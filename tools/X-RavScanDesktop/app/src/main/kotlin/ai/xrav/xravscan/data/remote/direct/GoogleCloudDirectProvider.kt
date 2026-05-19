package ai.xrav.xravscan.data.remote.direct

import ai.xrav.xravscan.data.remote.UpdateHttpClient
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import org.slf4j.LoggerFactory

/**
 * Google Cloud publishes the union of every GCP service IP range in
 * `gstatic.com/ipranges/cloud.json`. Each entry has an optional
 * `ipv4Prefix` and `ipv6Prefix` field plus `service` / `scope`
 * metadata that we ignore for now (we want everything).
 */
class GoogleCloudDirectProvider(
    private val client: OkHttpClient = UpdateHttpClient.dohOkHttp,
    private val json: Json = DEFAULT_JSON,
) : DirectRangeProvider {

    private val log = LoggerFactory.getLogger("XRavScan.GCP")
    override val providerSlug: String = "gcp"

    @Serializable
    private data class GcpResponse(
        val syncToken: String? = null,
        val creationTime: String? = null,
        val prefixes: List<GcpPrefix> = emptyList(),
    )

    @Serializable
    private data class GcpPrefix(
        @SerialName("ipv4Prefix") val ipv4Prefix: String? = null,
        @SerialName("ipv6Prefix") val ipv6Prefix: String? = null,
        val service: String = "",
        val scope: String = "",
    )

    override suspend fun fetch(): DirectRangeProvider.Result =
        runCatching {
            client.newCall(Request.Builder().url(URL).build()).execute().use { resp ->
                if (!resp.isSuccessful) error("HTTP ${resp.code}")
                val body = resp.body?.string().orEmpty()
                val parsed = json.decodeFromString(GcpResponse.serializer(), body)
                val cidrs = parsed.prefixes
                    .flatMap { listOfNotNull(it.ipv4Prefix, it.ipv6Prefix) }
                    .distinct()
                if (cidrs.isEmpty()) error("empty prefix list")
                log.info("GCP: ${cidrs.size} CIDR(s) from cloud.json")
                DirectRangeProvider.Result.ok(cidrs)
            }
        }.getOrElse { t ->
            log.warn("GCP direct fetch failed: ${t.message}")
            DirectRangeProvider.Result.fallback(
                cidrs = HARDCODED_RANGES,
                reason = t.message ?: t::class.simpleName ?: "unknown error",
            )
        }

    companion object {
        const val URL = "https://www.gstatic.com/ipranges/cloud.json"
        private val DEFAULT_JSON = Json {
            ignoreUnknownKeys = true
            isLenient = true
            explicitNulls = false
        }

        /** Stable Google Cloud super-blocks pulled from cloud.json snapshots. */
        val HARDCODED_RANGES: List<String> = listOf(
            "8.8.4.0/24",
            "8.8.8.0/24",
            "8.34.208.0/20",
            "8.35.192.0/20",
            "23.236.48.0/20",
            "23.251.128.0/19",
            "34.0.0.0/9",
            "34.128.0.0/10",
            "35.184.0.0/13",
            "35.192.0.0/14",
            "35.196.0.0/15",
            "35.198.0.0/16",
            "35.199.0.0/17",
            "35.199.128.0/18",
            "35.200.0.0/13",
            "35.208.0.0/12",
            "35.224.0.0/12",
            "35.240.0.0/13",
            "64.15.112.0/20",
            "64.233.160.0/19",
            "66.102.0.0/20",
            "66.249.64.0/19",
            "70.32.128.0/19",
            "72.14.192.0/18",
            "74.114.24.0/21",
            "74.125.0.0/16",
            "104.154.0.0/15",
            "104.196.0.0/14",
            "104.237.160.0/19",
            "107.167.160.0/19",
            "107.178.192.0/18",
            "108.59.80.0/20",
            "108.170.192.0/20",
            "108.177.0.0/17",
            "130.211.0.0/16",
            "136.124.0.0/15",
            "146.148.0.0/17",
            "162.216.148.0/22",
            "162.222.176.0/21",
            "172.110.32.0/21",
            "172.217.0.0/16",
            "172.253.0.0/16",
            "173.194.0.0/16",
            "173.255.112.0/20",
            "192.158.28.0/22",
            "199.192.112.0/22",
            "199.223.232.0/21",
            "207.223.160.0/20",
            "208.65.152.0/22",
            "208.68.108.0/22",
            "208.81.188.0/22",
            "208.117.224.0/19",
            "209.85.128.0/17",
            "216.58.192.0/19",
            "216.239.32.0/19",
            // IPv6 super-blocks
            "2001:4860::/32",
            "2404:6800::/32",
            "2607:f8b0::/32",
            "2620:11a:a000::/40",
            "2620:120:e000::/40",
            "2800:3f0::/32",
            "2a00:1450::/32",
            "2c0f:fb50::/32",
        )
    }
}
