package ai.xrav.xravscan.data.remote.api

import ai.xrav.xravscan.data.remote.dto.BgpViewPrefixesResponse
import retrofit2.http.GET
import retrofit2.http.Path

interface BgpViewService {
    @GET("asn/{asn}/prefixes")
    suspend fun prefixes(@Path("asn") asn: Long): BgpViewPrefixesResponse
}
