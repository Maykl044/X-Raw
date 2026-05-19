package ai.xrav.xravscan

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
import ai.xrav.xravscan.navigation.AppRoot
import ai.xrav.xravscan.ui.components.GlobalErrorScreen
import ai.xrav.xravscan.ui.theme.XRavScanTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
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
