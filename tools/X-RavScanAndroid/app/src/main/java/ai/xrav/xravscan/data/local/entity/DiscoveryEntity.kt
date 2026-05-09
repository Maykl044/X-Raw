package ai.xrav.xravscan.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "discoveries",
    indices = [
        Index("providerSlug"),
        Index(value = ["providerSlug", "cidr"], unique = true),
    ],
)
data class DiscoveryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val providerSlug: String,
    val asn: Long?,
    val cidr: String,
    val sourceApi: String,
    val foundAt: Long,
    val applied: Boolean,
)
