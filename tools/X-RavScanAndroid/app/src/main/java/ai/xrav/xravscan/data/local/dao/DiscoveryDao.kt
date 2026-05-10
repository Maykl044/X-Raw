package ai.xrav.xravscan.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import ai.xrav.xravscan.data.local.entity.DiscoveryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface DiscoveryDao {

    @Query("SELECT COUNT(*) FROM discoveries")
    fun observeCount(): Flow<Int>

    @Query("SELECT * FROM discoveries ORDER BY foundAt DESC")
    fun observeAll(): Flow<List<DiscoveryEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(rows: List<DiscoveryEntity>): List<Long>

    @Query("UPDATE discoveries SET applied = :applied WHERE id = :id")
    suspend fun setApplied(id: Long, applied: Boolean)

    @Query("DELETE FROM discoveries WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM discoveries WHERE applied = 0")
    suspend fun deletePending()
}
