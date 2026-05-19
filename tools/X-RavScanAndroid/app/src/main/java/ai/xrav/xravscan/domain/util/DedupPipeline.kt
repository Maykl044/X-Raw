package ai.xrav.xravscan.domain.util

/**
 * One-shot, per-provider dedup + validation step that turns a raw
 * vendor feed into the three numbers we want to surface to the user:
 *
 *   * `found`      — total candidate CIDRs received from the API
 *   * `invalid`    — CIDRs that fail [CidrValidator.isValid]
 *   * `duplicates` — valid CIDRs that already exist for this provider
 *   * `added`      — valid, non-duplicate CIDRs that will be inserted
 *
 * The pipeline is deliberately framework-agnostic — no Android, no
 * Hilt, no coroutines — so the Desktop module can re-use it
 * byte-for-byte.
 */
object DedupPipeline {

    data class Outcome(
        val found: Int,
        val invalid: Int,
        val duplicates: Int,
        val added: List<String>,
    )

    /**
     * @param fetched the raw list of CIDR strings received from a vendor feed.
     * @param existing the canonical CIDRs already stored for this provider.
     */
    fun process(fetched: List<String>, existing: Collection<String>): Outcome {
        val existingSet: HashSet<String> = existing
            .mapNotNullTo(HashSet(existing.size)) { CidrValidator.canonical(it) }

        var invalid = 0
        var duplicates = 0
        val added = mutableListOf<String>()
        val seenThisRun = HashSet<String>(fetched.size)

        for (raw in fetched) {
            val canonical = CidrValidator.canonical(raw)
            if (canonical == null) {
                invalid++
                continue
            }
            if (!seenThisRun.add(canonical)) {
                duplicates++
                continue
            }
            if (canonical in existingSet) {
                duplicates++
                continue
            }
            added += canonical
            existingSet += canonical
        }

        return Outcome(
            found = fetched.size,
            invalid = invalid,
            duplicates = duplicates,
            added = added,
        )
    }
}
