package ai.xrav.xravscan.ui.components

import ai.xrav.xravscan.ui.theme.GlassStrokeStrong
import ai.xrav.xravscan.ui.theme.SkyBlue
import ai.xrav.xravscan.ui.theme.TextPrimary
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun NeonButton(
    text: String,
    modifier: Modifier = Modifier,
    accent: Color = SkyBlue,
    leadingIcon: ImageVector? = null,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.97f else 1f, label = "neon-scale")
    val shape = RoundedCornerShape(18.dp)

    Box(
        modifier = modifier
            .scale(scale)
            .clip(shape)
            .background(
                brush = Brush.horizontalGradient(
                    listOf(accent.copy(alpha = if (enabled) 0.20f else 0.10f), accent.copy(alpha = if (enabled) 0.10f else 0.06f)),
                ),
                shape = shape,
            )
            .border(BorderStroke(1.dp, GlassStrokeStrong), shape)
            .clickable(
                enabled = enabled,
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            )
            .height(46.dp)
            .padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            if (leadingIcon != null) {
                Icon(
                    leadingIcon,
                    contentDescription = null,
                    tint = if (enabled) accent else accent.copy(alpha = 0.5f),
                )
                Spacer(Modifier.width(8.dp))
            }
            Text(
                text,
                color = if (enabled) TextPrimary else TextPrimary.copy(alpha = 0.55f),
                fontSize = 14.sp,
            )
        }
    }
}
