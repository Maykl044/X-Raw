package ai.xrav.xravscan.domain.model

data class Discovery(
    val id: Long,
    val providerSlug: String,
    val asn: Long?,
    val cidr: String,
    val sourceApi: String,
    val foundAt: Long,
    val applied: Boolean,
)
