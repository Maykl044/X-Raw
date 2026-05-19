package ai.xrav.xravscan.data.remote.dto

import kotlinx.serialization.Serializable

/**
 * Response from `GET https://api.bgpview.io/asn/{asn}/prefixes`.
 *
 * Only the fields we need are mapped — Retrofit + kotlinx-serialization
 * with `ignoreUnknownKeys = true` will drop everything else.
 */
@Serializable
data class BgpViewPrefixesResponse(
    val status: String = "",
    val data: BgpViewPrefixesData = BgpViewPrefixesData(),
)

@Serializable
data class BgpViewPrefixesData(
    val ipv4_prefixes: List<BgpViewPrefix> = emptyList(),
    val ipv6_prefixes: List<BgpViewPrefix> = emptyList(),
)

@Serializable
data class BgpViewPrefix(
    val prefix: String = "",
    val name: String? = null,
    val description: String? = null,
    val country_code: String? = null,
)
