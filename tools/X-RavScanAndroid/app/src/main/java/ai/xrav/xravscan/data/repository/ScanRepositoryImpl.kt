package ai.xrav.xravscan.data.repository

import ai.xrav.xravscan.data.local.dao.CidrRangeDao
import ai.xrav.xravscan.data.local.dao.ProviderDao
import ai.xrav.xravscan.data.local.dao.ScanResultDao
import ai.xrav.xravscan.data.local.entity.ScanResultEntity
import ai.xrav.xravscan.domain.model.FullScanProgress
import ai.xrav.xravscan.domain.model.ScanResult
import ai.xrav.xravscan.domain.repository.ScanRepository
import ai.xrav.xravscan.domain.util.Cidr
import ai.xrav.xravscan.ui.network.NetworkMonitor
import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

@Singleton
class ScanRepositoryImpl @Inject constructor(
    private val providerDao: ProviderDao,
    private val cidrRangeDao: CidrRangeDao,
    private val scanResultDao: ScanResultDao,
    private val networkMonitor: NetworkMonitor,
) : ScanRepository {

    override fun observeRecent(limit: Int): Flow<List<ScanResult>> =
        scanResultDao.observeRecent(limit).map { rows -> rows.map { it.toDomain() } }

    override suspend fun runQuickScan(
        sampleSize: Int,
        onLog: (String) -> Unit,
    ): Int = withContext(Dispatchers.IO) {
        val net = networkMonitor.state.value
        if (net.isVpn) {
            onLog("VPN active — Quick scan paused. Disable the VPN to continue.")
            return@withContext 0
        }
        if (!net.available) {
            onLog("Offline — Quick scan paused until connectivity returns.")
            return@withContext 0
        }

        val providers = providerDao.observeAllWithCount().first().filter { it.enabled }
        if (providers.isEmpty()) {
            onLog("No enabled providers — nothing to scan")
            return@withContext 0
        }
        onLog("Quick scan starting — ${providers.size} provider(s), sample=$sampleSize each")

        coroutineScope {
            providers.flatMap { p ->
                val ranges = cidrRangeDao.rangesForProvider(p.slug)
                val ips = pickRandomIps(ranges.map { it.cidr }, sampleSize)
                if (ips.isEmpty()) {
                    onLog("${p.name}: no IPs sampled")
                    return@flatMap emptyList()
                }
                onLog("${p.name}: probing ${ips.size} host(s)")
                ips.map { ip ->
                    async {
                        probeOne(p.slug, ip, 443)
                    }
                }
            }.awaitAll()
        }.count { result ->
            if (result != null) {
                scanResultDao.insert(result)
                true
            } else {
                false
            }
        }.also { hits ->
            onLog("Quick scan done — $hits reachable host(s)")
        }
    }

    override suspend fun clearAll() {
        withContext(Dispatchers.IO) { scanResultDao.deleteAll() }
    }

    // ------------------------------------------------------------------
    // Full Provider Scan — exhaustive expansion + bounded concurrency
    // ------------------------------------------------------------------

    override fun runFullProviderScan(
        providerSlug: String,
        maxIps: Long,
        concurrency: Int,
    ): Flow<FullScanProgress> = channelFlow {
        val providers = providerDao.observeAllWithCount().first()
        val provider = providers.firstOrNull { it.slug == providerSlug }
        if (provider == null) {
            send(
                FullScanProgress(
                    providerSlug = providerSlug,
                    providerName = providerSlug,
                    ipsScanned = 0,
                    ipsTotal = 0,
                    hits = 0,
                    currentCidr = null,
                    message = "Provider not found",
                    done = true,
                ),
            )
            return@channelFlow
        }
        val net = networkMonitor.state.value
        if (net.isVpn) {
            send(
                FullScanProgress(
                    providerSlug = providerSlug,
                    providerName = provider.name,
                    ipsScanned = 0,
                    ipsTotal = 0,
                    hits = 0,
                    currentCidr = null,
                    message = "VPN active — Full scan paused. Disable the VPN to continue.",
                    done = true,
                ),
            )
            return@channelFlow
        }
        if (!net.available) {
            send(
                FullScanProgress(
                    providerSlug = providerSlug,
                    providerName = provider.name,
                    ipsScanned = 0,
                    ipsTotal = 0,
                    hits = 0,
                    currentCidr = null,
                    message = "Offline — Full scan paused until connectivity returns.",
                    done = true,
                ),
            )
            return@channelFlow
        }

        val ranges = cidrRangeDao.rangesForProvider(providerSlug)
            .mapNotNull { row -> Cidr.parse(row.cidr)?.let { it to row.cidr } }
            .filter { it.first.version == 4 && it.first.prefixLen in 8..32 }

        // Sum total — clamp to maxIps so the progress bar stays meaningful.
        val rawTotal = ranges.sumOf { it.first.size }
        val total = minOf(rawTotal, maxIps).coerceAtLeast(0)

        send(
            FullScanProgress(
                providerSlug = providerSlug,
                providerName = provider.name,
                ipsScanned = 0,
                ipsTotal = total,
                hits = 0,
                currentCidr = null,
                message = "Full scan starting — ${ranges.size} CIDR(s), target $total IP(s)",
            ),
        )

        if (total == 0L) {
            send(
                FullScanProgress(
                    providerSlug = providerSlug,
                    providerName = provider.name,
                    ipsScanned = 0,
                    ipsTotal = 0,
                    hits = 0,
                    currentCidr = null,
                    message = "No scannable IPv4 ranges for ${provider.name}",
                    done = true,
                ),
            )
            return@channelFlow
        }

        val pool = concurrency.coerceIn(1, 256)
        val ipChannel = Channel<Pair<String, String>>(capacity = pool * 4)
        val scanned = AtomicLong(0)
        val hits = AtomicInteger(0)
        val pending = java.util.Collections.synchronizedList(mutableListOf<ScanResultEntity>())
        val lastEmittedAt = AtomicLong(0)
        val lastSeenCidr = java.util.concurrent.atomic.AtomicReference<String?>(null)

        // Producer — lazily walks every CIDR and pushes each IP onto the
        // bounded channel. Stops when [maxIps] reached or the consumer
        // collector is cancelled.
        val producer = launch {
            outer@ for ((cidr, raw) in ranges) {
                lastSeenCidr.set(raw)
                for (ip in cidr.expandIpv4()) {
                    if (!isActive) break@outer
                    if (scanned.get() >= maxIps) break@outer
                    ipChannel.send(ip to raw)
                }
            }
            ipChannel.close()
        }

        // Workers — drain the channel in parallel, probe each IP, batch
        // hits into the DAO every BATCH_FLUSH writes.
        val workers = (1..pool).map {
            launch {
                for ((ip, raw) in ipChannel) {
                    if (scanned.get() >= maxIps) {
                        // Drain the rest of the channel quickly without probing.
                        continue
                    }
                    val result = probeOne(providerSlug, ip, 443)
                    val current = scanned.incrementAndGet()
                    lastSeenCidr.set(raw)
                    if (result != null) {
                        pending += result
                        hits.incrementAndGet()
                        if (pending.size >= BATCH_FLUSH) {
                            val toFlush = synchronized(pending) {
                                if (pending.size >= BATCH_FLUSH) {
                                    val copy = pending.toList()
                                    pending.clear()
                                    copy
                                } else {
                                    emptyList()
                                }
                            }
                            if (toFlush.isNotEmpty()) {
                                runCatching { scanResultDao.insertAll(toFlush) }
                                    .onFailure { Log.w(TAG, "batch flush failed", it) }
                            }
                        }
                    }
                    if (current >= maxIps) {
                        ipChannel.close()
                    }
                    val now = System.currentTimeMillis()
                    val prev = lastEmittedAt.get()
                    if (now - prev >= EMIT_INTERVAL_MS && lastEmittedAt.compareAndSet(prev, now)) {
                        trySend(
                            FullScanProgress(
                                providerSlug = providerSlug,
                                providerName = provider.name,
                                ipsScanned = scanned.get(),
                                ipsTotal = total,
                                hits = hits.get(),
                                currentCidr = lastSeenCidr.get(),
                            ),
                        )
                    }
                }
            }
        }

        producer.join()
        workers.forEach { it.join() }

        // Drain any remaining buffered hits.
        val tail = synchronized(pending) {
            val copy = pending.toList()
            pending.clear()
            copy
        }
        if (tail.isNotEmpty()) {
            runCatching { scanResultDao.insertAll(tail) }
                .onFailure { Log.w(TAG, "final flush failed", it) }
        }

        val cancelled = !currentCoroutineContext().isActive
        send(
            FullScanProgress(
                providerSlug = providerSlug,
                providerName = provider.name,
                ipsScanned = scanned.get().coerceAtMost(total),
                ipsTotal = total,
                hits = hits.get(),
                currentCidr = null,
                message = if (cancelled) {
                    "Full scan cancelled — ${scanned.get()} of $total IP(s) probed, ${hits.get()} reachable"
                } else {
                    "Full scan complete — ${scanned.get().coerceAtMost(total)} IP(s) probed, ${hits.get()} reachable"
                },
                done = true,
                cancelled = cancelled,
            ),
        )
    }.flowOn(Dispatchers.IO)

    // ------------------------------------------------------------------
    // sample + probe
    // ------------------------------------------------------------------

    private fun pickRandomIps(cidrs: List<String>, sampleSize: Int): List<String> {
        if (cidrs.isEmpty()) return emptyList()
        val ipv4 = cidrs.mapNotNull { Cidr.parse(it) }.filter { it.version == 4 }
        if (ipv4.isEmpty()) return emptyList()
        val out = mutableListOf<String>()
        val rng = java.util.Random()
        var attempts = 0
        while (out.size < sampleSize && attempts < sampleSize * 5) {
            val net = ipv4.random()
            val host = randomHostInCidr(net, rng) ?: continue
            if (host !in out) out += host
            attempts++
        }
        return out
    }

    private fun randomHostInCidr(net: Cidr, rng: java.util.Random): String? {
        // Render network address bytes, randomise the host portion within
        // the prefix, return dotted-quad. Only sensible /8 .. /30 ranges.
        if (net.version != 4 || net.prefixLen < 8 || net.prefixLen >= 31) return null
        val canonical = net.canonical.substringBefore('/')
        val parts = canonical.split('.').mapNotNull { it.toIntOrNull() }
        if (parts.size != 4) return null
        val baseInt = (parts[0] shl 24) or (parts[1] shl 16) or (parts[2] shl 8) or parts[3]
        val hostBits = 32 - net.prefixLen
        // hostBits in [2, 24] here, so mask fits comfortably in Int.
        val mask = (1 shl hostBits) - 1
        var hostPart: Int
        var safety = 0
        do {
            hostPart = rng.nextInt(mask + 1)
            safety++
        } while ((hostPart == 0 || hostPart == mask) && safety < 8)
        val ipInt = baseInt.toLong().and(0xFFFFFFFFL).or(hostPart.toLong())
        val a = (ipInt shr 24) and 0xFF
        val b = (ipInt shr 16) and 0xFF
        val c = (ipInt shr 8) and 0xFF
        val d = ipInt and 0xFF
        return "$a.$b.$c.$d"
    }

    private suspend fun probeOne(
        slug: String,
        ip: String,
        port: Int,
    ): ScanResultEntity? {
        val started = System.currentTimeMillis()
        val tcpOpen = withTimeoutOrNull(2_500) {
            runCatching {
                Socket().use { s ->
                    s.connect(InetSocketAddress(InetAddress.getByName(ip), port), 2_000)
                    true
                }
            }.getOrDefault(false)
        } ?: false
        if (!tcpOpen) return null

        val rtt = (System.currentTimeMillis() - started).toInt()
        val cn = withTimeoutOrNull(3_500) { tlsHandshakeCommonName(ip, port) }

        return ScanResultEntity(
            providerSlug = slug,
            ip = ip,
            port = port,
            sni = null,
            rttMs = rtt,
            tlsCertCn = cn,
            scannedAt = System.currentTimeMillis(),
        )
    }

    private fun tlsHandshakeCommonName(ip: String, port: Int): String? = runCatching {
        val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
        (factory.createSocket(ip, port) as SSLSocket).use { socket ->
            // No SNI — we only have the IP. Some servers will refuse,
            // others (Cloudflare/Akamai) will still hand back a cert.
            val params = socket.sslParameters
            params.serverNames = listOf<SNIHostName>()
            socket.sslParameters = params
            socket.soTimeout = 2_500
            socket.startHandshake()
            val cert = socket.session.peerCertificates.firstOrNull() ?: return@use null
            val dn = (cert as? java.security.cert.X509Certificate)?.subjectX500Principal?.name
            extractCn(dn)
        }
    }.onFailure { Log.d(TAG, "tls $ip:$port failed: ${it.message}") }.getOrNull()

    private fun extractCn(dn: String?): String? {
        if (dn.isNullOrBlank()) return null
        return dn.split(',').firstNotNullOfOrNull { part ->
            val piece = part.trim()
            if (piece.startsWith("CN=", ignoreCase = true)) piece.substring(3) else null
        }
    }

    private fun ScanResultEntity.toDomain(): ScanResult = ScanResult(
        id = id,
        providerSlug = providerSlug,
        ip = ip,
        port = port,
        sni = sni,
        rttMs = rttMs,
        tlsCertCn = tlsCertCn,
        scannedAt = scannedAt,
    )

    companion object {
        private const val TAG = "XRavScan.Scan"
        /** Number of buffered hits before we flush to SQLite. */
        private const val BATCH_FLUSH = 200
        /** Minimum interval between progress emissions (milliseconds). */
        private const val EMIT_INTERVAL_MS = 250L
    }
}
