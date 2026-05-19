package ai.xrav.xravscan.data.remote

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.UserAgent
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.slf4j.LoggerFactory
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/**
 * Builds the [HttpClient] used by every *update* path on Desktop
 * (BGPView, Bunny direct API, RIPE, Hackertarget). Two responsibilities:
 *
 *   1. **DNS-over-HTTPS** via Cloudflare (`https://1.1.1.1/dns-query`).
 *      Bootstrapped with the literal `1.1.1.1` / `1.0.0.1` so the very
 *      first lookup never hits the system resolver — fixes
 *      *"Unable to resolve host"* on networks where the recursive
 *      resolver is poisoned or blocked.
 *   2. Sensible update-traffic timeouts (8s connect, 30s request) and
 *      a stable User-Agent string.
 *
 * VPN-bypass à la Android (`ConnectivityManager.requestNetwork` with
 * `NET_CAPABILITY_NOT_VPN`) is intentionally **not** mirrored here:
 * the JVM has no equivalent system API, and DoH alone is enough for
 * `1.1.1.1` to keep resolving inside almost every commodity VPN.
 */
object UpdateHttpClient {
    private val log = LoggerFactory.getLogger("XRavScan.UpdateHttp")

    private val bootstrapClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build()
    }

    private val dohResolver: DnsOverHttps by lazy {
        DnsOverHttps.Builder()
            .client(bootstrapClient)
            .url("https://1.1.1.1/dns-query".toHttpUrl())
            .bootstrapDnsHosts(
                InetAddress.getByName("1.1.1.1"),
                InetAddress.getByName("1.0.0.1"),
            )
            .includeIPv6(true)
            .build()
    }

    /**
     * Single shared OkHttp client wired through Cloudflare DoH. All
     * Ktor services delegate to this so we only pay the bootstrap /
     * resolver setup cost once.
     */
    val dohOkHttp: OkHttpClient by lazy {
        log.info("Building update OkHttp client with Cloudflare DoH bootstrap")
        OkHttpClient.Builder()
            .dns(dohResolver)
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Convenience Ktor [HttpClient] for any service that just wants
     * `GET /…` + content negotiation. Pre-installed:
     * [ContentNegotiation] + [HttpTimeout] + [UserAgent].
     */
    fun ktor(): HttpClient = HttpClient(OkHttp) {
        engine { preconfigured = dohOkHttp }
        install(ContentNegotiation) {
            json(
                Json {
                    ignoreUnknownKeys = true
                    isLenient = true
                    explicitNulls = false
                },
            )
        }
        install(HttpTimeout) {
            connectTimeoutMillis = 8_000
            requestTimeoutMillis = 30_000
            socketTimeoutMillis = 15_000
        }
        install(UserAgent) {
            agent = "XRavScan-Desktop/1.0 (+https://github.com/Maykl044/X-Raw)"
        }
    }
}
