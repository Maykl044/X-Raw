package ai.xrav.xravscan.ui

import ai.xrav.xravscan.ui.components.AnimatedBackground
import ai.xrav.xravscan.ui.components.GlassCard
import ai.xrav.xravscan.ui.components.NetworkTopBar
import ai.xrav.xravscan.ui.localization.LocalAppStrings
import ai.xrav.xravscan.ui.screens.DashboardScreen
import ai.xrav.xravscan.ui.screens.DiscoveryScreen
import ai.xrav.xravscan.ui.screens.ProvidersScreen
import ai.xrav.xravscan.ui.screens.ResultsScreen
import ai.xrav.xravscan.ui.screens.SettingsScreen
import ai.xrav.xravscan.ui.theme.SkyBlue
import ai.xrav.xravscan.ui.theme.TextMuted
import ai.xrav.xravscan.ui.theme.TextPrimary
import ai.xrav.xravscan.ui.theme.TextSecondary
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight

enum class Destination(val label: String, val icon: ImageVector) {
    Dashboard("Dashboard", Icons.Outlined.Dashboard),
    Providers("Providers", Icons.Outlined.Layers),
    Discovery("Discovery", Icons.Outlined.Explore),
    Results("Results", Icons.Outlined.Insights),
    Settings("Settings", Icons.Outlined.Settings),
}

@Composable
fun AppRoot() {
    var current by remember { mutableStateOf(Destination.Dashboard) }
    Box(modifier = Modifier.fillMaxSize()) {
        AnimatedBackground(modifier = Modifier.fillMaxSize())
        Column(modifier = Modifier.fillMaxSize().padding(20.dp)) {
            NetworkTopBar()
            Spacer(Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxSize()) {
                SidebarNav(current = current, onSelect = { current = it })
                Spacer(Modifier.width(20.dp))
                Box(modifier = Modifier.fillMaxSize()) {
                    AnimatedContent(
                        targetState = current,
                        label = "screen-switch",
                        transitionSpec = {
                            fadeIn(animationSpec = tween(220)) togetherWith fadeOut(animationSpec = tween(160))
                        },
                    ) { dest ->
                        when (dest) {
                            Destination.Dashboard -> DashboardScreen(modifier = Modifier.fillMaxSize())
                            Destination.Providers -> ProvidersScreen(modifier = Modifier.fillMaxSize())
                            Destination.Discovery -> DiscoveryScreen(modifier = Modifier.fillMaxSize())
                            Destination.Results -> ResultsScreen(modifier = Modifier.fillMaxSize())
                            Destination.Settings -> SettingsScreen(modifier = Modifier.fillMaxSize())
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SidebarNav(current: Destination, onSelect: (Destination) -> Unit) {
    val s = LocalAppStrings.current
    GlassCard(
        modifier = Modifier.fillMaxHeight().width(232.dp),
        contentPadding = 16.dp,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Text(
                s.brandTagline,
                color = TextPrimary,
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "iOS Glassmorphism · Desktop",
                color = TextMuted,
                fontSize = 11.sp,
            )
            Spacer(Modifier.height(20.dp))
            Destination.entries.forEach { d ->
                val label = when (d) {
                    Destination.Dashboard -> s.sidebarDashboard
                    Destination.Providers -> s.sidebarProviders
                    Destination.Discovery -> s.sidebarDiscovery
                    Destination.Results -> s.sidebarResults
                    Destination.Settings -> s.sidebarSettings
                }
                NavItem(
                    destination = d,
                    label = label,
                    selected = d == current,
                    onClick = { onSelect(d) },
                )
                Spacer(Modifier.height(6.dp))
            }
        }
    }
}

@Composable
private fun NavItem(destination: Destination, label: String, selected: Boolean, onClick: () -> Unit) {
    val accent = SkyBlue
    val targetAlpha = if (selected) 0.20f else 0.0f
    val pad by animateDpAsState(if (selected) 14.dp else 12.dp, tween(140), label = "nav-pad")
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(shape)
            .background(
                brush = Brush.horizontalGradient(
                    listOf(accent.copy(alpha = targetAlpha), Color.Transparent),
                ),
                shape = shape,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = pad),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
    ) {
        Icon(
            destination.icon,
            contentDescription = label,
            tint = if (selected) accent else TextSecondary,
        )
        Spacer(Modifier.width(12.dp))
        Text(
            label,
            color = if (selected) TextPrimary else TextSecondary,
            fontSize = 14.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
        if (selected) {
            Spacer(Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .height(6.dp)
                    .width(6.dp)
                    .clip(RoundedCornerShape(50))
                    .background(accent),
            )
        }
    }
}
