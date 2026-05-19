package ai.xrav.xravscan.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val DarkColorScheme = darkColorScheme(
    primary = SkyBlue,
    onPrimary = DeepMidnight900,
    secondary = Violet,
    onSecondary = DeepMidnight900,
    tertiary = Mint,
    onTertiary = DeepMidnight900,
    background = DeepMidnight900,
    onBackground = TextPrimary,
    surface = DeepMidnight800,
    onSurface = TextPrimary,
    surfaceVariant = DeepMidnight700,
    onSurfaceVariant = TextSecondary,
    error = Danger,
    onError = DeepMidnight900,
)

@Composable
fun XRavScanTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // X-RavScan brand palette is fixed (always dark) — disable dynamic
    // colour by default, but allow opt-in for users on Android 12+.
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            dynamicDarkColorScheme(context)
        else -> DarkColorScheme
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = DeepMidnight900.toArgb()
            window.navigationBarColor = DeepMidnight900.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content,
    )
}
