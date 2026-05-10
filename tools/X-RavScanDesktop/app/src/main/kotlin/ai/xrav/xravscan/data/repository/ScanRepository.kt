package ai.xrav.xravscan.data.repository

import ai.xrav.xravscan.db.XRavScanDb
import ai.xrav.xravscan.domain.model.ScanResult
import ai.xrav.xravscan.domain.util.Cidr
import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.security.cert.X509Certificate
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory

/**
 * Quick scan core: TCP connect + bare TLS handshake against random hosts
 * sampled from each enabled provider's CIDR set. Probes are fully
 * defensive — every host is wrapped in withTimeoutOrNull + runCatching
 * so a single unreachable IP never aborts the run.
 */
class ScanRepository(private val db: XRavScanDb) {
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
}
