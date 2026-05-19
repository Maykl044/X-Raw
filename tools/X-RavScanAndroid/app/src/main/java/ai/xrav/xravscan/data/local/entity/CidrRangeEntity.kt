package ai.xrav.xravscan.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "cidr_ranges",
    foreignKeys = [
        ForeignKey(
            entity = ProviderEntity::class,
            parentColumns = ["slug"],
            childColumns = ["providerSlug"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("providerSlug"),
        Index(value = ["providerSlug", "cidr"], unique = true),
    ],
)
data class CidrRangeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val providerSlug: String,
    val cidr: String,
    val source: String,
)
