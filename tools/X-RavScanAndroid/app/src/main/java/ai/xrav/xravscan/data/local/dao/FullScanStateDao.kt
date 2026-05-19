package ai.xrav.xravscan.data.local.dao

import ai.xrav.xravscan.data.local.entity.FullScanStateEntity
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * One-row-per-provider persistence for paused Full Provider Scans.
 * Designed for fast upsert (called every ~250ms while a scan runs) and
 * O(1) lookup on resume.
 */
@Dao
interface FullScanStateDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(state: FullScanStateEntity)

    @Query("SELECT * FROM full_scan_state WHERE providerSlug = :slug LIMIT 1")
    suspend fun stateFor(slug: String): FullScanStateEntity?

    @Query("DELETE FROM full_scan_state WHERE providerSlug = :slug")
    suspend fun clear(slug: String)
}
