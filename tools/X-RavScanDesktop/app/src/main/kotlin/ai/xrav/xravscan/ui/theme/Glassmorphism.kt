package ai.xrav.xravscan.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.background

/**
 * Apple-HIG style frosted-glass surface.
 *
 * On Desktop the GPU compositor handles light translucency cheaply, so we lean on
 * a frosted gradient + hairline border rather than full Skia blur (full blur only
 * pays off if the underlying content is busy enough to *need* it, and it costs
 * ~3-4ms per frame on weaker iGPUs).
 */
fun Modifier.glassSurface(
    cornerRadius: Dp = 22.dp,
    borderWidth: Dp = 1.dp,
    borderColor: Color = GlassStroke,
    fillTop: Color = Color.White.copy(alpha = 0.07f),
    fillBottom: Color = Color.White.copy(alpha = 0.04f),
): Modifier {
    val shape = RoundedCornerShape(cornerRadius)
    return this
        .clip(shape)
        .background(
            brush = Brush.verticalGradient(listOf(fillTop, fillBottom)),
            shape = shape,
        )
        .border(BorderStroke(borderWidth, borderColor), shape)
}

@Composable
fun GlassPanel(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 22.dp,
    content: @Composable () -> Unit,
) {
    Box(modifier = modifier.glassSurface(cornerRadius = cornerRadius)) {
        content()
    }
}
