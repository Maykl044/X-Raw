package ai.xrav.xravscan

import ai.xrav.xravscan.navigation.AppRoot
import ai.xrav.xravscan.ui.components.GlobalErrorScreen
import ai.xrav.xravscan.ui.localization.LocalizationManager
import ai.xrav.xravscan.ui.localization.ProvideAppLocale
import ai.xrav.xravscan.ui.network.NetworkMonitor
import ai.xrav.xravscan.ui.network.ProvideNetworkState
import ai.xrav.xravscan.ui.theme.XRavScanTheme
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var localizationManager: LocalizationManager
    @Inject lateinit var networkMonitor: NetworkMonitor

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Apply persisted locale before Compose composes so the very first
        // frame already renders in the correct language.
        localizationManager.applyPersisted()

        setContent {
            ProvideAppLocale(localizationManager) {
                ProvideNetworkState(networkMonitor) {
                    XRavScanTheme {
                        Surface(
                            modifier = Modifier.fillMaxSize(),
                            color = androidx.compose.ui.graphics.Color.Transparent,
                        ) {
                            SafeRoot()
                        }
                    }
                }
            }
        }
    }
}

/**
 * Wraps the navigation graph with a try/catch so that a crash inside any
 * Composable surfaces a glassmorphic error card instead of killing the
 * whole window. The captured throwable is logged via the default Android
 * uncaught handler that ``XRavScanApp`` installed.
 */
@Composable
private fun SafeRoot() {
    var fatal: Throwable? by remember { mutableStateOf(null) }
    if (fatal != null) {
        GlobalErrorScreen(throwable = fatal!!, onDismiss = { fatal = null })
        return
    }
    runCatching {
        AppRoot()
    }.onFailure {
        fatal = it
    }
}
