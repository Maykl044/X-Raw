package ai.xrav.xravscan.domain.util

/**
 * O(1)-memory streaming IPv4 iterator for ultra-large CIDR walks
 * (Cloudflare ≈ 6 500 000 IPv4, etc.).
 *
 * Unlike `Cidr.expandIpv4()`'s `Sequence<String>`, this class keeps the
 * walk explicit — no coroutine machinery, no continuations, no GC
 * pressure beyond the single host-string allocation in [next] — and
 * lets the caller observe / restore the walk cursor `(cidrIndex,
 * ipOffset)` so a Full Provider Scan can be Paused and Resumed without
 * re-walking the prefixes already covered.
 *
 * State invariants:
 *  * [cidrIndex] points at the CIDR we are currently inside (or one
 *    past the last CIDR once the walk is finished).
 *  * [ipOffset] is the **next** host index inside the current CIDR
 *    that [next] will yield. `0` means "start of the host range".
 *  * Total memory: O(N_cidrs) — only the list of raw strings is kept.
 *    No matter whether the iterator walks 50 000 or 50 000 000 IPs the
 *    heap footprint is identical (a few KB).
 *
 * Concurrency: NOT thread-safe by design. The producer side of the
 * Full Provider Scan owns the iterator and feeds a bounded channel;
 * workers never touch it directly.
 */
class IpStreamIterator private constructor(
    /** All CIDR strings the walk should cover, in scan order. */
    val cidrs: List<String>,
    initialCidrIndex: Int,
    initialIpOffset: Long,
) {

    /** 0-based index of the CIDR we are currently walking. */
    var cidrIndex: Int = initialCidrIndex
        private set

    /** Next host index inside [cidrIndex] that [next] will return. */
    var ipOffset: Long = initialIpOffset
        private set

    /** Cached parse of the *current* CIDR; nulled when we step over. */
    private var currentParsed: Parsed? = null

    /** Snapshot of the iterator's position. */
    data class Cursor(val cidrIndex: Int, val ipOffset: Long)

    /** Total scannable IPv4 host count across every CIDR in [cidrs]. */
    fun totalHostCount(): Long {
        var total = 0L
        for (raw in cidrs) {
            val p = Parsed.parse(raw) ?: continue
            total += p.hostCount
        }
        return total
    }

    /** Best-effort total of host indices that have been emitted so far. */
    fun completedHostCount(): Long {
        var total = 0L
        for (i in 0 until cidrIndex.coerceAtMost(cidrs.size)) {
            val p = Parsed.parse(cidrs[i]) ?: continue
            total += p.hostCount
        }
        return total + ipOffset.coerceAtLeast(0L)
    }

    /** Capture the current cursor (use for SQLite persistence). */
    fun cursor(): Cursor = Cursor(cidrIndex, ipOffset)

    /** Returns the raw CIDR string we are currently emitting from. */
    fun currentCidr(): String? = cidrs.getOrNull(cidrIndex)

    /**
     * Pure-math next IPv4 host string, or `null` once the walk is done.
     * Bumps the cursor before returning. Skips IPv6 entries, unparseable
     * strings, and the network / broadcast addresses of `/8 … /30`.
     */
    fun next(): String? {
        while (cidrIndex < cidrs.size) {
            val parsed = currentParsed ?: Parsed.parse(cidrs[cidrIndex]).also { currentParsed = it }
            if (parsed == null) {
                // Skip junk / IPv6 — fast-forward past this CIDR.
                stepOverCurrent()
                continue
            }
            if (ipOffset >= parsed.hostCount) {
                stepOverCurrent()
                continue
            }
            val hostIndex = parsed.firstHostIndex + ipOffset
            ipOffset += 1
            val ipInt = (parsed.baseInt and 0xFFFFFFFFL) or hostIndex
            return ipToString(ipInt)
        }
        return null
    }

    private fun stepOverCurrent() {
        cidrIndex += 1
        ipOffset = 0L
        currentParsed = null
    }

    private fun ipToString(ipInt: Long): String {
        val a = (ipInt shr 24) and 0xFF
        val b = (ipInt shr 16) and 0xFF
        val c = (ipInt shr 8) and 0xFF
        val d = ipInt and 0xFF
        return "$a.$b.$c.$d"
    }

    /**
     * Inline numeric snapshot of a CIDR — IPv4 only. Holds the network
     * `baseInt` plus the inclusive [firstHostIndex] / exclusive
     * [endHostIndex] interval that the walk should emit.
     */
    private data class Parsed(
        val baseInt: Long,
        val firstHostIndex: Long,
        val endHostIndex: Long,
    ) {
        val hostCount: Long get() = (endHostIndex - firstHostIndex).coerceAtLeast(0L)

        companion object {
            /**
             * Re-parse the raw CIDR string ourselves (host + prefix) so we
             * never depend on `Cidr.network` (which is intentionally
             * private). Skips anything that isn't a sensible IPv4
             * prefix between /8 and /32 — IPv6, junk, and over-broad
             * /0../7 nets are not viable scan targets anyway.
             */
            fun parse(raw: String): Parsed? {
                val text = raw.trim()
                if (text.isEmpty() || text.startsWith('#') || text.contains(':')) return null
                val slash = text.indexOf('/')
                val (host, prefixStr) = if (slash >= 0) {
                    text.substring(0, slash) to text.substring(slash + 1)
                } else {
                    text to "32"
                }
                val prefix = prefixStr.toIntOrNull() ?: return null
                if (prefix !in 8..32) return null
                val parts = host.split('.')
                if (parts.size != 4) return null
                val octets = IntArray(4)
                for (i in 0 until 4) {
                    val v = parts[i].toIntOrNull() ?: return null
                    if (v !in 0..255) return null
                    octets[i] = v
                }
                val rawInt = ((octets[0].toLong()) shl 24) or
                    ((octets[1].toLong()) shl 16) or
                    ((octets[2].toLong()) shl 8) or
                    octets[3].toLong()
                val hostBits = 32 - prefix
                val total = 1L shl hostBits
                // Mask off the host bits so a non-canonical CIDR like
                // 192.168.0.255/24 still produces the right base 192.168.0.0.
                val mask = (0xFFFFFFFFL shl hostBits) and 0xFFFFFFFFL
                val baseInt = rawInt and mask
                val skipNetworkAndBroadcast = prefix in 8..30
                val first = if (skipNetworkAndBroadcast) 1L else 0L
                val end = if (skipNetworkAndBroadcast) total - 1 else total
                return Parsed(baseInt = baseInt, firstHostIndex = first, endHostIndex = end)
            }
        }
    }

    companion object {
        /** Start a fresh walk over [cidrs] from index 0, offset 0. */
        fun fromStart(cidrs: List<String>): IpStreamIterator =
            IpStreamIterator(cidrs, initialCidrIndex = 0, initialIpOffset = 0L)

        /**
         * Resume a previously-paused walk. Out-of-range cursor values
         * are clamped so a stale persisted state never throws.
         */
        fun resume(
            cidrs: List<String>,
            cidrIndex: Int,
            ipOffset: Long,
        ): IpStreamIterator {
            val safeIdx = cidrIndex.coerceIn(0, cidrs.size)
            val safeOff = ipOffset.coerceAtLeast(0L)
            return IpStreamIterator(cidrs, safeIdx, safeOff)
        }
    }
}
