package ai.xrav.xravscan.domain.repository

import ai.xrav.xravscan.domain.model.Provider
import kotlinx.coroutines.flow.Flow

interface ProviderRepository {
    fun observeAll(): Flow<List<Provider>>
    suspend fun setEnabled(slug: String, enabled: Boolean)
    fun observeCidrCount(): Flow<Int>
    fun observeResultCount(): Flow<Int>
    fun observeDiscoveryCount(): Flow<Int>
}
