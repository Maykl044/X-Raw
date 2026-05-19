package ai.xrav.xravscan.ui.components

import ai.xrav.xravscan.ui.theme.DeepMidnight
import ai.xrav.xravscan.ui.theme.Mint
import ai.xrav.xravscan.ui.theme.SkyBlue
import ai.xrav.xravscan.ui.theme.Violet
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Drifting neon "blobs" on a Deep Midnight base. Pure Compose canvas — no Skia
 * shaders required, so it ships everywhere Compose Desktop runs.
 */
@Composable
fun AnimatedBackground(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "neon-bg")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 18_000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "phase",
    )

    Canvas(modifier = modifier.fillMaxSize().background(DeepMidnight)) {
        val w = size.width
        val h = size.height
        val blobs = listOf(
            BlobDef(SkyBlue, 0.20f, 0.30f, 0.85f, 0.0f),
            BlobDef(Violet, 0.78f, 0.22f, 0.7f, 1.6f),
            BlobDef(Mint, 0.65f, 0.78f, 0.65f, 3.1f),
            BlobDef(SkyBlue.copy(alpha = 0.55f), 0.15f, 0.78f, 0.55f, 4.7f),
        )
        for (b in blobs) {
            val cx = (b.cx + 0.05f * cos(phase + b.offset)) * w
            val cy = (b.cy + 0.05f * sin(phase + b.offset)) * h
            val radius = b.radius * minOf(w, h)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(b.color.copy(alpha = 0.28f), Color.Transparent),
                    center = Offset(cx, cy),
                    radius = radius,
                ),
                radius = radius,
                center = Offset(cx, cy),
            )
        }
    }
}

private data class BlobDef(
    val color: Color,
    val cx: Float,
    val cy: Float,
    val radius: Float,
    val offset: Float,
)
