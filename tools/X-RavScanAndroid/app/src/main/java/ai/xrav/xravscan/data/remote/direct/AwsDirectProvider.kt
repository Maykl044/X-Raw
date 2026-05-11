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
 * Amazon publishes one big JSON with every IP range in
 * `ip-ranges.amazonaws.com/ip-ranges.json`. Each entry carries
 * `service` (EC2, S3, CLOUDFRONT, AMAZON, …) so a single fetch
 * can drive two separate seeded providers (`aws` and `cloudfront`)
 * via [forService].
 */
@Singleton
class AwsDirectProvider @Inject constructor(
    private val updateClients: UpdateClientFactory,
    private val json: Json,
) {
    @Serializable
    private data class AwsResponse(
        val syncToken: String? = null,
        val createDate: String? = null,
        val prefixes: List<AwsPrefix> = emptyList(),
        @SerialName("ipv6_prefixes") val ipv6Prefixes: List<AwsIpv6Prefix> = emptyList(),
    )

    @Serializable
    private data class AwsPrefix(
        @SerialName("ip_prefix") val ipPrefix: String,
        val region: String = "",
        val service: String = "",
        @SerialName("network_border_group") val networkBorderGroup: String? = null,
    )

    @Serializable
    private data class AwsIpv6Prefix(
        @SerialName("ipv6_prefix") val ipv6Prefix: String,
        val region: String = "",
        val service: String = "",
        @SerialName("network_border_group") val networkBorderGroup: String? = null,
    )

    /**
     * Adapter that exposes one [DirectRangeProvider] per AWS service.
     * Pass `service = "AMAZON"` for the general `aws` provider and
     * `service = "CLOUDFRONT"` for the dedicated CloudFront slug.
     */
    fun forService(slug: String, service: String): DirectRangeProvider =
        object : DirectRangeProvider {
            override val providerSlug: String = slug
            override suspend fun fetch(): DirectRangeProvider.Result =
                this@AwsDirectProvider.fetchService(slug, service)
        }

    private suspend fun fetchService(slug: String, service: String): DirectRangeProvider.Result {
        val client = updateClients.dohClient
        return runCatching {
            val response = client.newCall(Request.Builder().url(URL).build()).execute()
            response.use {
                if (!it.isSuccessful) error("HTTP ${it.code}")
                val body = it.body?.string().orEmpty()
                val parsed = json.decodeFromString(AwsResponse.serializer(), body)
                val v4 = parsed.prefixes
                    .filter { p -> p.service.equals(service, ignoreCase = true) }
                    .map { p -> p.ipPrefix }
                val v6 = parsed.ipv6Prefixes
                    .filter { p -> p.service.equals(service, ignoreCase = true) }
                    .map { p -> p.ipv6Prefix }
                val merged = (v4 + v6).distinct()
                if (merged.isEmpty()) error("no '$service' prefixes in AWS feed")
                Log.i(TAG, "AWS $slug ($service): ${merged.size} CIDR(s)")
                DirectRangeProvider.Result.ok(merged)
            }
        }.getOrElse { t ->
            Log.w(TAG, "AWS direct ($service) failed: ${t.message}")
            DirectRangeProvider.Result.fallback(
                cidrs = emptyList(),
                reason = t.message ?: t::class.simpleName ?: "unknown error",
            )
        }
    }

    companion object {
        private const val TAG = "XRavScan.AWS"
        private const val URL = "https://ip-ranges.amazonaws.com/ip-ranges.json"
    }
}
