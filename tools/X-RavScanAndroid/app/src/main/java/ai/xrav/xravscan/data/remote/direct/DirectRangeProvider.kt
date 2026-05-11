package ai.xrav.xravscan.data.remote.direct

/**
 * One vendor's official IP-range feed. Each concrete provider knows
 * (a) which provider slug it is responsible for and (b) how to turn
 * that vendor's specific JSON / plain-text shape into a list of CIDR
 * strings. Returning `Result` instead of a raw list keeps source
 * provenance (DIRECT_API vs HARDCODED_FALLBACK) so the activity log
 * can be honest about where the numbers came from.
 */
interface DirectRangeProvider {
    val providerSlug: String

    suspend fun fetch(): Result

    enum class Source { DIRECT_API, HARDCODED_FALLBACK, MIXED }

    data class Result(
        val cidrs: List<String>,
        val source: Source,
        val errorMessage: String? = null,
    ) {
        companion object {
            fun ok(cidrs: List<String>) = Result(cidrs, Source.DIRECT_API, null)
            fun fallback(cidrs: List<String>, reason: String) =
                Result(cidrs, Source.HARDCODED_FALLBACK, reason)
        }
    }
}
