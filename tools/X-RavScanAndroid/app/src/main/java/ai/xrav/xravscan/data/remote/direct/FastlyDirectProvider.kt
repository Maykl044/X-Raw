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
 * Fastly exposes the public edge IP list at
 * `https://api.fastly.com/public-ip-list`. No auth required. Shape:
 * `{ "addresses": ["…/24", …], "ipv6_addresses": ["…/32", …] }`.
 */
@Singleton
class FastlyDirectProvider @Inject constructor(
    private val updateClients: UpdateClientFactory,
    private val json: Json,
) : DirectRangeProvider {

    override val providerSlug: String = "fastly"

    @Serializable
    private data class FastlyResponse(
        val addresses: List<String> = emptyList(),
        @SerialName("ipv6_addresses") val ipv6Addresses: List<String> = emptyList(),
    )

    override suspend fun fetch(): DirectRangeProvider.Result {
        val client = updateClients.dohClient
        return runCatching {
            val response = client.newCall(Request.Builder().url(URL).build()).execute()
            response.use {
                if (!it.isSuccessful) error("HTTP ${it.code}")
                val body = it.body?.string().orEmpty()
                val parsed = json.decodeFromString(FastlyResponse.serializer(), body)
                val cidrs = (parsed.addresses + parsed.ipv6Addresses).distinct()
                if (cidrs.isEmpty()) error("Fastly public-ip-list returned empty")
                Log.i(TAG, "Fastly: ${cidrs.size} CIDR(s)")
                DirectRangeProvider.Result.ok(cidrs)
            }
        }.getOrElse { t ->
            Log.w(TAG, "Fastly direct API failed: ${t.message}")
            DirectRangeProvider.Result.fallback(
                cidrs = HARDCODED_RANGES,
                reason = t.message ?: t::class.simpleName ?: "unknown error",
            )
        }
    }

    companion object {
        private const val TAG = "XRavScan.Fastly"
        private const val URL = "https://api.fastly.com/public-ip-list"

        /** Stable Fastly super-blocks as of 2025-10. */
        val HARDCODED_RANGES: List<String> = listOf(
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
            "2a04:4e40::/32",
            "2a04:4e42::/32",
        )
    }
}
