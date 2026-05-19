package ai.xrav.xravscan.data.remote.direct

import ai.xrav.xravscan.data.remote.BunnyRangeProvider

/**
 * Adapter that exposes the existing [BunnyRangeProvider] under the
 * unified [DirectRangeProvider] interface so the Smart Append
 * dispatcher can treat Bunny exactly like Cloudflare/AWS/GCP/etc.
 */
class BunnyDirectProvider(
    private val backing: BunnyRangeProvider = BunnyRangeProvider(),
) : DirectRangeProvider {

    override val providerSlug: String = "bunny"

    override suspend fun fetch(): DirectRangeProvider.Result {
        val res = backing.fetchAll()
        return when (res.source) {
            BunnyRangeProvider.Source.DIRECT_API ->
                DirectRangeProvider.Result.ok(res.cidrs)
            BunnyRangeProvider.Source.HARDCODED_FALLBACK ->
                DirectRangeProvider.Result.fallback(
                    cidrs = res.cidrs,
                    reason = res.fallbackReason ?: "unknown error",
                )
            BunnyRangeProvider.Source.MIXED ->
                DirectRangeProvider.Result(
                    cidrs = res.cidrs,
                    source = DirectRangeProvider.Source.MIXED,
                    errorMessage = res.fallbackReason,
                )
        }
    }
}
