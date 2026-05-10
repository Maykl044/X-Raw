package ai.xrav.xravscan.domain.model

/**
 * Streaming progress signal emitted by a Full Provider Scan.
 *
 * The scanner publishes one update every ~250ms (or every batch of 200
 * probes — whichever comes first) so the UI can show a live counter
 * without taking the foreground thread hostage.
 */
data class FullScanProgress(
    val providerSlug: String,
    val providerName: String,
    val ipsScanned: Long,
    val ipsTotal: Long,
    val hits: Int,
    val currentCidr: String?,
    val message: String? = null,
    val done: Boolean = false,
    val cancelled: Boolean = false,
) {
    val percent: Float
        get() = if (ipsTotal <= 0) 0f else (ipsScanned.toFloat() / ipsTotal.toFloat()).coerceIn(0f, 1f)
}
