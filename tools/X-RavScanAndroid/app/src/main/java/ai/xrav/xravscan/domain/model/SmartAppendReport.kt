package ai.xrav.xravscan.domain.model

/**
 * Per-provider summary line returned by Smart Append discovery — mirrors the
 * Python `ProviderSyncReport` shape so we can format identical iOS-style
 * single-line summaries:
 *
 *   "Cloudflare: 5 added, 120 skipped"
 *   "Azure: up to date — no new networks"
 *   "Akamai: error — http 502"
 */
data class SmartAppendReport(
    val providerSlug: String,
    val providerName: String,
    val added: Int,
    val skipped: Int,
    val superseded: Int,
    val error: String? = null,
) {
    val ok: Boolean get() = error == null

    fun iosLine(): String = when {
        added > 0 -> buildString {
            append(providerName)
            append(": ")
            append(added)
            append(" added, ")
            append(skipped)
            append(" skipped")
            if (superseded > 0) {
                append(", ")
                append(superseded)
                append(" superseded")
            }
        }
        error != null -> "$providerName: error — $error"
        else -> "$providerName: up to date — no new networks"
    }
}
