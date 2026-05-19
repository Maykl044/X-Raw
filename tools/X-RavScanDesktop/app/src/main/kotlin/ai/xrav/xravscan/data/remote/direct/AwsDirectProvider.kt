package ai.xrav.xravscan.data.remote.direct

import ai.xrav.xravscan.data.remote.UpdateHttpClient
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import org.slf4j.LoggerFactory

/**
 * AWS publishes `ip-ranges.amazonaws.com/ip-ranges.json` — a single
 * JSON blob with every IP range partitioned by `service` field
 * (AMAZON, EC2, S3, CLOUDFRONT, …). We expose **two** logical
 * providers from one fetch: slug `aws` (filter `AMAZON`, the
 * catch-all superset) and slug `cloudfront` (filter `CLOUDFRONT`).
 */
class AwsDirectProvider(
    private val client: OkHttpClient = UpdateHttpClient.dohOkHttp,
    private val json: Json = DEFAULT_JSON,
) {

    private val log = LoggerFactory.getLogger("XRavScan.AWS")

    @Serializable
    private data class AwsResponse(
        val syncToken: String? = null,
        val createDate: String? = null,
        val prefixes: List<AwsPrefix> = emptyList(),
        val ipv6_prefixes: List<AwsPrefix6> = emptyList(),
    )

    @Serializable
    private data class AwsPrefix(
        @SerialName("ip_prefix") val ipPrefix: String,
        val service: String = "",
        val region: String = "",
        val network_border_group: String = "",
    )

    @Serializable
    private data class AwsPrefix6(
        @SerialName("ipv6_prefix") val ipv6Prefix: String,
        val service: String = "",
        val region: String = "",
        val network_border_group: String = "",
    )

    private suspend fun fetchService(slug: String, service: String): DirectRangeProvider.Result =
        runCatching {
            client.newCall(Request.Builder().url(URL).build()).execute().use { resp ->
                if (!resp.isSuccessful) error("HTTP ${resp.code}")
                val body = resp.body?.string().orEmpty()
                val parsed = json.decodeFromString(AwsResponse.serializer(), body)
                val cidrs = buildList {
                    addAll(parsed.prefixes.filter { it.service.equals(service, true) }.map { it.ipPrefix })
                    addAll(parsed.ipv6_prefixes.filter { it.service.equals(service, true) }.map { it.ipv6Prefix })
                }.distinct()
                if (cidrs.isEmpty()) error("no $service prefixes")
                log.info("AWS service=$service: ${cidrs.size} CIDR(s)")
                DirectRangeProvider.Result.ok(cidrs)
            }
        }.getOrElse { t ->
            log.warn("AWS service=$service fetch failed: ${t.message}")
            val fb = if (slug == "cloudfront") HARDCODED_CLOUDFRONT else HARDCODED_AMAZON
            DirectRangeProvider.Result.fallback(
                cidrs = fb,
                reason = t.message ?: t::class.simpleName ?: "unknown error",
            )
        }

    /** Build an adapter [DirectRangeProvider] for one of AWS's logical sub-services. */
    fun forService(slug: String, service: String): DirectRangeProvider =
        object : DirectRangeProvider {
            override val providerSlug: String = slug
            override suspend fun fetch(): DirectRangeProvider.Result =
                this@AwsDirectProvider.fetchService(slug, service)
        }

    companion object {
        const val URL = "https://ip-ranges.amazonaws.com/ip-ranges.json"
        private val DEFAULT_JSON = Json {
            ignoreUnknownKeys = true
            isLenient = true
            explicitNulls = false
        }

        /** AMAZON super-set fallback (a tiny but representative slice). */
        val HARDCODED_AMAZON: List<String> = listOf(
            "3.2.0.0/24", "3.5.0.0/19", "3.5.32.0/21",
            "13.32.0.0/15", "13.34.0.0/15", "13.36.0.0/14",
            "13.112.0.0/14", "13.124.0.0/16", "13.208.0.0/14",
            "13.224.0.0/14", "15.177.0.0/18", "15.181.0.0/16",
            "15.184.0.0/15", "15.188.0.0/16", "15.193.0.0/16",
            "16.12.0.0/15", "16.50.0.0/15", "18.32.0.0/11",
            "18.116.0.0/14", "18.130.0.0/16", "18.144.0.0/15",
            "18.160.0.0/15", "18.180.0.0/14", "18.200.0.0/16",
            "18.204.0.0/14", "18.208.0.0/13", "18.228.0.0/14",
            "18.232.0.0/14", "18.236.0.0/15", "18.244.0.0/15",
            "18.246.0.0/16", "18.248.0.0/14", "34.192.0.0/12",
            "34.208.0.0/12", "34.224.0.0/12", "34.240.0.0/13",
            "34.248.0.0/13", "35.71.64.0/18", "35.71.128.0/17",
            "35.72.0.0/13", "35.80.0.0/12", "35.96.0.0/12",
            "35.152.0.0/13", "35.160.0.0/13", "35.168.0.0/14",
            "35.172.0.0/15", "35.174.0.0/16", "35.175.0.0/17",
            "35.176.0.0/15", "35.178.0.0/16", "35.180.0.0/14",
            "44.192.0.0/11", "44.224.0.0/12", "44.240.0.0/14",
            "44.244.0.0/14", "50.16.0.0/14", "50.112.0.0/16",
            "52.0.0.0/15", "52.4.0.0/14", "52.8.0.0/13",
            "52.16.0.0/13", "52.24.0.0/13", "52.32.0.0/13",
            "52.40.0.0/13", "52.48.0.0/12", "52.64.0.0/12",
            "52.80.0.0/12", "52.144.192.0/18", "52.192.0.0/11",
            "52.208.0.0/13", "52.216.0.0/15", "52.218.0.0/16",
            "52.219.0.0/16", "52.220.0.0/15",
            "54.64.0.0/13", "54.72.0.0/13", "54.80.0.0/12",
            "54.144.0.0/12", "54.160.0.0/12", "54.176.0.0/12",
            "54.192.0.0/12", "54.208.0.0/13", "54.216.0.0/14",
            "54.220.0.0/14", "54.224.0.0/12", "54.240.0.0/12",
        )

        /** CloudFront edge fallback. */
        val HARDCODED_CLOUDFRONT: List<String> = listOf(
            "3.5.140.0/22", "13.32.0.0/15", "13.35.0.0/16",
            "13.113.196.0/22", "13.113.203.0/24", "13.124.199.0/24",
            "13.210.67.128/26", "13.224.0.0/14", "13.228.69.0/24",
            "13.249.0.0/16", "15.158.0.0/16", "18.67.0.0/16",
            "18.155.0.0/16", "18.160.0.0/15", "18.164.0.0/15",
            "18.172.0.0/15", "18.238.0.0/15", "18.244.0.0/15",
            "18.248.0.0/14", "52.46.0.0/18", "52.56.127.0/25",
            "52.57.254.0/24", "52.66.194.128/26", "52.78.247.128/26",
            "52.82.128.0/19", "52.84.0.0/15", "52.124.128.0/17",
            "52.142.0.0/16", "52.157.0.0/16", "52.158.0.0/15",
            "52.220.191.0/26", "52.222.128.0/17", "54.182.0.0/16",
            "54.192.0.0/16", "54.230.0.0/16", "54.239.128.0/18",
            "54.239.192.0/19", "54.240.128.0/18", "64.252.64.0/18",
            "65.8.0.0/16", "65.9.0.0/17", "65.9.128.0/18",
            "70.132.0.0/18", "71.152.0.0/17", "99.84.0.0/16",
            "99.86.0.0/16", "108.138.0.0/15", "108.156.0.0/14",
            "108.158.0.0/16", "108.166.244.0/22", "108.175.48.0/20",
            "130.176.0.0/16", "143.204.0.0/16", "144.220.0.0/16",
            "204.246.164.0/22", "204.246.168.0/22", "204.246.172.0/24",
            "204.246.174.0/23", "205.251.192.0/19",
            "205.251.249.0/24", "205.251.250.0/23", "205.251.252.0/23",
            "205.251.254.0/24", "216.137.32.0/19",
        )
    }
}
