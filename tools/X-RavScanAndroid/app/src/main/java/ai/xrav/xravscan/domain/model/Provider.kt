package ai.xrav.xravscan.domain.model

/**
 * A logical CDN / cloud / hosting provider whose IP ranges X-RavScan
 * tracks. The slug is the stable identifier used everywhere (file names,
 * foreign keys); the display name is what we show in the UI.
 */
data class Provider(
    val slug: String,
    val name: String,
    val color: String,
    val asns: List<Long>,
    val enabled: Boolean,
    val cidrCount: Int,
)
