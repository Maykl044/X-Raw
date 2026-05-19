package ai.xrav.xravscan.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import ai.xrav.xravscan.data.local.entity.ProviderEntity
import kotlinx.coroutines.flow.Flow

data class ProviderWithCount(
    val slug: String,
    val name: String,
    val color: String,
    val asnsCsv: String,
    val enabled: Boolean,
    val cidrCount: Int,
)

@Dao
interface ProviderDao {

    @Query("SELECT COUNT(*) FROM providers")
    suspend fun count(): Int

    @Query("SELECT * FROM providers ORDER BY name COLLATE NOCASE ASC")
    fun observeAll(): Flow<List<ProviderEntity>>

    @Query(
        """
        SELECT p.slug AS slug,
               p.name AS name,
               p.color AS color,
               p.asnsCsv AS asnsCsv,
               p.enabled AS enabled,
               COALESCE((SELECT COUNT(*) FROM cidr_ranges r WHERE r.providerSlug = p.slug), 0) AS cidrCount
        FROM providers p
        ORDER BY p.name COLLATE NOCASE ASC
        """
    )
    fun observeAllWithCount(): Flow<List<ProviderWithCount>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(provider: ProviderEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(providers: List<ProviderEntity>)

    @Query("UPDATE providers SET enabled = :enabled WHERE slug = :slug")
    suspend fun setEnabled(slug: String, enabled: Boolean)
}
