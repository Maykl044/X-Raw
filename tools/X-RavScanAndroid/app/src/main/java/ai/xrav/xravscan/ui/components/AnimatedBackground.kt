package ai.xrav.xravscan.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import ai.xrav.xravscan.ui.theme.Mint
import ai.xrav.xravscan.ui.theme.Pink
import ai.xrav.xravscan.ui.theme.SkyBlue
import ai.xrav.xravscan.ui.theme.Violet
import ai.xrav.xravscan.ui.theme.verticalDeepMidnight
import kotlin.math.cos
import kotlin.math.sin

/**
 * Deep Midnight gradient with three slowly-orbiting neon "blobs" to give
 * the background some life. Drawn purely with Compose Canvas — no GPU
 * blur, no extra dependencies.
 */
@Composable
fun AnimatedBackground(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val transition = rememberInfiniteTransition(label = "bg")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 18_000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "phase",
    )

    Box(modifier = modifier.background(verticalDeepMidnight())) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            drawBlob(SkyBlue.copy(alpha = 0.18f), centerOf(w, h, 0.18f, 0.20f, phase, 0.0), w * 0.55f)
            drawBlob(Violet.copy(alpha = 0.20f), centerOf(w, h, 0.78f, 0.30f, phase, 0.33), w * 0.50f)
            drawBlob(Mint.copy(alpha = 0.14f), centerOf(w, h, 0.30f, 0.85f, phase, 0.66), w * 0.65f)
            drawBlob(Pink.copy(alpha = 0.10f), centerOf(w, h, 0.85f, 0.85f, phase, 0.5), w * 0.40f)
        }
        content()
    }
}

private fun centerOf(w: Float, h: Float, x: Float, y: Float, phase: Float, offset: Double): Offset {
    val angle = ((phase + offset.toFloat()) * 2 * Math.PI).toFloat()
    val rx = w * 0.06f
    val ry = h * 0.04f
    return Offset(x = w * x + cos(angle) * rx, y = h * y + sin(angle) * ry)
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawBlob(
    color: Color,
    center: Offset,
    radius: Float,
) {
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(color, Color.Transparent),
            center = center,
            radius = radius,
        ),
        radius = radius,
        center = center,
    )
}


