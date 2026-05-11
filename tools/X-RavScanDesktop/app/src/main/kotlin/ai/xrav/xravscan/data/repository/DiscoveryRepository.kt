package ai.xrav.xravscan.data.repository

import ai.xrav.xravscan.data.remote.BgpViewService
import ai.xrav.xravscan.data.remote.BunnyRangeProvider
import ai.xrav.xravscan.db.XRavScanDb
import ai.xrav.xravscan.domain.model.Discovery
import ai.xrav.xravscan.domain.model.SmartAppendReport
import ai.xrav.xravscan.domain.util.Cidr
import ai.xrav.xravscan.domain.util.smartAppend
import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory

/**
 * Discovery + Smart-Append + Clean-and-Optimise live here. The repository
 * is injected with both the database and the BGPView service via
 * [ai.xrav.xravscan.AppContainer].
 *
 * All public APIs marshal the underlying calls onto [Dispatchers.IO]
 * (network + SQLite) so the caller can stay on whatever scope it likes.
 */
class DiscoveryRepository(
    private val db: XRavScanDb,
    private val bgpView: BgpViewService,
    private val bunny: BunnyRangeProvider,
) {
    private val log = LoggerFactory.getLogger("XRavScan.Discovery")
    private val q get() = db.xRavScanDbQueries

    /**
     * Localizable status messages used during a Smart Append run. The
     * repository emits semantic events and the UI supplies a [Messages]
     * instance built from the active `AppStrings` so log lines render in
     * the user's selected language. Defaults are English so any caller
     * that doesn't care about i18n keeps working unchanged.
     */
    data class Messages(
        val bunnyLoadingViaApi: String = "Loading Bunny CDN ranges via direct API…",
        val bunnyReceivedGrouped: (Int, Int) -> String = { ips, cidrs ->
            "Bunny: $ips IPs received, grouped into $cidrs CIDRs"
        },
        val bunnyFallbackUsed: (Int) -> String = { n ->
            "Bunny CDN: fell back to built-in list ($n CIDR)"
        },
    )

    fun observeAll(): Flow<List<Discovery>> =
        q.allDiscoveries().asFlow().mapToList(Dispatchers.IO).map { rows ->
            rows.map {
                Discovery(
                    id = it.id,
                    providerSlug = it.providerSlug,
                    cidr = it.cidr,
                    foundAt = it.foundAt,
                    applied = it.applied == 1L,
                )
            }
        }.flowOn(Dispatchers.IO)

    /**
     * Run a parallel BGPView walk for every enabled provider. Truly-new
     * prefixes land in the `discoveries` table with `applied = 0`. Each
     * provider produces one terse iOS-style log line via [onLog].
     */
    suspend fun runSmartAppend(
        messages: Messages = Messages(),
        onLog: suspend (String) -> Unit,
    ): List<SmartAppendReport> =
        withContext(Dispatchers.IO) {
            val providers = q.enabledProviders().executeAsList()
            if (providers.isEmpty()) {
                onLog("No enabled providers — nothing to do")
                return@withContext emptyList()
            }
            onLog("Smart Append starting — ${providers.size} provider(s)")

            coroutineScope {
                providers.map { row ->
                    async {
                        val report = if (row.slug.equals("bunny", ignoreCase = true)) {
                            syncBunny(row.id, row.slug, row.name, messages, onLog)
                        } else {
                            val asns = row.asns.split(',')
                                .mapNotNull { it.trim().toLongOrNull() }
                            syncProvider(row.id, row.slug, row.name, asns)
                        }
                        onLog(report.iosLine())
                        report
                    }
                }.awaitAll()
            }
        }

    /**
     * Route-summarisation lite: drop subnets that are already covered by a
     * larger known prefix from the same provider. Performed inside one
     * SQLite transaction so the operation is atomic.
     */
    suspend fun cleanAndOptimize(onLog: suspend (String) -> Unit): Int =
        withContext(Dispatchers.IO) {
            val providers = q.allProviders().executeAsList()
            var totalRemoved = 0
            for (p in providers) {
                val before = q.cidrsForProvider(p.id).executeAsList()
                if (before.isEmpty()) continue
                val collapsed = collapseCidrs(before)
                val removed = before.size - collapsed.size
                if (removed <= 0) continue
                db.transaction {
                    q.deleteCidrsForProvider(p.id)
                    for (cidr in collapsed) {
                        val family = if (cidr.contains(":")) 6L else 4L
                        q.insertCidr(p.id, cidr, family)
                    }
                }
                totalRemoved += removed
                onLog("${p.name}: collapsed ${before.size} → ${collapsed.size} ($removed removed)")
            }
            if (totalRemoved == 0) onLog("Clean & Optimize — nothing to collapse")
            else onLog("Clean & Optimize done — $totalRemoved redundant ranges removed")
            totalRemoved
        }

    suspend fun applyDiscovery(id: Long) {
        withContext(Dispatchers.IO) {
            val pending = q.allDiscoveries().executeAsList().firstOrNull { it.id == id }
                ?: return@withContext
            val family = if (pending.cidr.contains(":")) 6L else 4L
            db.transaction {
                q.insertCidrBySlug(cidr = pending.cidr, family = family, slug = pending.providerSlug)
                q.setDiscoveryApplied(id)
            }
        }
    }

    suspend fun applyAllForProvider(providerSlug: String): Int =
        withContext(Dispatchers.IO) {
            val pending = q.pendingDiscoveries().executeAsList()
                .filter { it.providerSlug == providerSlug }
            if (pending.isEmpty()) return@withContext 0
            db.transaction {
                for (it in pending) {
                    val family = if (it.cidr.contains(":")) 6L else 4L
                    q.insertCidrBySlug(cidr = it.cidr, family = family, slug = it.providerSlug)
                    q.setDiscoveryApplied(it.id)
                }
            }
            pending.size
        }

    suspend fun dismiss(id: Long) {
        withContext(Dispatchers.IO) { q.deleteDiscoveryById(id) }
    }

    suspend fun dismissAllPending() {
        withContext(Dispatchers.IO) { q.deletePendingDiscoveries() }
    }

    // ------------------------------------------------------------------
    // internals
    // ------------------------------------------------------------------

    private suspend fun syncProvider(
        providerId: Long,
        slug: String,
        name: String,
        asns: List<Long>,
    ): SmartAppendReport {
        if (asns.isEmpty()) {
            return SmartAppendReport(slug, name, 0, 0, 0, error = "no ASN configured")
        }
        val collected = mutableListOf<String>()
        var error: String? = null
        for (asn in asns) {
            try {
                val resp = bgpView.prefixes(asn)
                if (!resp.status.equals("ok", ignoreCase = true)) continue
                collected += resp.data.ipv4Prefixes.map { it.prefix }
                collected += resp.data.ipv6Prefixes.map { it.prefix }
            } catch (t: Throwable) {
                log.warn("BGPView failed for ASN {} ({}): {}", asn, name, t.message)
                error = error ?: (t.message ?: t::class.simpleName ?: "unknown error")
            }
        }
        if (collected.isEmpty()) {
            return SmartAppendReport(slug, name, 0, 0, 0, error = error)
        }

        val existing = q.cidrsForProvider(providerId).executeAsList()
        val outcome = smartAppend(existing, collected)

        if (outcome.added.isNotEmpty()) {
            val now = System.currentTimeMillis()
            db.transaction {
                for (cidr in outcome.added) q.insertDiscovery(slug, cidr, now)
            }
        }

        return SmartAppendReport(
            providerSlug = slug,
            providerName = name,
            added = outcome.added.size,
            skipped = outcome.skipped,
            superseded = outcome.superseded.size,
            error = error,
        )
    }

    /**
     * Bunny CDN sync path. BGPView returns essentially nothing for the
     * Bunny ASN (their edges live on Datacamp parent networks) so we
     * call the public edge-server list directly and fall back to a
     * curated hardcoded list if the API is unreachable.
     */
    private suspend fun syncBunny(
        providerId: Long,
        slug: String,
        name: String,
        messages: Messages,
        onLog: suspend (String) -> Unit,
    ): SmartAppendReport {
        onLog(messages.bunnyLoadingViaApi)
        val result = try {
            bunny.fetchAll()
        } catch (t: Throwable) {
            log.warn("Bunny.fetchAll() threw: {}", t.message)
            BunnyRangeProvider.Result(
                cidrs = BunnyRangeProvider.HARDCODED_RANGES,
                source = BunnyRangeProvider.Source.HARDCODED_FALLBACK,
                edgeIpCount = 0,
                fallbackReason = t.message ?: t::class.simpleName ?: "unknown error",
            )
        }

        when (result.source) {
            BunnyRangeProvider.Source.DIRECT_API,
            BunnyRangeProvider.Source.MIXED -> {
                onLog(messages.bunnyReceivedGrouped(result.edgeIpCount, result.cidrs.size))
            }
            BunnyRangeProvider.Source.HARDCODED_FALLBACK -> {
                onLog(messages.bunnyFallbackUsed(result.cidrs.size))
            }
        }

        if (result.cidrs.isEmpty()) {
            // Defence in depth — BunnyRangeProvider never returns empty,
            // but if it ever did we surface the hardcoded list.
            return SmartAppendReport(slug, name, 0, 0, 0, error = "Bunny: empty CIDR set")
        }

        val existing = q.cidrsForProvider(providerId).executeAsList()
        val outcome = smartAppend(existing, result.cidrs)

        if (outcome.added.isNotEmpty()) {
            val now = System.currentTimeMillis()
            db.transaction {
                for (cidr in outcome.added) q.insertDiscovery(slug, cidr, now)
            }
        }

        return SmartAppendReport(
            providerSlug = slug,
            providerName = name,
            added = outcome.added.size,
            skipped = outcome.skipped,
            superseded = outcome.superseded.size,
            error = result.fallbackReason?.let { "Bunny fallback: $it" },
        )
    }

    private fun collapseCidrs(cidrs: List<String>): List<String> {
        val parsed = cidrs.mapNotNull { Cidr.parse(it) }.distinct()
        val keep = mutableListOf<Cidr>()
        for (net in parsed.sortedBy { it.prefixLen }) {
            if (keep.any { it.version == net.version && net.subnetOf(it) }) continue
            keep += net
        }
        return keep.map { it.canonical }
    }
}
