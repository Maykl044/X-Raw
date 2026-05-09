package ai.xrav.xravscan.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import ai.xrav.xravscan.data.local.entity.ScanResultEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ScanResultDao {

    @Query("SELECT COUNT(*) FROM scan_results")
    fun observeCount(): Flow<Int>

    @Query("SELECT * FROM scan_results ORDER BY scannedAt DESC LIMIT :limit")
    fun observeRecent(limit: Int = 200): Flow<List<ScanResultEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(result: ScanResultEntity): Long

    @Query("DELETE FROM scan_results")
    suspend fun deleteAll()
}
