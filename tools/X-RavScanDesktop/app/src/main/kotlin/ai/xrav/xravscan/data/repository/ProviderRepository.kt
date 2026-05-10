package ai.xrav.xravscan.data.repository

import ai.xrav.xravscan.db.XRavScanDb
import ai.xrav.xravscan.domain.model.DashboardStats
import ai.xrav.xravscan.domain.model.Provider
import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToOne
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn

class ProviderRepository(private val db: XRavScanDb) {

    private val q get() = db.xRavScanDbQueries

    fun observeStats(): Flow<DashboardStats> = combine(
        q.countProviders().asFlow().mapToOne(Dispatchers.IO),
        q.countCidrRanges().asFlow().mapToOne(Dispatchers.IO),
        q.countScanResults().asFlow().mapToOne(Dispatchers.IO),
        q.countDiscoveries().asFlow().mapToOne(Dispatchers.IO),
    ) { p, c, r, d ->
        DashboardStats(p.toInt(), c.toInt(), r.toInt(), d.toInt())
    }.flowOn(Dispatchers.IO)

    suspend fun listAll(): List<Provider> {
        return kotlinx.coroutines.withContext(Dispatchers.IO) {
            q.allProviders().executeAsList().map { row ->
                val cidrCount = q.cidrsForProvider(row.id).executeAsList().size
                Provider(
                    id = row.id,
                    slug = row.slug,
                    name = row.name,
                    color = row.color,
                    asns = row.asns.split(",").mapNotNull { it.trim().toLongOrNull() },
                    enabled = row.enabled == 1L,
                    cidrCount = cidrCount,
                )
            }
        }
    }

    suspend fun setEnabled(providerId: Long, enabled: Boolean) {
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            q.setProviderEnabled(if (enabled) 1L else 0L, providerId)
        }
    }
}
