package ai.xrav.xravscan.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import ai.xrav.xravscan.data.local.entity.CidrRangeEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CidrRangeDao {

    @Query("SELECT COUNT(*) FROM cidr_ranges")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM cidr_ranges")
    fun observeCount(): Flow<Int>

    @Query("SELECT * FROM cidr_ranges WHERE providerSlug = :slug")
    suspend fun rangesForProvider(slug: String): List<CidrRangeEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(ranges: List<CidrRangeEntity>): List<Long>

    @Query("DELETE FROM cidr_ranges WHERE providerSlug = :slug")
    suspend fun deleteForProvider(slug: String)
}
