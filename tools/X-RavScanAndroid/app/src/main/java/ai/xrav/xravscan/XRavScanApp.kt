package ai.xrav.xravscan

import android.app.Application
import android.util.Log
import dagger.hilt.android.HiltAndroidApp

/**
 * Hilt entry point. Installed as ``android:name`` in the manifest.
 *
 * Also installs a default uncaught-exception handler that *logs* the
 * crash to logcat instead of letting Android show the system crash
 * dialog (which on some launchers looks like a silent close).
 */
@HiltAndroidApp
class XRavScanApp : Application() {

    override fun onCreate() {
        super.onCreate()
        installFallbackUncaughtHandler()
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
