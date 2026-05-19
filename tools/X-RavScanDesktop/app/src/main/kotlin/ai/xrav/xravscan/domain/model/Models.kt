package ai.xrav.xravscan.domain.model

data class Provider(
    val id: Long,
    val slug: String,
    val name: String,
    val color: String,
    val asns: List<Long>,
    val enabled: Boolean,
    val cidrCount: Int = 0,
)

data class Discovery(
    val id: Long,
    val providerSlug: String,
    val cidr: String,
    val foundAt: Long,
    val applied: Boolean,
)

data class ScanResult(
    val id: Long,
    val providerSlug: String,
    val ip: String,
    val port: Int,
    val rttMs: Long,
    val tlsCn: String?,
    val foundAt: Long,
)

data class DashboardStats(
    val providers: Int,
    val cidrLoaded: Int,
    val results: Int,
    val discoveries: Int,
)

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

data class SmartAppendReport(
    val providerSlug: String,
    val providerName: String,
    val added: Int,
    val skipped: Int,
    val superseded: Int,
    val error: String? = null,
) {
    fun iosLine(): String = when {
        error != null && added == 0 -> "$providerName: error — $error"
        added > 0 -> "$providerName: $added added, $skipped skipped" +
            if (superseded > 0) ", $superseded superseded" else ""
        else -> "$providerName: up to date — no new networks"
    }
}
