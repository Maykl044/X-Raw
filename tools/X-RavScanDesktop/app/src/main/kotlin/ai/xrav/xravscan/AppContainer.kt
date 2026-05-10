package ai.xrav.xravscan

import ai.xrav.xravscan.data.export.Exporter
import ai.xrav.xravscan.data.local.DatabaseFactory
import ai.xrav.xravscan.data.remote.BgpViewService
import ai.xrav.xravscan.data.repository.DiscoveryRepository
import ai.xrav.xravscan.data.repository.ProviderRepository
import ai.xrav.xravscan.data.repository.ScanRepository
import ai.xrav.xravscan.data.seed.ProviderSeeder
import ai.xrav.xravscan.db.XRavScanDb
import org.slf4j.LoggerFactory

/**
 * Simple service-locator. The desktop target is a single JVM process so we
 * don't need Hilt/Koin here — a hand-rolled container is enough through
 * Phases D2-D5.
 */
class AppContainer private constructor(
    val db: XRavScanDb,
    val bgpViewService: BgpViewService,
    val providerRepository: ProviderRepository,
    val discoveryRepository: DiscoveryRepository,
    val scanRepository: ScanRepository,
    val exporter: Exporter,
) {
    companion object {
        @Volatile private var instance: AppContainer? = null
        private val log = LoggerFactory.getLogger("XRavScan.Container")

        fun get(): AppContainer {
            instance?.let { return it }
            return synchronized(this) {
                instance ?: build().also { instance = it }
            }
        }

        private fun build(): AppContainer {
            log.info("Initialising database at {}", DatabaseFactory.resolveDbFile())
            val db = DatabaseFactory.create()
            val report = ProviderSeeder(db).seedIfNeeded()
            log.info(
                "Seed report — skipped={}, providers={}, cidrs={}",
                report.skipped, report.providers, report.cidrs,
            )
            val bgpView = BgpViewService()
            return AppContainer(
                db = db,
                bgpViewService = bgpView,
                providerRepository = ProviderRepository(db),
                discoveryRepository = DiscoveryRepository(db, bgpView),
                scanRepository = ScanRepository(db),
                exporter = Exporter(db),
            )
        }
    }
}
