package ai.xrav.xravscan.domain.repository

import ai.xrav.xravscan.domain.model.FullScanProgress
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

    /**
     * Exhaustively expand every CIDR registered for [providerSlug] into
     * individual IPs and probe them in a bounded coroutine pool.
     *
     * Streaming details:
     *  - IP generation is lazy ([Sequence] over each CIDR), so we never
     *    pre-materialise the (potentially millions-long) target list;
     *  - probes run on [Dispatchers.IO] under a [Semaphore] of size
     *    [concurrency] so the device's NIC isn't melted;
     *  - results are batched and persisted every 200 hits, so SQLite
     *    isn't hit with one INSERT per IP;
     *  - emissions on the returned [Flow] are conflated by the caller
     *    (Compose), so a hot UI never blocks the producer.
     *
     * The scan stops at [maxIps] total probes — the default 50 000 is a
     * sane "looks like a real run" upper bound. Pass [Long.MAX_VALUE] to
     * truly attempt every IP (be aware: Cloudflare alone is 162M IPs).
     */
    fun runFullProviderScan(
        providerSlug: String,
        maxIps: Long = 50_000L,
        concurrency: Int = 64,
        resume: Boolean = false,
    ): Flow<FullScanProgress>

    /**
     * Snapshot of a paused Full Provider Scan as persisted in
     * `full_scan_state`. The UI uses it to render a "Resume" button
     * with the right counters before a scan is actually started.
     */
    data class PausedScan(
        val providerSlug: String,
        val cidrIndex: Int,
        val ipOffset: Long,
        val ipsScanned: Long,
        val ipsTotal: Long,
        val hits: Int,
        val maxIps: Long,
        val concurrency: Int,
    )

    suspend fun pausedScanFor(providerSlug: String): PausedScan?

    suspend fun discardPausedScan(providerSlug: String)

    suspend fun clearAll()
}
