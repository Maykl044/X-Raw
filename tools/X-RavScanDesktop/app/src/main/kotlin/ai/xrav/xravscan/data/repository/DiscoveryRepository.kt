package ai.xrav.xravscan.data.repository

import ai.xrav.xravscan.data.remote.BgpViewService
import ai.xrav.xravscan.data.remote.BunnyRangeProvider
import ai.xrav.xravscan.data.remote.direct.AkamaiDirectProvider
import ai.xrav.xravscan.data.remote.direct.AwsDirectProvider
import ai.xrav.xravscan.data.remote.direct.BunnyDirectProvider
import ai.xrav.xravscan.data.remote.direct.CloudflareDirectProvider
import ai.xrav.xravscan.data.remote.direct.DirectRangeProvider
import ai.xrav.xravscan.data.remote.direct.FastlyDirectProvider
import ai.xrav.xravscan.data.remote.direct.GoogleCloudDirectProvider
import ai.xrav.xravscan.db.XRavScanDb
import ai.xrav.xravscan.domain.model.Discovery
import ai.xrav.xravscan.domain.model.SmartAppendReport
import ai.xrav.xravscan.domain.util.Cidr
import ai.xrav.xravscan.domain.util.DedupPipeline
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
 * Phase H — Smart Append now routes each provider to a vendor-specific
 * [DirectRangeProvider] (Cloudflare ips-v4, AWS ip-ranges.json, GCP
 * cloud.json, Fastly public-ip-list, Akamai RADB snapshot, Bunny direct
 * API) whenever the slug matches; everything else still falls back to
 * the legacy BGPView ASN walk. Every batch then goes through
 * [DedupPipeline] which normalises canonical form, drops invalid CIDRs,
 * counts duplicates, and yields a single structured log line per
 * provider in the form `Name: found N, duplicates M, added K`.
 *
 * All public APIs marshal the underlying calls onto [Dispatchers.IO]
 * (network + SQLite) so the caller can stay on whatever scope it likes.
 */
class DiscoveryRepository(
    private val db: XRavScanDb,
    private val bgpView: BgpViewService,
    private val bunny: BunnyRangeProvider,
    private val cloudflareDirect: CloudflareDirectProvider = CloudflareDirectProvider(),
    private val googleCloudDirect: GoogleCloudDirectProvider = GoogleCloudDirectProvider(),
    private val awsDirect: AwsDirectProvider = AwsDirectProvider(),
    private val fastlyDirect: FastlyDirectProvider = FastlyDirectProvider(),
    private val akamaiDirect: AkamaiDirectProvider = AkamaiDirectProvider(),
    private val bunnyDirect: BunnyDirectProvider = BunnyDirectProvider(bunny),
) {
    private val log = LoggerFactory.getLogger("XRavScan.Discovery")
    private val q get() = db.xRavScanDbQueries

    /**
     * Lookup table from provider slug → which [DirectRangeProvider]
     * to call instead of BGPView. AWS and CloudFront share the same
     * upstream JSON but pull different `service` rows.
     */
    private val directProviders: Map<String, DirectRangeProvider> by lazy {
        mapOf(
            "cloudflare" to cloudflareDirect,
            "gcp" to googleCloudDirect,
            "aws" to awsDirect.forService("aws", "AMAZON"),
            "cloudfront" to awsDirect.forService("cloudfront", "CLOUDFRONT"),
            "fastly" to fastlyDirect,
            "akamai" to akamaiDirect,
            "bunny" to bunnyDirect,
        )
    }

    /**
     * Localizable status messages used during a Smart Append run. The
     * repository emits semantic events and the UI supplies a [Messages]
     * instance built from the active `AppStrings` so log lines render in
     * the user's selected language. Defaults are English so any caller
     * that doesn't care about i18n keeps working unchanged.
     */
    data class Messages(
        val noProviders: String = "No enabled providers — nothing to do",
        val starting: (Int) -> String = { n -> "Smart Append starting — $n provider(s)" },
        val bunnyLoadingViaApi: String = "Loading Bunny CDN ranges via direct API…",
        val bunnyReceivedGrouped: (Int, Int) -> String = { ips, cidrs ->
            "Bunny: $ips IPs received, grouped into $cidrs CIDRs"
        },
        val bunnyFallbackUsed: (Int) -> String = { n ->
            "Bunny CDN: fell back to built-in list ($n CIDR)"
        },
        val noAsnConfigured: (String) -> String = { name -> "$name: no ASN configured" },
        val errorLine: (String, String) -> String =
            { name, err -> "$name: error — $err" },
        val noPrefixes: (String) -> String = { name -> "$name: no prefixes" },
        val fallback: (String, Int) -> String =
            { name, count -> "$name: fallback to built-in list ($count CIDR)" },
        val fallbackWithReason: (String, Int, String) -> String =
            { name, count, reason -> "$name: fallback to built-in list ($count CIDR) — $reason" },
        val providerLine: (String, Int, Int, Int) -> String =
            { name, found, dup, added -> "$name: found $found, duplicates $dup, added $added" },
        val providerLineWithInvalid: (String, Int, Int, Int, Int) -> String =
            { name, found, dup, added, invalid ->
                "$name: found $found, duplicates $dup, added $added · $invalid invalid"
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
     * Run Smart Append for every enabled provider — in parallel, with a
     * dedicated path per provider:
     *
     *  * `cloudflare`, `gcp`, `aws`, `cloudfront`, `fastly`, `akamai`,
     *    `bunny` → vendor-specific [DirectRangeProvider]
     *  * any other slug → legacy BGPView ASN walk
     *
     * Each call produces one terse iOS-style log line via [onLog]:
     * `Cloudflare: found 22, duplicates 18, added 4`.
     */
    suspend fun runSmartAppend(
        messages: Messages = Messages(),
        onLog: suspend (String) -> Unit,
    ): List<SmartAppendReport> =
        withContext(Dispatchers.IO) {
            val providers = q.enabledProviders().executeAsList()
            if (providers.isEmpty()) {
                onLog(messages.noProviders)
                return@withContext emptyList()
            }
            onLog(messages.starting(providers.size))

            coroutineScope {
                providers.map { row ->
                    async {
                        val direct = directProviders[row.slug.lowercase()]
                        val report = if (direct != null) {
                            syncDirect(direct, row.id, row.slug, row.name, messages, onLog)
                        } else {
                            val asns = row.asns.split(',')
                                .mapNotNull { it.trim().toLongOrNull() }
                            syncProvider(row.id, row.slug, row.name, asns, messages, onLog)
                        }
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

    /**
     * Generic direct-vendor Smart-Append path used by Cloudflare, GCP,
     * AWS, CloudFront, Fastly, Akamai, and Bunny. Each vendor's
     * [DirectRangeProvider] returns a single [DirectRangeProvider.Result]
     * + a per-vendor source tag; we always run the output through
     * [DedupPipeline] before inserting so:
     *
     *   * invalid CIDRs are dropped (and counted)
     *   * duplicates (same canonical network already in the table) are
     *     dropped (and counted)
     *   * the user sees ONE structured log line:
     *     "Cloudflare: found 22, duplicates 18, added 4"
     */
    private suspend fun syncDirect(
        provider: DirectRangeProvider,
        providerId: Long,
        slug: String,
        name: String,
        messages: Messages,
        onLog: suspend (String) -> Unit,
    ): SmartAppendReport {
        if (slug.equals("bunny", ignoreCase = true)) {
            onLog(messages.bunnyLoadingViaApi)
        }

        val result = runCatching { provider.fetch() }.getOrElse { t ->
            log.warn("Direct fetch failed for {}: {}", slug, t.message)
            DirectRangeProvider.Result.fallback(
                cidrs = emptyList(),
                reason = t.message ?: t::class.simpleName ?: "unknown error",
            )
        }

        if (result.source == DirectRangeProvider.Source.HARDCODED_FALLBACK) {
            val reason = result.errorMessage
            onLog(
                if (reason.isNullOrBlank()) {
                    messages.fallback(name, result.cidrs.size)
                } else {
                    messages.fallbackWithReason(name, result.cidrs.size, reason)
                },
            )
        }

        val existing = q.cidrsForProvider(providerId).executeAsList()
        val outcome = DedupPipeline.process(result.cidrs, existing)

        if (outcome.added.isNotEmpty()) {
            val now = System.currentTimeMillis()
            db.transaction {
                for (cidr in outcome.added) q.insertDiscovery(slug, cidr, now)
            }
        }

        onLog(formatFoundDuplicatesAdded(name, outcome, messages))

        return SmartAppendReport(
            providerSlug = slug,
            providerName = name,
            added = outcome.added.size,
            skipped = outcome.duplicates,
            superseded = 0,
            error = if (result.cidrs.isEmpty()) result.errorMessage else null,
        )
    }

    private suspend fun syncProvider(
        providerId: Long,
        slug: String,
        name: String,
        asns: List<Long>,
        messages: Messages,
        onLog: suspend (String) -> Unit,
    ): SmartAppendReport {
        if (asns.isEmpty()) {
            onLog(messages.noAsnConfigured(name))
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
            onLog(error?.let { messages.errorLine(name, it) } ?: messages.noPrefixes(name))
            return SmartAppendReport(slug, name, 0, 0, 0, error = error)
        }

        val existing = q.cidrsForProvider(providerId).executeAsList()
        val outcome = DedupPipeline.process(collected, existing)

        if (outcome.added.isNotEmpty()) {
            val now = System.currentTimeMillis()
            db.transaction {
                for (cidr in outcome.added) q.insertDiscovery(slug, cidr, now)
            }
        }

        onLog(formatFoundDuplicatesAdded(name, outcome, messages))

        return SmartAppendReport(
            providerSlug = slug,
            providerName = name,
            added = outcome.added.size,
            skipped = outcome.duplicates,
            superseded = 0,
            error = error,
        )
    }

    /** Phase H structured per-provider summary line. */
    private fun formatFoundDuplicatesAdded(
        name: String,
        outcome: DedupPipeline.Outcome,
        messages: Messages,
    ): String =
        if (outcome.invalid > 0) {
            messages.providerLineWithInvalid(
                name,
                outcome.found,
                outcome.duplicates,
                outcome.added.size,
                outcome.invalid,
            )
        } else {
            messages.providerLine(name, outcome.found, outcome.duplicates, outcome.added.size)
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
