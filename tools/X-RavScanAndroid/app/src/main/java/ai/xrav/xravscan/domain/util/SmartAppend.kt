package ai.xrav.xravscan.domain.util

/**
 * Result of merging a batch of incoming CIDRs into an existing set using the
 * Smart-Append rules: exact-duplicate / subset / supernet checks.
 */
data class SmartAppendOutcome(
    val added: List<String>,
    val skipped: Int,
    val superseded: List<String>,
)

/**
 * Pure-Kotlin Smart-Append implementation that mirrors the Python
 * `data_manager.smart_append` reference logic.
 *
 * It only depends on the standard library (no inet.ipaddr) — we re-use the
 * tiny [Cidr] helper below so the algorithm stays self-contained and the
 * Android build doesn't pull a heavy third-party IP-math dependency.
 */
fun smartAppend(existing: Collection<String>, incoming: Iterable<String>): SmartAppendOutcome {
    val parsedExisting = existing.mapNotNull { Cidr.parse(it) }.toMutableList()
    val seen = parsedExisting.map { it.canonical }.toMutableSet()

    val added = mutableListOf<String>()
    val superseded = mutableListOf<String>()
    var skipped = 0

    for (raw in incoming) {
        val net = Cidr.parse(raw) ?: continue
        val key = net.canonical
        if (key in seen) {
            skipped++
            continue
        }

        val isSubset = parsedExisting.any { known ->
            known.version == net.version && net.subnetOf(known)
        }
        if (isSubset) {
            skipped++
            continue
        }

        for (known in parsedExisting.toList()) {
            if (known.version == net.version && known != net && known.subnetOf(net)) {
                superseded += known.canonical
            }
        }

        parsedExisting += net
        seen += key
        added += key
    }

    return SmartAppendOutcome(added = added, skipped = skipped, superseded = superseded)
}

/**
 * Lightweight CIDR representation used by Smart-Append.
 *
 * Pure standard-library: parses both IPv4 and IPv6 prefixes, normalises them
 * to a canonical "network/prefixlen" form, and exposes a [subnetOf] helper.
 */
data class Cidr(
    val version: Int,
    private val network: ByteArray,
    val prefixLen: Int,
) {
    val canonical: String by lazy {
        when (version) {
            4 -> "${network.joinToString(".") { (it.toInt() and 0xFF).toString() }}/$prefixLen"
            else -> ipv6String(network) + "/" + prefixLen
        }
    }

    fun subnetOf(other: Cidr): Boolean {
        if (version != other.version) return false
        if (prefixLen < other.prefixLen) return false
        // Compare the first `other.prefixLen` bits.
        return matchesPrefix(network, other.network, other.prefixLen)
    }

    /**
     * Total addressable IPs in this prefix.
     *  - IPv4: ``2^(32-prefixLen)`` — capped at [Long.MAX_VALUE] for the
     *    pathological ``/0`` case (which we'd never actually scan).
     *  - IPv6: only meaningful for ``/112`` and narrower; wider prefixes
     *    return [Long.MAX_VALUE] so callers can refuse to expand them.
     */
    val size: Long get() = when (version) {
        4 -> 1L shl (32 - prefixLen)
        6 -> if (prefixLen >= 64) 1L shl (128 - prefixLen).coerceAtMost(62) else Long.MAX_VALUE
        else -> 0L
    }

    /**
     * Lazily yields every IPv4 address in the prefix, **excluding** the
     * network and broadcast addresses for prefixes ≤ /30. ``/31`` and
     * ``/32`` yield both addresses (RFC 3021 / single host).
     *
     * Returns an empty sequence for IPv6 — full IPv6 expansion is never
     * a sensible scan target.
     */
    fun expandIpv4(): Sequence<String> = sequence {
        if (version != 4 || prefixLen < 8) return@sequence
        val baseInt = ((network[0].toInt() and 0xFF) shl 24) or
            ((network[1].toInt() and 0xFF) shl 16) or
            ((network[2].toInt() and 0xFF) shl 8) or
            (network[3].toInt() and 0xFF)
        val hostBits = 32 - prefixLen
        val total = 1L shl hostBits
        val skipNetworkAndBroadcast = prefixLen in 8..30
        val start = if (skipNetworkAndBroadcast) 1L else 0L
        val endExclusive = if (skipNetworkAndBroadcast) total - 1 else total
        for (i in start until endExclusive) {
            val ipInt = (baseInt.toLong() and 0xFFFFFFFFL) or i
            val a = (ipInt shr 24) and 0xFF
            val b = (ipInt shr 16) and 0xFF
            val c = (ipInt shr 8) and 0xFF
            val d = ipInt and 0xFF
            yield("$a.$b.$c.$d")
        }
    }

    override fun equals(other: Any?): Boolean =
        other is Cidr && version == other.version && prefixLen == other.prefixLen &&
            network.contentEquals(other.network)

    override fun hashCode(): Int =
        version * 31 * 31 + prefixLen * 31 + network.contentHashCode()

    companion object {
        fun parse(raw: String): Cidr? = runCatching {
            val text = raw.trim()
            if (text.isEmpty() || text.startsWith('#')) return@runCatching null
            val slash = text.indexOf('/')
            val (host, prefixStr) = if (slash >= 0) {
                text.substring(0, slash) to text.substring(slash + 1)
            } else {
                text to null
            }
            val isIpv6 = host.contains(':')
            val bytes = if (isIpv6) parseIpv6(host) else parseIpv4(host)
            val version = if (isIpv6) 6 else 4
            val totalBits = if (isIpv6) 128 else 32
            val prefix = prefixStr?.toIntOrNull() ?: totalBits
            if (prefix !in 0..totalBits) return@runCatching null
            val network = applyMask(bytes, prefix)
            Cidr(version, network, prefix)
        }.getOrNull()

        private fun parseIpv4(host: String): ByteArray {
            val parts = host.split('.')
            require(parts.size == 4) { "ipv4 expects 4 octets" }
            return ByteArray(4) { i ->
                val v = parts[i].toInt()
                require(v in 0..255) { "octet out of range" }
                v.toByte()
            }
        }

        private fun parseIpv6(host: String): ByteArray {
            // Minimal RFC4291 IPv6 parser — handles "::" compression. Doesn't
            // handle scope ids or embedded IPv4 (good enough for CIDR seeds).
            val doubleColon = host.indexOf("::")
            val (head, tail) = if (doubleColon >= 0) {
                val left = host.substring(0, doubleColon).split(':').filter { it.isNotEmpty() }
                val right = host.substring(doubleColon + 2).split(':').filter { it.isNotEmpty() }
                left to right
            } else {
                host.split(':') to emptyList()
            }
            val groups = head + List(8 - head.size - tail.size) { "0" } + tail
            require(groups.size == 8) { "ipv6 must expand to 8 groups" }
            val out = ByteArray(16)
            for (i in groups.indices) {
                val v = groups[i].ifEmpty { "0" }.toInt(16)
                require(v in 0..0xFFFF) { "group out of range" }
                out[i * 2] = ((v shr 8) and 0xFF).toByte()
                out[i * 2 + 1] = (v and 0xFF).toByte()
            }
            return out
        }

        private fun applyMask(bytes: ByteArray, prefix: Int): ByteArray {
            val out = bytes.copyOf()
            var bits = prefix
            for (i in out.indices) {
                if (bits >= 8) {
                    bits -= 8
                    continue
                }
                val mask = if (bits == 0) 0 else (0xFF shl (8 - bits)) and 0xFF
                out[i] = (out[i].toInt() and mask).toByte()
                bits = 0
            }
            return out
        }

        private fun matchesPrefix(a: ByteArray, b: ByteArray, prefix: Int): Boolean {
            var bits = prefix
            for (i in a.indices) {
                if (bits >= 8) {
                    if (a[i] != b[i]) return false
                    bits -= 8
                    continue
                }
                if (bits == 0) return true
                val mask = (0xFF shl (8 - bits)) and 0xFF
                if ((a[i].toInt() and mask) != (b[i].toInt() and mask)) return false
                return true
            }
            return true
        }

        private fun ipv6String(bytes: ByteArray): String {
            val groups = IntArray(8) { i ->
                ((bytes[i * 2].toInt() and 0xFF) shl 8) or (bytes[i * 2 + 1].toInt() and 0xFF)
            }
            // Find longest run of zeroes for "::" compression.
            var bestStart = -1
            var bestLen = 0
            var curStart = -1
            var curLen = 0
            for (i in groups.indices) {
                if (groups[i] == 0) {
                    if (curStart < 0) curStart = i
                    curLen++
                    if (curLen > bestLen) {
                        bestLen = curLen
                        bestStart = curStart
                    }
                } else {
                    curStart = -1
                    curLen = 0
                }
            }
            if (bestLen < 2) {
                return groups.joinToString(":") { it.toString(16) }
            }
            val before = (0 until bestStart).joinToString(":") { groups[it].toString(16) }
            val afterStart = bestStart + bestLen
            val after = (afterStart until 8).joinToString(":") { groups[it].toString(16) }
            return "$before::$after"
        }
    }
}

