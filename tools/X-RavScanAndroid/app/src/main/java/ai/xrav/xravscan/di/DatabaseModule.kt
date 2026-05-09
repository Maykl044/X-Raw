package ai.xrav.xravscan.di

import android.content.Context
import androidx.room.Room
import ai.xrav.xravscan.data.local.XRavScanDatabase
import ai.xrav.xravscan.data.local.dao.CidrRangeDao
import ai.xrav.xravscan.data.local.dao.DiscoveryDao
import ai.xrav.xravscan.data.local.dao.ProviderDao
import ai.xrav.xravscan.data.local.dao.ScanResultDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): XRavScanDatabase =
        Room.databaseBuilder(
            context,
            XRavScanDatabase::class.java,
            "x_ravscan.db",
        )
            .fallbackToDestructiveMigration()
            .build()

    @Provides
    fun provideProviderDao(db: XRavScanDatabase): ProviderDao = db.providerDao()

    @Provides
    fun provideCidrRangeDao(db: XRavScanDatabase): CidrRangeDao = db.cidrRangeDao()

    @Provides
    fun provideScanResultDao(db: XRavScanDatabase): ScanResultDao = db.scanResultDao()

    @Provides
    fun provideDiscoveryDao(db: XRavScanDatabase): DiscoveryDao = db.discoveryDao()
}
