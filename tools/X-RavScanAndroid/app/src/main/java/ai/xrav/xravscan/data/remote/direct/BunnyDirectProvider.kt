package ai.xrav.xravscan.data.remote.direct

import ai.xrav.xravscan.data.remote.BunnyRangeProvider
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Thin [DirectRangeProvider] adapter over the existing
 * [BunnyRangeProvider] so the unified `runSmartAppend` dispatcher
 * can treat Bunny the same as Cloudflare / GCP / AWS / Fastly /
 * Akamai. We keep the Phase G [BunnyRangeProvider] intact for the
 * other code paths that already depend on its richer [Result] shape
 * (`edgeIpCount` etc.) — this adapter just maps it onto the
 * generic [DirectRangeProvider.Result].
 */
@Singleton
class BunnyDirectProvider @Inject constructor(
    private val bunny: BunnyRangeProvider,
) : DirectRangeProvider {

    override val providerSlug: String = "bunny"

    override suspend fun fetch(): DirectRangeProvider.Result {
        val r = bunny.fetchAll()
        val source = when (r.source) {
            BunnyRangeProvider.Result.Source.DIRECT_API -> DirectRangeProvider.Source.DIRECT_API
            BunnyRangeProvider.Result.Source.MIXED -> DirectRangeProvider.Source.MIXED
            BunnyRangeProvider.Result.Source.HARDCODED_FALLBACK ->
                DirectRangeProvider.Source.HARDCODED_FALLBACK
        }
        return DirectRangeProvider.Result(
            cidrs = r.cidrs,
            source = source,
            errorMessage = r.fallbackReason,
        )
    }
}
