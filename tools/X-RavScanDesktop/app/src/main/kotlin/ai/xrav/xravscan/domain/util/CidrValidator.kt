package ai.xrav.xravscan.domain.util

/**
 * Tiny façade around [Cidr.parse] that two extra Phase H call sites
 * need: a boolean "is this string a syntactically valid CIDR?" and a
 * "give me the canonical `network/prefix` form, or null".
 *
 * Used by the dedup pipeline so two different vendor feeds that
 * spell the same network slightly differently
 * (`192.168.0.5/24` vs `192.168.0.0/24`) collapse to one entry.
 */
object CidrValidator {

    /** Whether [raw] parses as a valid IPv4 or IPv6 CIDR. */
    fun isValid(raw: String): Boolean = Cidr.parse(raw) != null

    /**
     * Returns the canonical `network/prefix` form, or `null` if
     * [raw] is not a valid CIDR. `192.168.0.255/24` → `192.168.0.0/24`.
     */
    fun canonical(raw: String): String? = Cidr.parse(raw)?.canonical
}
