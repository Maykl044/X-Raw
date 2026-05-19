package ai.xrav.xravscan.data.remote.api

import retrofit2.http.GET

/**
 * Direct edge-server list published by bunny.net. Returns a flat
 * JSON array of `"a.b.c.d"` strings — every edge IP currently in
 * rotation. Used as the primary source for the Bunny CDN provider
 * because BGPView returns an essentially empty prefix list for the
 * Bunny ASN (their edges live on Datacamp parent networks).
 *
 * Mirrors:
 *  - https://api.bunny.net/system/edgeserverlist        (preferred)
 *  - https://bunnycdn.com/api/system/edgeserverlist     (alias)
 */
interface BunnyEdgeApi {
    @GET("system/edgeserverlist")
    suspend fun edgeServers(): List<String>
}
