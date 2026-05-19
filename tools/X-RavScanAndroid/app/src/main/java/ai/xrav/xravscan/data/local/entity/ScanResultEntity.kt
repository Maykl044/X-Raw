package ai.xrav.xravscan.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "scan_results",
    indices = [
        Index("providerSlug"),
        Index(value = ["ip", "port"]),
    ],
)
data class ScanResultEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val providerSlug: String,
    val ip: String,
    val port: Int,
    val sni: String?,
    val rttMs: Int?,
    val tlsCertCn: String?,
    val scannedAt: Long,
)
