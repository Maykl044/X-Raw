package ai.xrav.xravscan.data.repository

import ai.xrav.xravscan.db.XRavScanDb
import ai.xrav.xravscan.domain.model.FullScanProgress
import ai.xrav.xravscan.domain.model.ScanResult
import ai.xrav.xravscan.domain.util.Cidr
import ai.xrav.xravscan.domain.util.IpStreamIterator
import ai.xrav.xravscan.ui.network.NetworkMonitor
import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.security.cert.X509Certificate
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory

/**
 * Quick scan core: TCP connect + bare TLS handshake against random hosts
 * sampled from each enabled provider's CIDR set. Probes are fully
 * defensive — every host is wrapped in withTimeoutOrNull + runCatching
 * so a single unreachable IP never aborts the run.
 */
class ScanRepository(
    private val db: XRavScanDb,
    private val networkMonitor: NetworkMonitor? = null,
) {
    private val log = LoggerFactory.getLogger("XRavScan.Scan")
    private val q get() = db.xRavScanDbQueries

    fun observeRecent(limit: Long = 200): Flow<List<ScanResult>> =
        q.recentScanResults(limit).asFlow().mapToList(Dispatchers.IO).map { rows ->
            rows.map {
                ScanResult(
                    id = it.id,
                    providerSlug = it.providerSlug,
                    ip = it.ip,
                    port = it.port.toInt(),
                    rttMs = it.rttMs,
                    tlsCn = it.tlsCn,
                    foundAt = it.foundAt,
                )
            }
        }.flowOn(Dispatchers.IO)

    suspend fun runQuickScan(
        sampleSize: Int = 24,
        onLog: suspend (String) -> Unit,
    ): Int = withContext(Dispatchers.IO) {
        val providers = q.enabledProviders().executeAsList()
        if (providers.isEmpty()) {
            onLog("No enabled providers — nothing to scan")
            return@withContext 0
        }
        onLog("Quick scan starting — ${providers.size} provider(s), sample=$sampleSize each")

        val hits = coroutineScope {
            providers.flatMap { p ->
                val ranges = q.cidrsForProvider(p.id).executeAsList()
                val ips = pickRandomIps(ranges, sampleSize)
                if (ips.isEmpty()) {
                    return@flatMap emptyList()
                }
                onLog("${p.name}: probing ${ips.size} host(s)")
                ips.map { ip ->
                    async { probeOne(p.slug, ip, 443) }
                }
            }.awaitAll()
        }

        var inserted = 0
        for (probe in hits) {
            if (probe == null) continue
            q.insertScanResult(
                providerSlug = probe.providerSlug,
                ip = probe.ip,
                port = probe.port.toLong(),
                rttMs = probe.rttMs,
                tlsCn = probe.tlsCn,
                foundAt = probe.foundAt,
            )
            inserted++
        }
        onLog("Quick scan done — $inserted reachable host(s)")
        inserted
    }

    suspend fun clearAll() {
        withContext(Dispatchers.IO) { q.clearScanResults() }
    }

    /**
     * Phase I — snapshot of a previously-paused Full Provider Scan, as
     * persisted in the `full_scan_state` SQLite table. Exposed so the
     * UI can render a Resume/Discard card before the scan is
     * re-launched.
     */
    data class PausedScan(
        val providerSlug: String,
        val cidrIndex: Int,
        val ipOffset: Long,
        val ipsScanned: Long,
        val ipsTotal: Long,
        val hits: Int,
        val maxIps: Long,
        val concurrency: Int,
    )

    suspend fun pausedScanFor(providerSlug: String): PausedScan? =
        withContext(Dispatchers.IO) {
            val row = q.fullScanStateFor(providerSlug).executeAsOneOrNull() ?: return@withContext null
            val cidrList = row.cidrSnapshot.split('\n').filter { it.isNotBlank() }
            val total = sumIpv4HostCount(cidrList).coerceAtMost(row.maxIps).coerceAtLeast(0L)
            PausedScan(
                providerSlug = row.providerSlug,
                cidrIndex = row.cidrIndex.toInt(),
                ipOffset = row.ipOffset,
                ipsScanned = row.ipsScanned,
                ipsTotal = total,
                hits = row.hits.toInt(),
                maxIps = row.maxIps,
                concurrency = row.concurrency.toInt(),
            )
        }

    suspend fun discardPausedScan(providerSlug: String) {
        withContext(Dispatchers.IO) { q.clearFullScanState(providerSlug) }
    }

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
    // full provider scan
    // ------------------------------------------------------------------

    /**
     * Streams a [FullScanProgress] for every meaningful state change as
     * the scanner walks every IP in every CIDR of [providerSlug].
     *
     * Architecture:
     *  - One producer coroutine iterates the lazy [Cidr.expandIpv4]
     *    sequences and pushes `(ip, cidr)` pairs onto a bounded
     *    [Channel]. Bounded so the producer blocks if workers fall
     *    behind, instead of materialising millions of IPs in RAM.
     *  - [concurrency] worker coroutines drain the channel and probe
     *    each IP via [probeOne].
     *  - Successful probes are buffered and flushed to SQLite in
     *    batches of [BATCH_FLUSH] hits so we never spam single
     *    INSERTs.
     *  - Progress is emitted at most every [EMIT_INTERVAL_MS] ms to
     *    keep Compose from drowning in recompositions.
     *
     * The flow is collected on [Dispatchers.IO] and the consumer can
     * always `.cancel()` to stop the whole tree.
     */
    fun runFullProviderScan(
        providerSlug: String,
        maxIps: Long = 50_000L,
        concurrency: Int = 64,
        resume: Boolean = false,
    ): Flow<FullScanProgress> = channelFlow {
        val providerRow = withContext(Dispatchers.IO) {
            q.providerBySlug(providerSlug).executeAsOneOrNull()
        }
        if (providerRow == null) {
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
        val providerName = providerRow.name

        // Pre-flight: refuse to start if the network monitor reports a
        // VPN/offline state. Caller will see one paused progress and
        // the flow completes.
        val netState = networkMonitor?.state?.value
        if (netState != null && netState.shouldPauseScans) {
            val msg = if (netState.isVpn) {
                "VPN active — Full scan paused. Disable the VPN to continue."
            } else {
                "Offline — Full scan paused. Reconnect to continue."
            }
            send(
                FullScanProgress(
                    providerSlug = providerSlug,
                    providerName = providerName,
                    ipsScanned = 0,
                    ipsTotal = 0,
                    hits = 0,
                    currentCidr = null,
                    message = msg,
                    done = true,
                    cancelled = true,
                ),
            )
            return@channelFlow
        }

        // Phase I — iterator + cursor. Resume reads the persisted CIDR
        // snapshot so a provider edit between Pause and Resume cannot
        // derail the position. A fresh run snapshots the current CIDR
        // list and clears any stale cursor.
        val persisted = withContext(Dispatchers.IO) {
            q.fullScanStateFor(providerSlug).executeAsOneOrNull()
        }
        val rangesRaw: List<String>
        var startCidrIndex = 0
        var startIpOffset = 0L
        var carriedScanned = 0L
        var carriedHits = 0
        if (resume && persisted != null) {
            rangesRaw = persisted.cidrSnapshot.split('\n').filter { it.isNotBlank() }
            startCidrIndex = persisted.cidrIndex.toInt()
            startIpOffset = persisted.ipOffset
            carriedScanned = persisted.ipsScanned
            carriedHits = persisted.hits.toInt()
        } else {
            rangesRaw = withContext(Dispatchers.IO) {
                q.cidrsForProvider(providerRow.id).executeAsList()
                    .filter { raw ->
                        val parsed = Cidr.parse(raw) ?: return@filter false
                        parsed.version == 4 && parsed.prefixLen in 8..32
                    }
            }
            if (persisted != null) {
                withContext(Dispatchers.IO) { q.clearFullScanState(providerSlug) }
            }
        }

        val plannedTotal = sumIpv4HostCount(rangesRaw).coerceAtMost(maxIps).coerceAtLeast(0L)
        if (rangesRaw.isEmpty() || plannedTotal == 0L) {
            send(
                FullScanProgress(
                    providerSlug = providerSlug,
                    providerName = providerName,
                    ipsScanned = 0,
                    ipsTotal = 0,
                    hits = 0,
                    currentCidr = null,
                    message = "No IPv4 CIDR ranges for $providerName",
                    done = true,
                ),
            )
            return@channelFlow
        }

        send(
            FullScanProgress(
                providerSlug = providerSlug,
                providerName = providerName,
                ipsScanned = carriedScanned,
                ipsTotal = plannedTotal,
                hits = carriedHits,
                currentCidr = rangesRaw.getOrNull(startCidrIndex),
                message = if (resume && persisted != null) {
                    "Resuming Full scan — ${rangesRaw.size} CIDR(s), $carriedScanned / $plannedTotal IP(s) already probed"
                } else {
                    "Full scan starting — ${rangesRaw.size} CIDR(s), target $plannedTotal IP(s)"
                },
            ),
        )

        val pool = concurrency.coerceIn(1, 256)
        val iterator = if (resume && persisted != null) {
            IpStreamIterator.resume(rangesRaw, startCidrIndex, startIpOffset)
        } else {
            IpStreamIterator.fromStart(rangesRaw)
        }
        // Bounded queue — max 1024 live IPs in flight regardless of scan size.
        val workQueue = Channel<IpHandoff>(capacity = IP_CHANNEL_CAPACITY)
        val scanned = AtomicLong(carriedScanned)
        val hits = AtomicInteger(carriedHits)
        val lastEmit = AtomicLong(0)
        val lastPersist = AtomicLong(0)
        val lastSeenCidr = java.util.concurrent.atomic.AtomicReference<String?>(
            rangesRaw.getOrNull(startCidrIndex),
        )
        val lastCursor = java.util.concurrent.atomic.AtomicReference(
            IpStreamIterator.Cursor(startCidrIndex, startIpOffset),
        )
        val pendingHits = mutableListOf<Probe>()
        val pendingLock = Object()
        var cancelledFlag = false

        suspend fun persistCursor() {
            val cur = lastCursor.get()
            withContext(Dispatchers.IO) {
                runCatching {
                    q.upsertFullScanState(
                        providerSlug = providerSlug,
                        cidrIndex = cur.cidrIndex.toLong(),
                        ipOffset = cur.ipOffset,
                        ipsScanned = scanned.get(),
                        hits = hits.get().toLong(),
                        maxIps = maxIps,
                        concurrency = pool.toLong(),
                        cidrSnapshot = rangesRaw.joinToString("\n"),
                        updatedAt = System.currentTimeMillis(),
                    )
                }.onFailure { log.warn("cursor persist failed: ${it.message}") }
            }
        }

        suspend fun maybeEmit(force: Boolean = false) {
            val now = System.currentTimeMillis()
            val prev = lastEmit.get()
            if (!force && now - prev < EMIT_INTERVAL_MS) return
            if (!force && !lastEmit.compareAndSet(prev, now)) return
            if (force) lastEmit.set(now)
            send(
                FullScanProgress(
                    providerSlug = providerSlug,
                    providerName = providerName,
                    ipsScanned = scanned.get(),
                    ipsTotal = plannedTotal,
                    hits = hits.get(),
                    currentCidr = lastSeenCidr.get(),
                ),
            )
        }

        suspend fun flushBatch(force: Boolean = false) {
            val toFlush = synchronized(pendingLock) {
                if (!force && pendingHits.size < BATCH_FLUSH) return
                if (pendingHits.isEmpty()) return
                val copy = pendingHits.toList()
                pendingHits.clear()
                copy
            }
            withContext(Dispatchers.IO) {
                q.transaction {
                    for (p in toFlush) {
                        q.insertScanResult(
                            providerSlug = p.providerSlug,
                            ip = p.ip,
                            port = p.port.toLong(),
                            rttMs = p.rttMs,
                            tlsCn = p.tlsCn,
                            foundAt = p.foundAt,
                        )
                    }
                }
            }
        }

        suspend fun maybePersist() {
            val now = System.currentTimeMillis()
            val prev = lastPersist.get()
            if (now - prev < PERSIST_INTERVAL_MS) return
            if (!lastPersist.compareAndSet(prev, now)) return
            persistCursor()
        }

        coroutineScope {
            // Producer — pumps IpStreamIterator into the bounded channel.
            // `send` suspends when the channel is full, giving us strict
            // backpressure: the iterator never gets ahead of the workers
            // by more than IP_CHANNEL_CAPACITY items.
            val producer = launch(Dispatchers.Default) {
                try {
                    while (isActive) {
                        if (scanned.get() >= maxIps) break
                        val ip = iterator.next() ?: break
                        val currentRaw = iterator.currentCidr() ?: continue
                        lastSeenCidr.set(currentRaw)
                        lastCursor.set(iterator.cursor())
                        workQueue.send(IpHandoff(ip = ip, cidr = currentRaw))
                    }
                } finally {
                    workQueue.close()
                }
            }

            val workers = List(pool) {
                async(Dispatchers.IO) {
                    for (handoff in workQueue) {
                        if (!isActive) break
                        if (scanned.get() >= maxIps) continue
                        val probe = runCatching { probeOne(providerSlug, handoff.ip, 443) }
                            .getOrNull()
                        scanned.incrementAndGet()
                        lastSeenCidr.set(handoff.cidr)
                        if (probe != null) {
                            hits.incrementAndGet()
                            synchronized(pendingLock) { pendingHits += probe }
                            flushBatch(force = false)
                        }
                        maybeEmit(force = false)
                        maybePersist()
                    }
                }
            }

            try {
                producer.join()
                workers.awaitAll()
            } catch (t: Throwable) {
                cancelledFlag = true
                throw t
            } finally {
                flushBatch(force = true)
                maybeEmit(force = true)
            }
        }

        val finalMessage: String
        if (cancelledFlag) {
            // Snapshot the cursor so the user can Resume from exactly here.
            persistCursor()
            finalMessage = "Full scan paused — ${scanned.get()} of $plannedTotal IP(s) probed, ${hits.get()} reachable. Click Resume to continue."
        } else {
            withContext(Dispatchers.IO) { q.clearFullScanState(providerSlug) }
            finalMessage = "Full scan complete — ${scanned.get()} IP(s) probed, ${hits.get()} reachable"
        }
        send(
            FullScanProgress(
                providerSlug = providerSlug,
                providerName = providerName,
                ipsScanned = scanned.get(),
                ipsTotal = plannedTotal,
                hits = hits.get(),
                currentCidr = lastSeenCidr.get().takeIf { cancelledFlag },
                message = finalMessage,
                done = true,
                cancelled = cancelledFlag,
            ),
        )
    }.flowOn(Dispatchers.IO)

    private data class IpHandoff(val ip: String, val cidr: String)

    // ------------------------------------------------------------------
    // sampling
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
        if (net.version != 4 || net.prefixLen < 8 || net.prefixLen >= 31) return null
        val canonical = net.canonical.substringBefore('/')
        val parts = canonical.split('.').mapNotNull { it.toIntOrNull() }
        if (parts.size != 4) return null
        val baseInt = (parts[0] shl 24) or (parts[1] shl 16) or (parts[2] shl 8) or parts[3]
        val hostBits = 32 - net.prefixLen
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

    // ------------------------------------------------------------------
    // probe
    // ------------------------------------------------------------------

    private data class Probe(
        val providerSlug: String,
        val ip: String,
        val port: Int,
        val rttMs: Long,
        val tlsCn: String?,
        val foundAt: Long,
    )

    private suspend fun probeOne(slug: String, ip: String, port: Int): Probe? {
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

        val rtt = System.currentTimeMillis() - started
        val cn = withTimeoutOrNull(3_500) { tlsHandshakeCn(ip, port) }

        return Probe(
            providerSlug = slug,
            ip = ip,
            port = port,
            rttMs = rtt,
            tlsCn = cn,
            foundAt = System.currentTimeMillis(),
        )
    }

    private fun tlsHandshakeCn(ip: String, port: Int): String? = runCatching {
        val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
        (factory.createSocket(ip, port) as SSLSocket).use { socket ->
            val params = socket.sslParameters
            params.serverNames = emptyList<SNIHostName>()
            socket.sslParameters = params
            socket.soTimeout = 2_500
            socket.startHandshake()
            val cert = socket.session.peerCertificates.firstOrNull() ?: return@use null
            val dn = (cert as? X509Certificate)?.subjectX500Principal?.name
            extractCn(dn)
        }
    }.onFailure { log.debug("tls $ip:$port failed: ${it.message}") }.getOrNull()

    private fun extractCn(dn: String?): String? {
        if (dn.isNullOrBlank()) return null
        return dn.split(',').firstNotNullOfOrNull { part ->
            val piece = part.trim()
            if (piece.startsWith("CN=", ignoreCase = true)) piece.substring(3) else null
        }
    }

    private companion object {
        /** Hits buffered before flushing to SQLite in one transaction. */
        const val BATCH_FLUSH = 200

        /** Minimum gap between progress emissions, in ms. */
        const val EMIT_INTERVAL_MS = 250L

        /**
         * Hard upper bound on live IP-handoffs sitting between the
         * producer and the worker pool. With 1024 entries × ~24 bytes
         * each ≈ 24 KB worst-case — flat regardless of whether the
         * iterator is walking 50 000 or 50 000 000 IPs. Phase I.
         */
        const val IP_CHANNEL_CAPACITY = 1024

        /** Minimum interval between Pause-cursor writes to SQLite. */
        const val PERSIST_INTERVAL_MS = 250L
    }
}
