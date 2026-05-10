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
 * Pure-Kotlin Smart-Append implementation that mirrors the legacy Python
 * `data_manager.smart_append` reference logic.
 *
 * Self-contained — only depends on the standard library, so the desktop
 * artefact stays small and the same code can later move into commonMain.
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
 * Lightweight CIDR representation. Pure standard-library: parses both IPv4
 * and IPv6 prefixes, normalises them to a canonical "network/prefixlen"
 * form, and exposes a [subnetOf] helper.
 */
data class Cidr(
    val version: Int,
    val network: ByteArray,
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
        return matchesPrefix(network, other.network, other.prefixLen)
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
