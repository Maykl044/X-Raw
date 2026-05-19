package ai.xrav.xravscan.data.remote.direct

import ai.xrav.xravscan.data.remote.UpdateHttpClient
import okhttp3.OkHttpClient
import okhttp3.Request
import org.slf4j.LoggerFactory

/**
 * Cloudflare publishes two flat newline-separated lists of CIDRs
 * at `cloudflare.com/ips-v4` and `cloudflare.com/ips-v6`.
 */
class CloudflareDirectProvider(
    private val client: OkHttpClient = UpdateHttpClient.dohOkHttp,
) : DirectRangeProvider {

    private val log = LoggerFactory.getLogger("XRavScan.Cloudflare")
    override val providerSlug: String = "cloudflare"

    override suspend fun fetch(): DirectRangeProvider.Result {
        val collected = mutableListOf<String>()
        var error: String? = null
        for (url in listOf(IPV4_URL, IPV6_URL)) {
            runCatching {
                client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                    if (!resp.isSuccessful) error("HTTP ${resp.code}")
                    val body = resp.body?.string().orEmpty()
                    collected += body.lineSequence()
                        .map { it.trim() }
                        .filter { it.isNotEmpty() && !it.startsWith('#') }
                        .toList()
                }
            }.onFailure { t ->
                log.warn("Cloudflare fetch failed for $url: ${t.message}")
                error = error ?: (t.message ?: t::class.simpleName ?: "unknown error")
            }
        }
        return if (collected.isEmpty()) {
            DirectRangeProvider.Result.fallback(HARDCODED_RANGES, error ?: "empty")
        } else {
            DirectRangeProvider.Result.ok(collected.distinct())
        }
    }

    companion object {
        const val IPV4_URL = "https://www.cloudflare.com/ips-v4"
        const val IPV6_URL = "https://www.cloudflare.com/ips-v6"

        /** Cloudflare's published v4 + v6 super-blocks as of 2025-10. */
        val HARDCODED_RANGES: List<String> = listOf(
            // IPv4
            "173.245.48.0/20",
            "103.21.244.0/22",
            "103.22.200.0/22",
            "103.31.4.0/22",
            "141.101.64.0/18",
            "108.162.192.0/18",
            "190.93.240.0/20",
            "188.114.96.0/20",
            "197.234.240.0/22",
            "198.41.128.0/17",
            "162.158.0.0/15",
            "104.16.0.0/13",
            "104.24.0.0/14",
            "172.64.0.0/13",
            "131.0.72.0/22",
            // IPv6
            "2400:cb00::/32",
            "2606:4700::/32",
            "2803:f800::/32",
            "2405:b500::/32",
            "2405:8100::/32",
            "2a06:98c0::/29",
            "2c0f:f248::/32",
        )
    }
}
