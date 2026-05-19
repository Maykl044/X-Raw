package ai.xrav.xravscan.ui.components

import ai.xrav.xravscan.ui.theme.glassSurface
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 22.dp,
    contentPadding: Dp = 18.dp,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .glassSurface(cornerRadius = cornerRadius)
            .padding(contentPadding),
    ) {
        content()
    }
}
