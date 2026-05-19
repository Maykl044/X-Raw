package ai.xrav.xravscan.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val DeepMidnightScheme = darkColorScheme(
    primary = SkyBlue,
    onPrimary = DeepMidnight,
    secondary = Violet,
    onSecondary = DeepMidnight,
    tertiary = Mint,
    onTertiary = DeepMidnight,
    background = DeepMidnight,
    onBackground = TextPrimary,
    surface = MidnightSlate,
    onSurface = TextPrimary,
    surfaceVariant = MidnightSlate,
    onSurfaceVariant = TextSecondary,
    error = Coral,
    onError = DeepMidnight,
    outline = GlassStroke,
)

@Composable
fun XRavScanDesktopTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DeepMidnightScheme,
        typography = AppTypography,
        content = content,
    )
}
