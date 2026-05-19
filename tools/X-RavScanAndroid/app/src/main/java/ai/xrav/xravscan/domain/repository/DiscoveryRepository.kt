package ai.xrav.xravscan.domain.repository

import ai.xrav.xravscan.domain.model.Discovery
import ai.xrav.xravscan.domain.model.SmartAppendReport
import kotlinx.coroutines.flow.Flow

interface DiscoveryRepository {

    /** Pending + applied discoveries ordered newest first. */
    fun observeAll(): Flow<List<Discovery>>

    /**
     * Run an ASN-driven Smart Append over every enabled provider, in
     * parallel. Each per-provider summary is delivered through [onLog] (so
     * the Discovery screen can stream lines into its activity feed) and
     * also returned as the final list once everything settles.
     *
     * Discovered prefixes that survived the subset / dup checks are
     * inserted into `discoveries` — the user can then merge them into
     * `cidr_ranges` via [applyDiscovery] / [applyAllForProvider].
     */
    suspend fun runSmartAppend(
        messages: SmartAppendMessages = SmartAppendMessages(),
        onLog: suspend (String) -> Unit,
    ): List<SmartAppendReport>

    /** Collapse all `cidr_ranges` per provider in place (Route Summarisation). */
    suspend fun cleanAndOptimize(onLog: suspend (String) -> Unit): Int

    /** Promote a single discovery into `cidr_ranges` and mark it applied. */
    suspend fun applyDiscovery(id: Long)

    /** Bulk variant — apply every pending discovery for [providerSlug]. */
    suspend fun applyAllForProvider(providerSlug: String): Int

    /** Drop a discovery row outright. */
    suspend fun dismiss(id: Long)

    /** Clear every pending (unapplied) discovery row. */
    suspend fun dismissAllPending()
}
