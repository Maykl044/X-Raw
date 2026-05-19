package ai.xrav.xravscan.domain.repository

import ai.xrav.xravscan.domain.model.ScanResult
import kotlinx.coroutines.flow.Flow

interface ScanRepository {
    fun observeRecent(limit: Int = 200): Flow<List<ScanResult>>

    /**
     * Run a quick TCP/TLS reachability sweep over the enabled providers.
     * Picks [sampleSize] random hosts per provider, opens a socket to
     * port 443, optionally tries a bare TLS handshake to capture the
     * certificate CN, and persists the outcome.
     */
    suspend fun runQuickScan(
        sampleSize: Int = 32,
        onLog: (String) -> Unit,
    ): Int

    suspend fun clearAll()
}
