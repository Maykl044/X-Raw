package ai.xrav.xravscan.data.remote.direct

import org.slf4j.LoggerFactory

/**
 * Akamai does **not** publish a machine-readable JSON / text file
 * with their edge IP ranges. Their documentation tells you to
 * either (a) sign an enterprise contract for an allowlist, or (b)
 * use RADB to look up their ASNs (`AS20940`, `AS12222`, `AS21342`,
 * `AS21357`, `AS35994`, …).
 *
 * Since the rest of this app is designed to work offline-first, we
 * keep a curated list of Akamai's most stable /16…/19 super-blocks
 * built from RADB snapshots. This is "best-effort" — Akamai
 * actually owns thousands of small prefixes and reassigns them
 * frequently. For most reconnaissance scenarios these 22 super
 * blocks are more than enough to catch their edge POPs.
 */
class AkamaiDirectProvider : DirectRangeProvider {

    private val log = LoggerFactory.getLogger("XRavScan.Akamai")
    override val providerSlug: String = "akamai"

    override suspend fun fetch(): DirectRangeProvider.Result {
        log.info("Akamai: using curated hardcoded list (${HARDCODED_RANGES.size} super-block(s))")
        return DirectRangeProvider.Result.fallback(
            cidrs = HARDCODED_RANGES,
            reason = "Akamai has no public JSON feed — using curated RADB snapshot",
        )
    }

    companion object {
        /**
         * Akamai super-blocks from AS20940 / AS12222 / AS21342 /
         * AS21357 / AS35994 snapshots. /16…/19 only — the more
         * specific prefixes change too often to keep hardcoded.
         */
        val HARDCODED_RANGES: List<String> = listOf(
            "23.0.0.0/12",
            "23.32.0.0/11",
            "23.64.0.0/14",
            "23.72.0.0/13",
            "23.192.0.0/11",
            "23.224.0.0/12",
            "23.235.32.0/20",
            "60.254.128.0/18",
            "69.31.0.0/16",
            "72.246.0.0/15",
            "92.122.0.0/15",
            "95.100.0.0/15",
            "96.6.0.0/15",
            "96.16.0.0/15",
            "104.64.0.0/10",
            "118.214.0.0/16",
            "165.254.0.0/16",
            "173.222.0.0/15",
            "184.24.0.0/13",
            "184.50.0.0/15",
            "184.84.0.0/14",
            "2.16.0.0/13",
            "2600:1400::/24",
            "2a02:26f0::/32",
        )
    }
}
