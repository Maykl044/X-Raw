package ai.xrav.xravscan.data.remote.direct

import ai.xrav.xravscan.data.remote.UpdateClientFactory
import android.util.Log
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Cloudflare publishes a tiny plain-text list of every CIDR they
 * announce at two endpoints:
 *
 *   https://www.cloudflare.com/ips-v4   (≈15 lines of IPv4 CIDR)
 *   https://www.cloudflare.com/ips-v6   (≈7 lines of IPv6 CIDR)
 *
 * One CIDR per line, no envelope. Both endpoints are served with a
 * very long Cache-Control so it's safe to poll on every Smart Append.
 */
@Singleton
class CloudflareDirectProvider @Inject constructor(
    private val updateClients: UpdateClientFactory,
) : DirectRangeProvider {

    override val providerSlug: String = "cloudflare"

    override suspend fun fetch(): DirectRangeProvider.Result {
        val client = updateClients.dohClient
        val collected = mutableListOf<String>()
        var error: String? = null
        for (url in listOf(IPV4_URL, IPV6_URL)) {
            runCatching {
                val response = client.newCall(Request.Builder().url(url).build()).execute()
                response.use {
                    if (!it.isSuccessful) error("HTTP ${it.code}")
                    val body = it.body?.string().orEmpty()
                    collected += body.lineSequence()
                        .map { line -> line.trim() }
                        .filter { line -> line.isNotEmpty() && !line.startsWith('#') }
                        .toList()
                }
            }.onFailure { t ->
                Log.w(TAG, "Cloudflare fetch failed for $url: ${t.message}")
                error = error ?: (t.message ?: t::class.simpleName ?: "unknown error")
            }
        }
        return if (collected.isEmpty()) {
            DirectRangeProvider.Result.fallback(
                cidrs = HARDCODED_RANGES,
                reason = error ?: "Cloudflare API returned empty",
            )
        } else {
            DirectRangeProvider.Result.ok(collected.distinct())
        }
    }

    companion object {
        private const val TAG = "XRavScan.Cloudflare"
        private const val IPV4_URL = "https://www.cloudflare.com/ips-v4"
        private const val IPV6_URL = "https://www.cloudflare.com/ips-v6"

        /**
         * Cloudflare's published list as of 2025-10. Reasonably stable;
         * the upstream file has barely changed in 5 years.
         */
        val HARDCODED_RANGES: List<String> = listOf(
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
