package ai.xrav.xravscan.data.seed

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Schema mirroring the legacy Python desktop's seed_providers.json.
 *
 * Every entry contributes one [SeedProvider] which is mapped into the SQLite
 * `providers` table on first launch. The CIDR ranges live alongside the JSON
 * in `seed/ranges/<slug>_ranges.txt` and are loaded separately by the seeder.
 */
@Serializable
data class SeedFile(
    @SerialName("providers") val providers: List<SeedProvider> = emptyList(),
)

@Serializable
data class SeedProvider(
    val slug: String,
    val name: String,
    val color: String = "#60A5FA",
    val asns: List<Long> = emptyList(),
    @SerialName("seed_file") val seedFile: String? = null,
    val enabled: Boolean = true,
)
