package ai.xrav.xravscan.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Pause / Resume cursor for a Full Provider Scan. One row per provider
 * slug — `INSERT OR REPLACE` semantics so the latest cursor always
 * wins. Holds the iterator state so a scan can survive process death
 * and pick up at the exact bit-offset of the last probe.
 *
 * `cidrSnapshot` is a newline-joined snapshot of the CIDRs (in scan
 * order) at the moment the scan started — guarantees the cursor is
 * meaningful even if the provider's CIDR table is rewritten while a
 * scan is paused.
 */
@Entity(tableName = "full_scan_state")
data class FullScanStateEntity(
    @PrimaryKey val providerSlug: String,
    val cidrIndex: Int,
    val ipOffset: Long,
    val ipsScanned: Long,
    val hits: Int,
    val maxIps: Long,
    val concurrency: Int,
    val cidrSnapshot: String,
    val updatedAt: Long,
)
