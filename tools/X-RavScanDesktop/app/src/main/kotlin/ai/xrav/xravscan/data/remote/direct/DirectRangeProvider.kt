package ai.xrav.xravscan.data.remote.direct

/**
 * Unified abstraction for any vendor that publishes its IP-range
 * list directly (Cloudflare ips-v4, Bunny edgeserverlist, AWS
 * ip-ranges.json, Google cloud.json, Fastly public-ip-list…). The
 * `Smart Append` dispatcher in [DiscoveryRepository] calls
 * [fetch] in parallel for every enabled provider that has a
 * registered direct feed.
 *
 * Implementations should always return something — even an empty
 * list with [Source.HARDCODED_FALLBACK] is preferable to throwing —
 * so the UI never has to special-case "API died, no data". When
 * the upstream feed is unreachable, every concrete provider in
 * this package falls back to a curated, hand-maintained CIDR list.
 */
interface DirectRangeProvider {

    /** Provider slug ("cloudflare", "aws", "gcp", "fastly", …). */
    val providerSlug: String

    /** Single round-trip vendor fetch + parse + curate. */
    suspend fun fetch(): Result

    /** Where did the cidrs come from. Used for log-line phrasing. */
    enum class Source { DIRECT_API, HARDCODED_FALLBACK, MIXED }

    /**
     * @param cidrs        every CIDR the provider returned (may contain
     *                     duplicates — [DedupPipeline] will deal with them)
     * @param source       see [Source]
     * @param errorMessage non-null when [Source.HARDCODED_FALLBACK] was
     *                     picked because the upstream feed failed
     */
    data class Result(
        val cidrs: List<String>,
        val source: Source,
        val errorMessage: String? = null,
    ) {
        companion object {
            fun ok(cidrs: List<String>): Result =
                Result(cidrs, Source.DIRECT_API, null)
            fun fallback(cidrs: List<String>, reason: String): Result =
                Result(cidrs, Source.HARDCODED_FALLBACK, reason)
        }
    }
}
