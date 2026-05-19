package ai.xrav.xravscan.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Apple-style frosted-glass surface. We approximate the iOS `Backdrop
 * Filter: blur(20px)` look with two stacked translucent fills + a
 * hairline border at 1px. ``cornerRadius`` defaults to 20dp per the
 * iOS HIG spec for cards.
 */
fun Modifier.glassSurface(
    cornerRadius: Dp = 20.dp,
    fill: Color = Glass50,
    border: Color = GlassBorder,
    accentTint: Color? = null,
): Modifier = this
    .clip(RoundedCornerShape(cornerRadius))
    .background(
        brush = Brush.verticalGradient(
            colors = listOf(
                fill.copy(alpha = (fill.alpha + 0.04f).coerceAtMost(1f)),
                fill,
            ),
        ),
    )
    .let { mod ->
        if (accentTint != null) {
            mod.background(
                brush = Brush.linearGradient(
                    colors = listOf(
                        accentTint.copy(alpha = 0.08f),
                        Color.Transparent,
                    ),
                ),
            )
        } else mod
    }
    .border(
        width = 1.dp,
        brush = Brush.linearGradient(
            colors = listOf(border, GlassBorderHi.copy(alpha = 0.08f)),
        ),
        shape = RoundedCornerShape(cornerRadius),
    )

@Composable
fun verticalDeepMidnight(): Brush = Brush.verticalGradient(
    colors = listOf(
        DeepMidnight900,
        DeepMidnight700,
        DeepMidnight600,
    ),
)
