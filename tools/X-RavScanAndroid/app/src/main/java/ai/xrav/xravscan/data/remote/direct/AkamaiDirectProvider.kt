package ai.xrav.xravscan.data.remote.direct

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Akamai does not publish a stable, machine-readable IP-range file —
 * their published "Origin IP Access Control" lists in techdocs are
 * HTML tables that frequently change layout, and the official
 * recommendation is "resolve `*.akamaiedge.net` via DNS at runtime",
 * which doesn't fit our pre-fetch model.
 *
 * To keep parity with the other Direct API providers we ship a
 * conservative hardcoded list of well-known Akamai super-blocks from
 * RADB / `whois -h whois.radb.net AS20940` + AS12222 (Akamai
 * Technologies / Akamai International). Verified active 2025-10.
 */
@Singleton
class AkamaiDirectProvider @Inject constructor() : DirectRangeProvider {

    override val providerSlug: String = "akamai"

    override suspend fun fetch(): DirectRangeProvider.Result =
        DirectRangeProvider.Result.fallback(
            cidrs = HARDCODED_RANGES,
            reason = "Akamai has no public IP-range JSON — hardcoded RADB snapshot",
        )

    companion object {
        /**
         * AS20940 (Akamai International B.V.) + AS12222 (Akamai
         * Technologies, Inc.) super-blocks. Trimmed to the largest
         * stable ranges to keep Discovery legible.
         */
        val HARDCODED_RANGES: List<String> = listOf(
            "23.0.0.0/12",
            "23.32.0.0/11",
            "23.64.0.0/14",
            "23.72.0.0/13",
            "23.192.0.0/11",
            "23.224.0.0/12",
            "63.208.0.0/12",
            "72.246.0.0/15",
            "92.122.0.0/16",
            "95.100.0.0/15",
            "96.6.0.0/15",
            "96.16.0.0/15",
            "104.64.0.0/10",
            "118.214.0.0/16",
            "173.222.0.0/15",
            "184.24.0.0/13",
            "184.84.0.0/14",
            "184.150.0.0/16",
            "2.16.0.0/13",
            "2600:1400::/24",
            "2600:1480::/24",
            "2a02:26f0::/29",
        )
    }
}
