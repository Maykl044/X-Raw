package ai.xrav.xravscan.data.repository

import ai.xrav.xravscan.data.local.dao.CidrRangeDao
import ai.xrav.xravscan.data.local.dao.ProviderDao
import ai.xrav.xravscan.data.local.dao.ScanResultDao
import ai.xrav.xravscan.data.local.entity.ScanResultEntity
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
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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
    }
}
