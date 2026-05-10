package ai.xrav.xravscan

import android.app.Application
import android.util.Log
import ai.xrav.xravscan.data.seed.ProviderSeeder
import ai.xrav.xravscan.util.LocaleManager
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Application entry point. Hilt installs DI here; we additionally:
 *
 * 1. Install a default uncaught-exception handler that logs every crash to
 *    logcat under the ``XRavScan`` tag *before* the system handler runs —
 *    so even a fatal Kotlin exception leaves a breadcrumb in ``adb logcat``.
 * 2. Spin up the bundled provider seeder on a background scope so the very
 *    first launch populates Room without blocking the Compose draw thread.
 */
@HiltAndroidApp
class XRavScanApp : Application() {

    @Inject lateinit var providerSeeder: ProviderSeeder
    @Inject lateinit var localeManager: LocaleManager

    private val appScope = CoroutineScope(
        SupervisorJob() +
            Dispatchers.IO +
            CoroutineExceptionHandler { _, t -> Log.e(TAG, "appScope coroutine failed", t) },
    )

    override fun onCreate() {
        super.onCreate()
        installFallbackUncaughtHandler()
        runCatching { localeManager.applyPersisted() }
            .onFailure { Log.e(TAG, "applyPersisted locale failed", it) }
        appScope.launch {
            try {
                providerSeeder.ensureSeeded()
            } catch (t: Throwable) {
                Log.e(TAG, "seeder threw", t)
            }
        }
    }

    private fun installFallbackUncaughtHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            Log.e(TAG, "uncaught exception in thread ${t.name}", e)
            previous?.uncaughtException(t, e)
        }
    }

    companion object {
        private const val TAG = "XRavScan"
    }
}
