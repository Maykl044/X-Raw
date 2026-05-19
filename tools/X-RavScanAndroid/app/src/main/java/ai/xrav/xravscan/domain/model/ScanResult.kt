package ai.xrav.xravscan.domain.model

data class ScanResult(
    val id: Long,
    val providerSlug: String,
    val ip: String,
    val port: Int,
    val sni: String?,
    val rttMs: Int?,
    val tlsCertCn: String?,
    val scannedAt: Long,
)
