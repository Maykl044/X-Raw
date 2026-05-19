package ai.xrav.xravscan.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ai.xrav.xravscan.ui.theme.glassSurface

@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 20.dp,
    contentPadding: Dp = 16.dp,
    accentTint: Color? = null,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .glassSurface(cornerRadius = cornerRadius, accentTint = accentTint)
            .padding(contentPadding),
    ) {
        content()
    }
}
