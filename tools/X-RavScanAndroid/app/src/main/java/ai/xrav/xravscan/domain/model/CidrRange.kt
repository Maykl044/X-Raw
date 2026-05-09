package ai.xrav.xravscan.domain.model

data class CidrRange(
    val id: Long,
    val providerSlug: String,
    val cidr: String,
    val source: String,
)
