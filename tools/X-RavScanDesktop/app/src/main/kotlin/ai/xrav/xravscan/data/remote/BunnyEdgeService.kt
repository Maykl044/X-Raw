package ai.xrav.xravscan.data.remote

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import org.slf4j.LoggerFactory

/**
 * Talks to `https://api.bunny.net/system/edgeserverlist`, the public
 * JSON array of every edge IP currently in rotation. The response is a
 * flat `["a.b.c.d", "e.f.g.h", …]`, no envelope, so we deserialise
 * straight into `List<String>`.
 *
 * Used as the primary source for the Bunny CDN provider because
 * BGPView returns essentially nothing for the Bunny ASNs (their
 * edges live on Datacamp parent networks).
 */
class BunnyEdgeService(
    private val client: HttpClient = UpdateHttpClient.ktor(),
    private val url: String = "https://api.bunny.net/system/edgeserverlist",
) {
    private val log = LoggerFactory.getLogger("XRavScan.BunnyEdgeService")

    suspend fun edgeServers(): List<String> {
        val ips: List<String> = client.get(url).body()
        log.info("Bunny edge list: {} IP(s)", ips.size)
        return ips
    }

    fun close() = client.close()
}
