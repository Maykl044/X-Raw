package ai.xrav.xravscan.data.seed

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SeedFile(
    val providers: List<SeedProvider> = emptyList(),
)

@Serializable
data class SeedProvider(
    val slug: String,
    val name: String,
    val color: String = "#7AA9FF",
    val asns: List<Long> = emptyList(),
    @SerialName("seed_file") val seedFile: String? = null,
    val sources: List<SeedSource> = emptyList(),
)

@Serializable
data class SeedSource(
    val type: String = "",
    val url: String = "",
    val service: String? = null,
)
