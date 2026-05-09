package ai.xrav.xravscan.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import ai.xrav.xravscan.data.local.dao.CidrRangeDao
import ai.xrav.xravscan.data.local.dao.DiscoveryDao
import ai.xrav.xravscan.data.local.dao.ProviderDao
import ai.xrav.xravscan.data.local.dao.ScanResultDao
import ai.xrav.xravscan.data.local.entity.CidrRangeEntity
import ai.xrav.xravscan.data.local.entity.DiscoveryEntity
import ai.xrav.xravscan.data.local.entity.ProviderEntity
import ai.xrav.xravscan.data.local.entity.ScanResultEntity

@Database(
    entities = [
        ProviderEntity::class,
        CidrRangeEntity::class,
        ScanResultEntity::class,
        DiscoveryEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class XRavScanDatabase : RoomDatabase() {
    abstract fun providerDao(): ProviderDao
    abstract fun cidrRangeDao(): CidrRangeDao
    abstract fun scanResultDao(): ScanResultDao
    abstract fun discoveryDao(): DiscoveryDao
}
