package ai.xrav.xravscan.data.scan

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Base64
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlin.time.Duration.Companion.milliseconds

/**
 * Phase K — Bunny CDN / VLESS-suitability probe.
 *
 * The standard `probeOne` flow only checks "is :443 open and does
 * anyone hand back a cert"; that is not enough for Bunny CDN edge IPs
 * because we need to know whether a given IP will actually proxy a
 * VLESS-over-WebSocket payload through the `b.cdn.net` (or custom)
 * hostname.
 *
 * This probe:
 *  1. Opens a raw TCP socket to `ip:443` (default).
 *  2. Wraps it in a TLS session with **SNI explicitly set** to the
 *     Bunny target host (default `b.cdn.net`).
 *  3. Reads the peer certificate. If the cert's SAN/CN does not match
 *     the Bunny hostname or a recognisable Bunny-CDN wildcard, the IP
 *     is discarded — the edge handed us back a stray cert, which
 *     means a real VLESS client would also be rejected.
 *  4. Writes a HTTP/1.1 `Upgrade: websocket` request with a random
 *     `Sec-WebSocket-Key` and `Sec-WebSocket-Version: 13`.
 *  5. Reads the response header block. Accepts the IP only if the
 *     status line is `101 Switching Protocols` (the canonical VLESS-
 *     over-WS success path) **or** a `200 / 400 / 403 / 404` from a
 *     `Server: BunnyCDN-...` header (the path is wrong but we
 *     hit a real Bunny edge, so a properly-configured pull-zone
 *     would still terminate the tunnel correctly).
 */
class BunnyVlessProbe(
    private val bunnyTarget: String = DEFAULT_BUNNY_TARGET,
) {

    /** Outcome of a single Bunny VLESS probe. */
    data class Outcome(
        val accepted: Boolean,
        val rttMs: Int,
        val statusLine: String?,
        val server: String?,
        val tlsCommonName: String?,
        val reason: String,
    )

    suspend fun probe(ip: String, port: Int = 443): Outcome {
        val started = System.currentTimeMillis()
        return runCatching {
            val plain = Socket()
            plain.tcpNoDelay = true
            plain.soTimeout = TLS_READ_TIMEOUT_MS
            plain.connect(InetSocketAddress(InetAddress.getByName(ip), port), TCP_CONNECT_TIMEOUT_MS)

            val ctx = SSLContext.getInstance("TLS")
            ctx.init(null, null, null)
            val factory = ctx.socketFactory as SSLSocketFactory
            val tls = factory.createSocket(plain, bunnyTarget, port, true) as SSLSocket
            tls.use { socket ->
                val params: SSLParameters = socket.sslParameters
                params.serverNames = listOf(SNIHostName(bunnyTarget))
                params.endpointIdentificationAlgorithm = null
                socket.sslParameters = params
                socket.soTimeout = TLS_READ_TIMEOUT_MS

                socket.startHandshake()

                val peer = runCatching {
                    socket.session.peerCertificates.firstOrNull() as? X509Certificate
                }.getOrNull()
                val cn = extractCn(peer)
                if (peer == null) {
                    return Outcome(
                        accepted = false,
                        rttMs = (System.currentTimeMillis() - started).toInt(),
                        statusLine = null,
                        server = null,
                        tlsCommonName = null,
                        reason = "no peer certificate after TLS handshake",
                    )
                }
                if (!certMatchesBunny(peer, cn)) {
                    return Outcome(
                        accepted = false,
                        rttMs = (System.currentTimeMillis() - started).toInt(),
                        statusLine = null,
                        server = null,
                        tlsCommonName = cn,
                        reason = "cert CN/SAN does not match $bunnyTarget — stray edge",
                    )
                }

                val wsKey = randomWsKey()
                val request = buildString {
                    append("GET / HTTP/1.1\r\n")
                    append("Host: $bunnyTarget\r\n")
                    append("Upgrade: websocket\r\n")
                    append("Connection: Upgrade\r\n")
                    append("Sec-WebSocket-Key: $wsKey\r\n")
                    append("Sec-WebSocket-Version: 13\r\n")
                    append("User-Agent: Mozilla/5.0 (X-RavScan-VLESS-Probe)\r\n")
                    append("Accept: */*\r\n")
                    append("\r\n")
                }
                socket.outputStream.write(request.toByteArray(Charsets.US_ASCII))
                socket.outputStream.flush()

                val reader = BufferedReader(InputStreamReader(socket.inputStream, Charsets.US_ASCII))
                val statusLine = reader.readLine()
                    ?: return Outcome(
                        accepted = false,
                        rttMs = (System.currentTimeMillis() - started).toInt(),
                        statusLine = null,
                        server = null,
                        tlsCommonName = cn,
                        reason = "no HTTP status line",
                    )

                var server: String? = null
                var contentLength = -1
                var lineCount = 0
                while (true) {
                    val header = reader.readLine() ?: break
                    if (header.isEmpty()) break
                    if (header.startsWith("Server:", ignoreCase = true)) {
                        server = header.substringAfter(':').trim()
                    } else if (header.startsWith("Content-Length:", ignoreCase = true)) {
                        contentLength = header.substringAfter(':').trim().toIntOrNull() ?: -1
                    }
                    lineCount += 1
                    if (lineCount >= 64) break
                }

                val status = statusLine.split(' ', limit = 3).getOrNull(1)?.toIntOrNull() ?: -1
                val serverLooksBunny = server?.contains("BunnyCDN", ignoreCase = true) == true ||
                    server?.contains("bunny", ignoreCase = true) == true
                val accepted = when {
                    status == 101 -> true
                    status in setOf(200, 400, 403, 404) && serverLooksBunny -> true
                    else -> false
                }
                val reason = when {
                    status == 101 -> "WebSocket Upgrade accepted (Server=${server ?: "?"})"
                    accepted -> "edge replied $status, Server=${server ?: "?"} — valid Bunny terminator"
                    status == -1 -> "no parseable status line ($statusLine)"
                    !serverLooksBunny -> "status $status, Server=${server ?: "<missing>"} — not Bunny CDN edge"
                    else -> "unexpected status $status (Server=${server ?: "?"})"
                }
                Outcome(
                    accepted = accepted,
                    rttMs = (System.currentTimeMillis() - started).toInt(),
                    statusLine = statusLine,
                    server = server,
                    tlsCommonName = cn,
                    reason = reason,
                )
            }
        }.getOrElse { t ->
            Log.d(TAG, "bunny vless probe $ip:$port failed: ${t.javaClass.simpleName}: ${t.message}")
            Outcome(
                accepted = false,
                rttMs = (System.currentTimeMillis() - started).toInt(),
                statusLine = null,
                server = null,
                tlsCommonName = null,
                reason = "${t.javaClass.simpleName}: ${t.message ?: "unknown"}",
            )
        }
    }

    private fun randomWsKey(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        return Base64.getEncoder().encodeToString(bytes)
    }

    private fun extractCn(cert: X509Certificate?): String? {
        val dn = cert?.subjectX500Principal?.name ?: return null
        return dn.split(',').firstNotNullOfOrNull { part ->
            val piece = part.trim()
            if (piece.startsWith("CN=", ignoreCase = true)) piece.substring(3) else null
        }
    }

    /**
     * Accepts the cert if its CN or any SAN matches the Bunny target,
     * a `*.b-cdn.net` wildcard, the bunnycdn.com / bunny.net family,
     * or carries the literal "bunny" token. Defends against catch-all
     * shared-host certs that route to the wrong virtual host.
     */
    private fun certMatchesBunny(cert: X509Certificate, cn: String?): Boolean {
        val target = bunnyTarget.lowercase()
        val candidates = mutableListOf<String>()
        cn?.let { candidates += it.lowercase() }
        runCatching {
            cert.subjectAlternativeNames?.forEach { alt ->
                val s = (alt.getOrNull(1) as? String)?.lowercase()
                if (!s.isNullOrBlank()) candidates += s
            }
        }
        return candidates.any { name ->
            name == target ||
                name == "b.cdn.net" ||
                name.endsWith(".b-cdn.net") ||
                name == "b-cdn.net" ||
                name.endsWith(".bunny.net") ||
                name.endsWith(".bunnycdn.com") ||
                name.contains("bunny", ignoreCase = true)
        }
    }

    companion object {
        const val DEFAULT_BUNNY_TARGET = "b.cdn.net"
        private const val TAG = "XRavScan.BunnyVless"
        private const val TCP_CONNECT_TIMEOUT_MS = 4_000
        private const val TLS_READ_TIMEOUT_MS = 6_000
    }
}
