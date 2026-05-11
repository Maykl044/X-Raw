package ai.xrav.xravscan.data.repository

import ai.xrav.xravscan.data.local.XRavScanDatabase
import ai.xrav.xravscan.data.local.dao.CidrRangeDao
import ai.xrav.xravscan.data.local.dao.DiscoveryDao
import ai.xrav.xravscan.data.local.dao.ProviderDao
import ai.xrav.xravscan.data.local.entity.CidrRangeEntity
import ai.xrav.xravscan.data.local.entity.DiscoveryEntity
import ai.xrav.xravscan.data.remote.api.BgpViewService
import ai.xrav.xravscan.data.remote.direct.AkamaiDirectProvider
import ai.xrav.xravscan.data.remote.direct.AwsDirectProvider
import ai.xrav.xravscan.data.remote.direct.BunnyDirectProvider
import ai.xrav.xravscan.data.remote.direct.CloudflareDirectProvider
import ai.xrav.xravscan.data.remote.direct.DirectRangeProvider
import ai.xrav.xravscan.data.remote.direct.FastlyDirectProvider
import ai.xrav.xravscan.data.remote.direct.GoogleCloudDirectProvider
import ai.xrav.xravscan.domain.model.Discovery
import ai.xrav.xravscan.domain.model.SmartAppendReport
import ai.xrav.xravscan.domain.repository.DiscoveryRepository
import ai.xrav.xravscan.domain.repository.SmartAppendMessages
import ai.xrav.xravscan.domain.util.Cidr
import ai.xrav.xravscan.domain.util.DedupPipeline
import ai.xrav.xravscan.ui.network.NetworkMonitor
import android.util.Log
import androidx.room.withTransaction
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

@Singleton
class DiscoveryRepositoryImpl @Inject constructor(
    private val db: XRavScanDatabase,
    private val providerDao: ProviderDao,
    private val cidrRangeDao: CidrRangeDao,
    private val discoveryDao: DiscoveryDao,
    private val bgpView: BgpViewService,
    private val cloudflareDirect: CloudflareDirectProvider,
    private val googleCloudDirect: GoogleCloudDirectProvider,
    private val awsDirect: AwsDirectProvider,
    private val fastlyDirect: FastlyDirectProvider,
    private val akamaiDirect: AkamaiDirectProvider,
    private val bunnyDirect: BunnyDirectProvider,
    private val networkMonitor: NetworkMonitor,
) : DiscoveryRepository {

    /**
     * Lookup table from provider slug → which [DirectRangeProvider]
     * to call instead of BGPView. Built lazily because Hilt has to
     * construct everything before this map can reference them.
     *
     * AWS / CloudFront share the same upstream JSON but pull
     * different `service` rows.
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

    override fun observeAll(): Flow<List<Discovery>> =
        discoveryDao.observeAll().map { rows -> rows.map { it.toDomain() } }

    override suspend fun runSmartAppend(
        messages: SmartAppendMessages,
        onLog: suspend (String) -> Unit,
    ): List<SmartAppendReport> =
        withContext(Dispatchers.IO) {
            // NB: we deliberately do NOT pause Smart Append for a live VPN.
            // Update traffic goes through [UpdateClientFactory] which uses
            // DNS-over-HTTPS and, when possible, pins to a non-VPN network
            // so the user can keep their VPN active and still refresh
            // databases. Only Quick / Full scanning pauses on VPN.
            val net = networkMonitor.state.value
            if (!net.available) {
                onLog(messages.offlinePaused)
                return@withContext emptyList()
            }
            if (net.isVpn) {
                onLog(messages.vpnActiveNote)
            }

            val providers = providerDao.observeAllWithCount().firstValue()
                .filter { it.enabled }
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
                            syncDirect(direct, row.slug, row.name, messages, onLog)
                        } else {
                            val asns = row.asnsCsv.split(',')
                                .mapNotNull { it.trim().toLongOrNull() }
                            syncProvider(row.slug, row.name, asns, messages, onLog)
                        }
                        report
                    }
                }.awaitAll()
            }
        }

    override suspend fun cleanAndOptimize(onLog: suspend (String) -> Unit): Int =
        withContext(Dispatchers.IO) {
            val providers = providerDao.observeAllWithCount().firstValue()
            var totalRemoved = 0
            for (p in providers) {
                val before = cidrRangeDao.rangesForProvider(p.slug)
                val collapsed = collapseCidrs(before.map { it.cidr })
                val removed = before.size - collapsed.size
                if (removed <= 0) continue
                db.withTransaction {
                    cidrRangeDao.deleteForProvider(p.slug)
                    cidrRangeDao.insertAll(
                        collapsed.map {
                            CidrRangeEntity(
                                providerSlug = p.slug,
                                cidr = it,
                                source = "clean_optimize",
                            )
                        },
                    )
                }
                totalRemoved += removed
                onLog("${p.name}: collapsed ${before.size} → ${collapsed.size} ($removed removed)")
            }
            if (totalRemoved == 0) {
                onLog("Clean & Optimize — nothing to collapse")
            } else {
                onLog("Clean & Optimize done — $totalRemoved redundant ranges removed")
            }
            totalRemoved
        }

    override suspend fun applyDiscovery(id: Long) {
        withContext(Dispatchers.IO) {
            val pending = discoveryDao.observeAll().firstValue().firstOrNull { it.id == id }
                ?: return@withContext
            db.withTransaction {
                cidrRangeDao.insertAll(
                    listOf(
                        CidrRangeEntity(
                            providerSlug = pending.providerSlug,
                            cidr = pending.cidr,
                            source = "discovery:${pending.sourceApi}",
                        ),
                    ),
                )
                discoveryDao.setApplied(id, true)
            }
        }
    }

    override suspend fun applyAllForProvider(providerSlug: String): Int =
        withContext(Dispatchers.IO) {
            val pending = discoveryDao.observeAll().firstValue()
                .filter { it.providerSlug == providerSlug && !it.applied }
            if (pending.isEmpty()) return@withContext 0
            db.withTransaction {
                cidrRangeDao.insertAll(
                    pending.map {
                        CidrRangeEntity(
                            providerSlug = it.providerSlug,
                            cidr = it.cidr,
                            source = "discovery:${it.sourceApi}",
                        )
                    },
                )
                pending.forEach { discoveryDao.setApplied(it.id, true) }
            }
            pending.size
        }

    override suspend fun dismiss(id: Long) {
        withContext(Dispatchers.IO) { discoveryDao.deleteById(id) }
    }

    override suspend fun dismissAllPending() {
        withContext(Dispatchers.IO) { discoveryDao.deletePending() }
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
        slug: String,
        name: String,
        messages: SmartAppendMessages,
        onLog: suspend (String) -> Unit,
    ): SmartAppendReport {
        if (slug.equals("bunny", ignoreCase = true)) {
            onLog(messages.bunnyLoading)
        }

        val result = runCatching { provider.fetch() }.getOrElse { t ->
            Log.w(TAG, "Direct fetch failed for $slug", t)
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

        val existing = cidrRangeDao.rangesForProvider(slug).map { it.cidr }
        val outcome = DedupPipeline.process(result.cidrs, existing)

        if (outcome.added.isNotEmpty()) {
            val now = System.currentTimeMillis()
            val sourceTag = when (result.source) {
                DirectRangeProvider.Source.DIRECT_API -> "${slug}_api"
                DirectRangeProvider.Source.HARDCODED_FALLBACK -> "${slug}_fallback"
                DirectRangeProvider.Source.MIXED -> "${slug}_mixed"
            }
            discoveryDao.insertAll(
                outcome.added.map { cidr ->
                    DiscoveryEntity(
                        providerSlug = slug,
                        asn = null,
                        cidr = cidr,
                        sourceApi = sourceTag,
                        foundAt = now,
                        applied = false,
                    )
                },
            )
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
        slug: String,
        name: String,
        asns: List<Long>,
        messages: SmartAppendMessages,
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
                collected += resp.data.ipv4_prefixes.map { it.prefix }
                collected += resp.data.ipv6_prefixes.map { it.prefix }
            } catch (t: java.net.UnknownHostException) {
                Log.w(TAG, "BGPView DNS lookup failed for ASN $asn ($name)", t)
                error = error ?: "DNS lookup failed (api.bgpview.io)"
            } catch (t: java.net.SocketTimeoutException) {
                Log.w(TAG, "BGPView timed out for ASN $asn ($name)", t)
                error = error ?: "API timed out — please retry"
            } catch (t: java.io.IOException) {
                Log.w(TAG, "BGPView I/O error for ASN $asn ($name)", t)
                error = error ?: (t.message ?: "I/O error")
            } catch (t: Throwable) {
                Log.w(TAG, "BGPView failed for ASN $asn ($name)", t)
                error = error ?: (t.message ?: t::class.simpleName ?: "unknown error")
            }
        }
        if (collected.isEmpty()) {
            onLog(error?.let { messages.errorLine(name, it) } ?: messages.noPrefixes(name))
            return SmartAppendReport(slug, name, 0, 0, 0, error = error)
        }

        val existing = cidrRangeDao.rangesForProvider(slug).map { it.cidr }
        val outcome = DedupPipeline.process(collected, existing)

        if (outcome.added.isNotEmpty()) {
            val now = System.currentTimeMillis()
            discoveryDao.insertAll(
                outcome.added.map { cidr ->
                    DiscoveryEntity(
                        providerSlug = slug,
                        asn = asns.firstOrNull(),
                        cidr = cidr,
                        sourceApi = "bgpview",
                        foundAt = now,
                        applied = false,
                    )
                },
            )
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
        messages: SmartAppendMessages,
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
        // For the first pass we de-duplicate and drop subnets that are
        // covered by larger prefixes — Route-Summarisation lite. Full
        // collapse_addresses-style merging can come in a follow-up.
        val parsed = cidrs.mapNotNull { Cidr.parse(it) }.distinct()
        val keep = mutableListOf<Cidr>()
        for (net in parsed.sortedBy { it.prefixLen }) {
            if (keep.any { it.version == net.version && net.subnetOf(it) }) continue
            keep += net
        }
        return keep.map { it.canonical }
    }

    private suspend fun <T> Flow<T>.firstValue(): T = first()

    private fun DiscoveryEntity.toDomain(): Discovery = Discovery(
        id = id,
        providerSlug = providerSlug,
        asn = asn,
        cidr = cidr,
        sourceApi = sourceApi,
        foundAtMillis = foundAt,
        applied = applied,
    )

    companion object {
        private const val TAG = "XRavScan.Discovery"
    }
}
