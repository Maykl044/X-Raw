package ai.xrav.xravscan.ui.components

import ai.xrav.xravscan.ui.localization.LocalAppStrings
import ai.xrav.xravscan.ui.network.LocalNetworkState
import ai.xrav.xravscan.ui.network.NetworkState
import ai.xrav.xravscan.ui.theme.Mint
import ai.xrav.xravscan.ui.theme.SkyBlue
import ai.xrav.xravscan.ui.theme.TextPrimary
import ai.xrav.xravscan.ui.theme.Violet
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Floating glass top bar mirroring the Android version: brand pill on
 * the left, colour-coded network pill on the right.
 */
@Composable
fun NetworkTopBar(modifier: Modifier = Modifier) {
    val state = LocalNetworkState.current
    val s = LocalAppStrings.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BrandPill(s.brandTagline)
        NetworkPill(state, s)
    }
}

@Composable
private fun BrandPill(label: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(
                brush = Brush.horizontalGradient(
                    listOf(Color(0x33153260), Color(0x227A41A8)),
                ),
            )
            .border(0.5.dp, Color.White.copy(alpha = 0.20f), RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(text = label, color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun NetworkPill(state: NetworkState, s: ai.xrav.xravscan.ui.localization.AppStrings) {
    val (label, accent) = when {
        state.isVpn -> s.networkVpnPaused to Violet
        !state.available -> s.networkOffline to Color(0xFFFF647C)
        state.transport == NetworkState.Transport.WIFI -> s.networkOnlineWifi to Mint
        state.transport == NetworkState.Transport.CELLULAR -> s.networkOnlineCellular to SkyBlue
        state.transport == NetworkState.Transport.ETHERNET -> s.networkOnlineEthernet to Mint
        else -> s.networkOnlineWifi to Mint
    }
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White.copy(alpha = 0.06f))
            .border(0.5.dp, accent.copy(alpha = 0.45f), RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        StatusDot(accent = accent, pulsing = state.available && !state.isVpn)
        Text(text = label, color = TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun StatusDot(accent: Color, pulsing: Boolean) {
    val transition = rememberInfiniteTransition(label = "status-dot")
    val alpha by transition.animateFloat(
        initialValue = 0.55f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1100),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "alpha",
    )
    Box(
        modifier = Modifier
            .size(8.dp)
            .clip(CircleShape)
            .background(if (pulsing) accent.copy(alpha = alpha) else accent),
    )
}
