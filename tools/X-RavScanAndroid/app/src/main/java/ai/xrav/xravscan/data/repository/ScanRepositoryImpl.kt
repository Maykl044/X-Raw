package ai.xrav.xravscan.data.repository

import ai.xrav.xravscan.data.local.dao.CidrRangeDao
import ai.xrav.xravscan.data.local.dao.FullScanStateDao
import ai.xrav.xravscan.data.local.dao.ProviderDao
import ai.xrav.xravscan.data.local.dao.ScanResultDao
import ai.xrav.xravscan.data.local.entity.FullScanStateEntity
import ai.xrav.xravscan.data.local.entity.ScanResultEntity
import ai.xrav.xravscan.domain.model.FullScanProgress
import ai.xrav.xravscan.domain.model.ScanResult
import ai.xrav.xravscan.domain.repository.ScanRepository
import ai.xrav.xravscan.domain.util.Cidr
import ai.xrav.xravscan.domain.util.IpStreamIterator
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
    private val fullScanStateDao: FullScanStateDao,
    private val networkMonitor: NetworkMonitor,
) : ScanRepository {

    override suspend fun pausedScanFor(providerSlug: String): ScanRepository.PausedScan? =
        withContext(Dispatchers.IO) {
            val row = fullScanStateDao.stateFor(providerSlug) ?: return@withContext null
            val cidrs = row.cidrSnapshot.split('\n').filter { it.isNotBlank() }
            // Phase J — paused-scan card shows the full uncapped total
            // so the Resume button picks up exactly where the user left
            // off (e.g. 4.5M / 6.6M for Cloudflare).
            val total = sumIpv4HostCount(cidrs)
            ScanRepository.PausedScan(
                providerSlug = row.providerSlug,
                cidrIndex = row.cidrIndex,
                ipOffset = row.ipOffset,
                ipsScanned = row.ipsScanned,
                ipsTotal = total,
                hits = row.hits,
                maxIps = row.maxIps,
                concurrency = row.concurrency,
            )
        }

    override suspend fun discardPausedScan(providerSlug: String) {
        withContext(Dispatchers.IO) { fullScanStateDao.clear(providerSlug) }
    }

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
        resume: Boolean,
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

        // ---- iterator + cursor -----------------------------------------
        // Resume mode rebuilds the iterator from the persisted snapshot
        // so the producer never re-walks IPs we already covered before
        // pause. A fresh scan takes the current CIDR list, snapshots it
        // (newline-joined) and stores it on the first cursor flush so
        // mid-scan provider edits cannot derail the resume position.
        val persisted = fullScanStateDao.stateFor(providerSlug)
        val rangesRaw: List<String>
        var startCidrIndex = 0
        var startIpOffset = 0L
        var carriedScanned = 0L
        var carriedHits = 0
        if (resume && persisted != null) {
            rangesRaw = persisted.cidrSnapshot.split('\n').filter { it.isNotBlank() }
            startCidrIndex = persisted.cidrIndex
            startIpOffset = persisted.ipOffset
            carriedScanned = persisted.ipsScanned
            carriedHits = persisted.hits
        } else {
            rangesRaw = cidrRangeDao.rangesForProvider(providerSlug)
                .map { it.cidr }
                .filter { raw ->
                    val parsed = Cidr.parse(raw) ?: return@filter false
                    parsed.version == 4 && parsed.prefixLen in 8..32
                }
            if (persisted != null) fullScanStateDao.clear(providerSlug)
        }

        // Phase J — uncapped. Total = sum of every IPv4 host in every
        // CIDR, with no clamping against an artificial ceiling. Cloudflare
        // resolves to ~6 684 672 IPs end-to-end and the UI progress bar
        // uses this exact figure as its dynamic maximum.
        val total = sumIpv4HostCount(rangesRaw).coerceAtLeast(0L)

        if (rangesRaw.isEmpty() || total == 0L) {
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

        send(
            FullScanProgress(
                providerSlug = providerSlug,
                providerName = provider.name,
                ipsScanned = carriedScanned,
                ipsTotal = total,
                hits = carriedHits,
                currentCidr = rangesRaw.getOrNull(startCidrIndex),
                message = if (resume && persisted != null) {
                    "Resuming Full scan — ${rangesRaw.size} CIDR(s), $carriedScanned / $total IP(s) already probed"
                } else {
                    "Full scan starting — ${rangesRaw.size} CIDR(s), target $total IP(s)"
                },
            ),
        )

        val pool = concurrency.coerceIn(1, 256)
        val iterator = if (resume && persisted != null) {
            IpStreamIterator.resume(rangesRaw, startCidrIndex, startIpOffset)
        } else {
            IpStreamIterator.fromStart(rangesRaw)
        }
        // Fixed bounded queue — never more than 1024 candidate IPs live
        // in volatile memory regardless of how many CIDRs we walk.
        val ipChannel = Channel<IpHandoff>(capacity = IP_CHANNEL_CAPACITY)
        val scanned = AtomicLong(carriedScanned)
        val hits = AtomicInteger(carriedHits)
        val pending = java.util.Collections.synchronizedList(mutableListOf<ScanResultEntity>())
        val lastEmittedAt = AtomicLong(0)
        val lastPersistAt = AtomicLong(0)
        val lastSeenCidr = java.util.concurrent.atomic.AtomicReference<String?>(
            rangesRaw.getOrNull(startCidrIndex),
        )
        val lastCursor = java.util.concurrent.atomic.AtomicReference(
            IpStreamIterator.Cursor(startCidrIndex, startIpOffset),
        )

        suspend fun persistCursor() {
            val cur = lastCursor.get()
            runCatching {
                fullScanStateDao.upsert(
                    FullScanStateEntity(
                        providerSlug = providerSlug,
                        cidrIndex = cur.cidrIndex,
                        ipOffset = cur.ipOffset,
                        ipsScanned = scanned.get(),
                        hits = hits.get(),
                        maxIps = maxIps,
                        concurrency = pool,
                        cidrSnapshot = rangesRaw.joinToString("\n"),
                        updatedAt = System.currentTimeMillis(),
                    ),
                )
            }.onFailure { Log.w(TAG, "cursor persist failed", it) }
        }

        // Producer — pumps the iterator into the bounded channel. `send`
        // suspends when the channel is full, giving us strict reactive
        // backpressure: the producer never gets ahead of the workers by
        // more than IP_CHANNEL_CAPACITY items.
        // Phase J — true stream condition. The producer ONLY stops when
        // the iterator runs out of IPs (`hasNext()` returns false) or the
        // surrounding coroutine is cancelled (Pause/Discard/screen exit).
        // No `scanned.get() >= maxIps` guard — every IP in every CIDR
        // makes it through the channel before the producer closes it.
        val producer = launch {
            try {
                while (isActive && iterator.hasNext()) {
                    val ip = iterator.next() ?: break
                    val currentRaw = iterator.currentCidr() ?: continue
                    lastSeenCidr.set(currentRaw)
                    lastCursor.set(iterator.cursor())
                    ipChannel.send(IpHandoff(ip = ip, cidr = currentRaw))
                }
            } finally {
                ipChannel.close()
            }
        }

        val workers = (1..pool).map {
            launch {
                // Phase J — uncapped worker. Drains the channel until the
                // producer closes it; no per-IP ceiling, no cap-based
                // early continue. Pause = surrounding job cancel = the
                // for-loop returns naturally and the cursor is flushed.
                for (handoff in ipChannel) {
                    val result = probeOne(providerSlug, handoff.ip, 443)
                    scanned.incrementAndGet()
                    lastSeenCidr.set(handoff.cidr)
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
                    val now = System.currentTimeMillis()
                    val prevEmit = lastEmittedAt.get()
                    if (now - prevEmit >= EMIT_INTERVAL_MS &&
                        lastEmittedAt.compareAndSet(prevEmit, now)
                    ) {
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
                    val prevPersist = lastPersistAt.get()
                    if (now - prevPersist >= PERSIST_INTERVAL_MS &&
                        lastPersistAt.compareAndSet(prevPersist, now)
                    ) {
                        persistCursor()
                    }
                }
            }
        }

        try {
            producer.join()
            workers.forEach { it.join() }
        } finally {
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
        }

        val cancelled = !currentCoroutineContext().isActive
        val message: String
        if (cancelled) {
            // Producer / workers were cancelled mid-flight: snapshot the
            // cursor so the user can Resume from exactly here.
            persistCursor()
            message = "Full scan paused — ${scanned.get()} of $total IP(s) probed, ${hits.get()} reachable. Tap Resume to continue."
        } else {
            // Phase J — clean completion only when the iterator was
            // mathematically exhausted (no artificial cap). Clear the
            // cursor so the next launch starts fresh from CIDR #0.
            fullScanStateDao.clear(providerSlug)
            message = "Full scan complete — ${scanned.get()} of $total IP(s) probed, ${hits.get()} reachable"
        }
        send(
            FullScanProgress(
                providerSlug = providerSlug,
                providerName = provider.name,
                ipsScanned = scanned.get(),
                ipsTotal = total,
                hits = hits.get(),
                currentCidr = lastSeenCidr.get().takeIf { cancelled },
                message = message,
                done = true,
                cancelled = cancelled,
            ),
        )
    }.flowOn(Dispatchers.IO)

    private data class IpHandoff(val ip: String, val cidr: String)

    /** Sum the IPv4 host count of every CIDR string, skipping IPv6/junk. */
    private fun sumIpv4HostCount(cidrs: List<String>): Long {
        var total = 0L
        for (raw in cidrs) {
            val net = Cidr.parse(raw) ?: continue
            if (net.version != 4 || net.prefixLen < 8 || net.prefixLen > 32) continue
            val hostBits = 32 - net.prefixLen
            val span = 1L shl hostBits
            total += when (net.prefixLen) {
                in 8..30 -> span - 2
                31 -> 2L
                32 -> 1L
                else -> 0L
            }
        }
        return total
    }

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
        /**
         * Hard upper bound on live IP-handoffs sitting between the
         * producer and the worker pool. With 1024 entries × ~24 bytes
         * each ≈ 24 KB worst-case — flat regardless of whether the
         * iterator is walking 50 000 or 50 000 000 IPs. Phase I.
         */
        private const val IP_CHANNEL_CAPACITY = 1024
        /**
         * Minimum interval between Pause-cursor writes to SQLite.
         * Same cadence as UI emits so we never write more often than
         * the user can see progress changing.
         */
        private const val PERSIST_INTERVAL_MS = 250L
    }
}
