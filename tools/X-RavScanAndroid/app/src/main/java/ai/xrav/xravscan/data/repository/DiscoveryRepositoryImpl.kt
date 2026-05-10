package ai.xrav.xravscan.data.repository

import ai.xrav.xravscan.data.local.XRavScanDatabase
import ai.xrav.xravscan.data.local.dao.CidrRangeDao
import ai.xrav.xravscan.data.local.dao.DiscoveryDao
import ai.xrav.xravscan.data.local.dao.ProviderDao
import ai.xrav.xravscan.data.local.entity.CidrRangeEntity
import ai.xrav.xravscan.data.local.entity.DiscoveryEntity
import ai.xrav.xravscan.data.remote.api.BgpViewService
import ai.xrav.xravscan.domain.model.Discovery
import ai.xrav.xravscan.domain.model.SmartAppendReport
import ai.xrav.xravscan.domain.repository.DiscoveryRepository
import ai.xrav.xravscan.domain.util.Cidr
import ai.xrav.xravscan.domain.util.smartAppend
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
    private val networkMonitor: NetworkMonitor,
) : DiscoveryRepository {

    override fun observeAll(): Flow<List<Discovery>> =
        discoveryDao.observeAll().map { rows -> rows.map { it.toDomain() } }

    override suspend fun runSmartAppend(onLog: suspend (String) -> Unit): List<SmartAppendReport> =
        withContext(Dispatchers.IO) {
            val net = networkMonitor.state.value
            if (net.isVpn) {
                onLog("VPN active — Smart Append paused. Disable the VPN to continue.")
                return@withContext emptyList()
            }
            if (!net.available) {
                onLog("Offline — Smart Append paused until connectivity returns.")
                return@withContext emptyList()
            }

            val providers = providerDao.observeAllWithCount().firstValue()
                .filter { it.enabled }
            if (providers.isEmpty()) {
                onLog("No enabled providers — nothing to do")
                return@withContext emptyList()
            }
            onLog("Smart Append starting — ${providers.size} providers")

            coroutineScope {
                providers.map { row ->
                    async {
                        val asns = row.asnsCsv.split(',')
                            .mapNotNull { it.trim().toLongOrNull() }
                        val report = syncProvider(row.slug, row.name, asns)
                        onLog(report.iosLine())
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

    private suspend fun syncProvider(
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
            return SmartAppendReport(slug, name, 0, 0, 0, error = error)
        }

        val existingRanges = cidrRangeDao.rangesForProvider(slug).map { it.cidr }
        val outcome = smartAppend(existingRanges, collected)

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

        return SmartAppendReport(
            providerSlug = slug,
            providerName = name,
            added = outcome.added.size,
            skipped = outcome.skipped,
            superseded = outcome.superseded.size,
            error = error,
        )
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
