package ai.xrav.xravscan.data.remote

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.UserAgent
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Subset of the BGPView response for `GET /asn/{asn}/prefixes`.
 *
 * Only the fields we actually consume are mapped; everything else is
 * dropped via [Json.ignoreUnknownKeys].
 */
@Serializable
data class BgpViewPrefixesResponse(
    val status: String = "",
    val data: BgpViewPrefixesData = BgpViewPrefixesData(),
)

@Serializable
data class BgpViewPrefixesData(
    @SerialName("ipv4_prefixes") val ipv4Prefixes: List<BgpViewPrefix> = emptyList(),
    @SerialName("ipv6_prefixes") val ipv6Prefixes: List<BgpViewPrefix> = emptyList(),
)

@Serializable
data class BgpViewPrefix(
    val prefix: String = "",
    val name: String? = null,
    val description: String? = null,
    @SerialName("country_code") val countryCode: String? = null,
)

/**
 * Tiny client wrapper around BGPView. Owns its own [HttpClient]; callers
 * should keep one [BgpViewService] instance for the lifetime of the app
 * (it is held by [ai.xrav.xravscan.AppContainer]).
 */
class BgpViewService(
    private val client: HttpClient = defaultClient(),
    private val baseUrl: String = "https://api.bgpview.io",
) {
    suspend fun prefixes(asn: Long): BgpViewPrefixesResponse =
        client.get("$baseUrl/asn/$asn/prefixes").body()

    fun close() = client.close()

    companion object {
        fun defaultClient(): HttpClient = HttpClient(OkHttp) {
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
}
