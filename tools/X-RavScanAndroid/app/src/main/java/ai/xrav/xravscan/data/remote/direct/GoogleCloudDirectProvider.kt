package ai.xrav.xravscan.data.remote.direct

import ai.xrav.xravscan.data.remote.UpdateClientFactory
import android.util.Log
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Google Cloud publishes its IP ranges at
 * `https://www.gstatic.com/ipranges/cloud.json`. Every entry contains
 * either an `ipv4Prefix` or `ipv6Prefix`; we union both.
 *
 * There is also `goog.json` — the broader Google network — but `gcp`
 * in the seed file specifically maps to *Cloud* customer ranges, so we
 * use `cloud.json` here. Hardcoded fallback covers the most stable
 * super-blocks.
 */
@Singleton
class GoogleCloudDirectProvider @Inject constructor(
    private val updateClients: UpdateClientFactory,
    private val json: Json,
) : DirectRangeProvider {

    override val providerSlug: String = "gcp"

    @Serializable
    private data class GcpResponse(
        val syncToken: String? = null,
        val creationTime: String? = null,
        val prefixes: List<GcpPrefix> = emptyList(),
    )

    @Serializable
    private data class GcpPrefix(
        val ipv4Prefix: String? = null,
        val ipv6Prefix: String? = null,
        val service: String? = null,
        val scope: String? = null,
    )

    override suspend fun fetch(): DirectRangeProvider.Result {
        val client = updateClients.dohClient
        return runCatching {
            val response = client.newCall(Request.Builder().url(URL).build()).execute()
            response.use {
                if (!it.isSuccessful) error("HTTP ${it.code}")
                val body = it.body?.string().orEmpty()
                val parsed = json.decodeFromString(GcpResponse.serializer(), body)
                val cidrs = parsed.prefixes.mapNotNull { p -> p.ipv4Prefix ?: p.ipv6Prefix }
                    .distinct()
                if (cidrs.isEmpty()) error("GCP cloud.json returned empty")
                Log.i(TAG, "GCP: ${cidrs.size} CIDR(s)")
                DirectRangeProvider.Result.ok(cidrs)
            }
        }.getOrElse { t ->
            Log.w(TAG, "GCP direct API failed: ${t.message}", t)
            DirectRangeProvider.Result.fallback(
                cidrs = HARDCODED_RANGES,
                reason = t.message ?: t::class.simpleName ?: "unknown error",
            )
        }
    }

    companion object {
        private const val TAG = "XRavScan.GCP"
        private const val URL = "https://www.gstatic.com/ipranges/cloud.json"

        /** Stable Google Cloud /20+ super-blocks. */
        val HARDCODED_RANGES: List<String> = listOf(
            "34.0.0.0/15",
            "34.2.0.0/16",
            "34.3.0.0/23",
            "34.64.0.0/10",
            "34.128.0.0/10",
            "35.184.0.0/14",
            "35.188.0.0/15",
            "35.190.0.0/17",
            "35.192.0.0/14",
            "35.196.0.0/15",
            "35.198.0.0/16",
            "35.199.0.0/17",
            "35.199.128.0/18",
            "35.200.0.0/13",
            "35.208.0.0/12",
            "35.224.0.0/12",
            "35.240.0.0/13",
            "104.154.0.0/15",
            "104.196.0.0/14",
            "107.167.160.0/19",
            "107.178.192.0/18",
            "108.59.80.0/20",
            "108.170.192.0/20",
            "108.170.208.0/21",
            "108.170.216.0/22",
            "108.170.220.0/23",
            "108.170.222.0/24",
            "130.211.0.0/22",
            "130.211.4.0/22",
            "130.211.8.0/21",
            "130.211.16.0/20",
            "130.211.32.0/19",
            "130.211.64.0/18",
            "130.211.128.0/17",
            "146.148.0.0/17",
            "146.148.128.0/17",
            "162.216.148.0/22",
            "162.222.176.0/21",
            "173.255.112.0/20",
            "192.158.28.0/22",
            "199.192.112.0/22",
            "199.223.232.0/21",
            "199.223.240.0/20",
            "208.68.108.0/23",
            "208.81.188.0/22",
            "209.85.128.0/17",
            "216.239.32.0/19",
        )
    }
}
