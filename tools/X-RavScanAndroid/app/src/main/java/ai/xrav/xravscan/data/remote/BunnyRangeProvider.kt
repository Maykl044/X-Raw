package ai.xrav.xravscan.data.remote

import ai.xrav.xravscan.data.remote.api.BunnyEdgeApi
import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single source of CIDR ranges for the Bunny CDN provider.
 *
 *  1. Tries the direct edge-server JSON ([BunnyEdgeApi]).
 *  2. Groups returned individual IPv4s into `/24` super-nets, so the
 *     Full Provider Scan walks 256 hosts per edge subnet — catching
 *     siblings the public list does not enumerate.
 *  3. Falls back to the [HARDCODED_RANGES] table (AS200325 + AS213035
 *     RADB announcements + historical Datacamp super-blocks) if the API
 *     is unreachable, returns empty, or fails any other way.
 *
 * The fallback is exposed separately via [hardcodedOnly] so the
 * caller can preempt the API call when offline and still surface a
 * meaningful number of CIDRs.
 */
@Singleton
class BunnyRangeProvider @Inject constructor(
    private val api: BunnyEdgeApi,
) {
    data class Result(
        val cidrs: List<String>,
        val source: Source,
        val edgeIpCount: Int,
        val fallbackReason: String? = null,
    ) {
        enum class Source { DIRECT_API, HARDCODED_FALLBACK, MIXED }
    }

    /**
     * Direct API → /24 grouping → fallback. Never returns an empty list:
     * if every step fails the result still carries [HARDCODED_RANGES].
     */
    suspend fun fetchAll(): Result {
        return runCatching {
            val ips = api.edgeServers()
            if (ips.isEmpty()) error("Bunny API returned empty list")
            val grouped = groupToSubnets(ips)
            Log.i(TAG, "Bunny direct API: ${ips.size} IPs → ${grouped.size} CIDR(s)")
            Result(
                cidrs = grouped,
                source = Result.Source.DIRECT_API,
                edgeIpCount = ips.size,
            )
        }.recoverCatching { t ->
            Log.w(TAG, "Bunny direct API failed — using hardcoded fallback", t)
            Result(
                cidrs = HARDCODED_RANGES,
                source = Result.Source.HARDCODED_FALLBACK,
                edgeIpCount = 0,
                fallbackReason = t.message ?: t::class.simpleName ?: "unknown error",
            )
        }.getOrThrow()
    }

    /**
     * Bunch the raw `/32` edge list into `/24` super-nets whenever
     * possible, and keep the original `/32` otherwise. This keeps the
     * discovery table compact (≤ 80 entries instead of 600+) while still
     * letting Full Provider Scan walk every host inside each /24.
     */
    private fun groupToSubnets(ips: List<String>): List<String> {
        val cleaned = ips.mapNotNull { raw ->
            raw.trim().takeIf { it.isNotEmpty() }
                ?.removeSuffix("/32")
                ?.takeIf { it.count { ch -> ch == '.' } == 3 }
        }
        val perSlash24 = cleaned.groupBy { ip -> ip.substringBeforeLast(".") }
        val result = mutableListOf<String>()
        for ((prefix, members) in perSlash24) {
            if (members.size >= 4) {
                result += "$prefix.0/24"
            } else {
                members.forEach { result += "$it/32" }
            }
        }
        return result.distinct()
    }

    /**
     * Curated list of routes announced by AS200325 (Bunny / DataCamp)
     * + AS213035 + historical Datacamp super-blocks. Updated
     * 2025-10 from RADB. Reachable even when the API and DNS are
     * unavailable.
     */
    companion object {
        private const val TAG = "XRavScan.Bunny"

        val HARDCODED_RANGES: List<String> = listOf(
            // AS200325 — bunny.net / DataCamp
            "38.92.173.0/24",
            "91.200.176.0/24",
            "103.180.114.0/23",
            "107.150.176.0/24",
            "109.104.146.0/23",
            "109.224.228.0/22",
            "157.53.226.0/24",
            "185.190.83.0/24",
            "193.162.131.0/24",
            "194.156.156.0/24",
            "212.104.158.0/24",
            // AS213035 — bunny.net
            "104.164.27.0/24",
            "104.164.28.0/24",
            "104.164.51.0/24",
            "104.164.52.0/24",
            "185.28.37.0/24",
            "185.126.34.0/24",
            "194.49.94.0/24",
            "194.87.132.0/24",
            "195.133.16.0/24",
            "209.182.101.0/24",
            "212.192.216.0/24",
            "212.192.218.0/24",
            "212.192.219.0/24",
            "212.192.240.0/24",
            "212.192.243.0/24",
            "212.193.29.0/24",
            "41.216.181.0/24",
            "87.121.69.0/24",
            // Historical Bunny edge super-blocks (DataCamp parent / known
            // long-running Bunny POPs). Verified active 2025-10.
            "84.17.32.0/19",
            "89.187.160.0/19",
            "138.199.0.0/16",
            "143.244.32.0/19",
            "156.59.64.0/19",
            "169.150.192.0/18",
            "185.93.0.0/22",
            "185.180.12.0/22",
            "195.181.160.0/19",
        )
    }
}
