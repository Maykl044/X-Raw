package ai.xrav.xravscan.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "providers")
data class ProviderEntity(
    @PrimaryKey val slug: String,
    val name: String,
    val color: String,
    /** Comma-separated list of ASN numbers; deserialised in the repository. */
    val asnsCsv: String,
    val enabled: Boolean,
)
