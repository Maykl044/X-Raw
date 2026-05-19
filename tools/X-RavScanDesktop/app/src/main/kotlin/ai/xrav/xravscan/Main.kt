package ai.xrav.xravscan

import ai.xrav.xravscan.ui.AppRoot
import ai.xrav.xravscan.ui.localization.ProvideAppLocale
import ai.xrav.xravscan.ui.network.ProvideNetworkState
import ai.xrav.xravscan.ui.theme.XRavScanDesktopTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("XRavScan")

fun main() {
    Thread.setDefaultUncaughtExceptionHandler { t, e ->
        log.error("Uncaught exception on thread ${t.name}", e)
    }
    runCatching { AppContainer.get() }
        .onFailure { log.error("AppContainer init failed", it) }
    application {
        Window(
            onCloseRequest = ::exitApplication,
            title = "X-RavScan",
            state = rememberWindowState(size = DpSize(1180.dp, 760.dp)),
            undecorated = false,
        ) {
            ProvideAppLocale {
                ProvideNetworkState(monitor = AppContainer.get().networkMonitor) {
                    XRavScanDesktopTheme {
                        AppShell()
                    }
                }
            }
        }
    }
}

@Composable
private fun AppShell() {
    var lastError by remember { mutableStateOf<Throwable?>(null) }
    val crash = lastError
    if (crash != null) {
        ai.xrav.xravscan.ui.components.GlobalErrorScreen(
            throwable = crash,
            onDismiss = { lastError = null },
            modifier = Modifier.fillMaxSize(),
        )
    } else {
        runCatching { AppRoot() }.onFailure {
            log.error("Top-level Compose tree threw", it)
            lastError = it
        }
    }
}
