package ai.xrav.xravscan.data.repository

import ai.xrav.xravscan.data.local.dao.CidrRangeDao
import ai.xrav.xravscan.data.local.dao.DiscoveryDao
import ai.xrav.xravscan.data.local.dao.ProviderDao
import ai.xrav.xravscan.data.local.dao.ProviderWithCount
import ai.xrav.xravscan.data.local.dao.ScanResultDao
import ai.xrav.xravscan.domain.model.Provider
import ai.xrav.xravscan.domain.repository.ProviderRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

@Singleton
class ProviderRepositoryImpl @Inject constructor(
    private val providerDao: ProviderDao,
    private val cidrRangeDao: CidrRangeDao,
    private val scanResultDao: ScanResultDao,
    private val discoveryDao: DiscoveryDao,
) : ProviderRepository {

    override fun observeAll(): Flow<List<Provider>> =
        providerDao.observeAllWithCount().map { rows -> rows.map { it.toDomain() } }

    override suspend fun setEnabled(slug: String, enabled: Boolean) {
        providerDao.setEnabled(slug, enabled)
    }

    override fun observeCidrCount(): Flow<Int> = cidrRangeDao.observeCount()

    override fun observeResultCount(): Flow<Int> = scanResultDao.observeCount()

    override fun observeDiscoveryCount(): Flow<Int> = discoveryDao.observeCount()

    private fun ProviderWithCount.toDomain(): Provider = Provider(
        slug = slug,
        name = name,
        color = color,
        asns = asnsCsv.split(",").mapNotNull { it.trim().toLongOrNull() },
        enabled = enabled,
        cidrCount = cidrCount,
    )
}
