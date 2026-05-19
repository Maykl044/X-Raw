package ai.xrav.xravscan.data.remote.direct

import ai.xrav.xravscan.data.remote.UpdateHttpClient
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import org.slf4j.LoggerFactory

/**
 * Fastly publishes `api.fastly.com/public-ip-list` — a tiny JSON
 * file with two flat arrays: `addresses` (IPv4 CIDRs) and
 * `ipv6_addresses` (IPv6 CIDRs). No auth required.
 */
class FastlyDirectProvider(
    private val client: OkHttpClient = UpdateHttpClient.dohOkHttp,
    private val json: Json = DEFAULT_JSON,
) : DirectRangeProvider {

    private val log = LoggerFactory.getLogger("XRavScan.Fastly")
    override val providerSlug: String = "fastly"

    @Serializable
    private data class FastlyResponse(
        val addresses: List<String> = emptyList(),
        @SerialName("ipv6_addresses") val ipv6Addresses: List<String> = emptyList(),
    )

    override suspend fun fetch(): DirectRangeProvider.Result =
        runCatching {
            client.newCall(Request.Builder().url(URL).build()).execute().use { resp ->
                if (!resp.isSuccessful) error("HTTP ${resp.code}")
                val body = resp.body?.string().orEmpty()
                val parsed = json.decodeFromString(FastlyResponse.serializer(), body)
                val cidrs = (parsed.addresses + parsed.ipv6Addresses).distinct()
                if (cidrs.isEmpty()) error("empty address list")
                log.info("Fastly: ${cidrs.size} CIDR(s) from public-ip-list")
                DirectRangeProvider.Result.ok(cidrs)
            }
        }.getOrElse { t ->
            log.warn("Fastly direct fetch failed: ${t.message}")
            DirectRangeProvider.Result.fallback(
                cidrs = HARDCODED_RANGES,
                reason = t.message ?: t::class.simpleName ?: "unknown error",
            )
        }

    companion object {
        const val URL = "https://api.fastly.com/public-ip-list"
        private val DEFAULT_JSON = Json {
            ignoreUnknownKeys = true
            isLenient = true
            explicitNulls = false
        }

        /** Fastly's published edge IP super-blocks as of 2025-10. */
        val HARDCODED_RANGES: List<String> = listOf(
            // IPv4
            "23.235.32.0/20",
            "43.249.72.0/22",
            "103.244.50.0/24",
            "103.245.222.0/23",
            "103.245.224.0/24",
            "104.156.80.0/20",
            "140.248.64.0/18",
            "140.248.128.0/17",
            "146.75.0.0/17",
            "151.101.0.0/16",
            "157.52.64.0/18",
            "167.82.0.0/17",
            "167.82.128.0/20",
            "167.82.160.0/20",
            "167.82.224.0/20",
            "172.111.64.0/18",
            "185.31.16.0/22",
            "199.27.72.0/21",
            "199.232.0.0/16",
            // IPv6
            "2a04:4e40::/32",
            "2a04:4e42::/32",
        )
    }
}
