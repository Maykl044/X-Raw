package ai.xrav.xravscan.ui.screens

import ai.xrav.xravscan.ui.components.GlassCard
import ai.xrav.xravscan.ui.components.NeonButton
import ai.xrav.xravscan.ui.theme.Mint
import ai.xrav.xravscan.ui.theme.SkyBlue
import ai.xrav.xravscan.ui.theme.TextPrimary
import ai.xrav.xravscan.ui.theme.TextSecondary
import ai.xrav.xravscan.ui.theme.Violet
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun DashboardScreen(modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text("Dashboard", color = TextPrimary, fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
        Text(
            "Live network reconnaissance overview",
            color = TextSecondary,
            fontSize = 13.sp,
        )
        Spacer(Modifier.height(20.dp))

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            StatTile("Providers", "0", Icons.Outlined.Cloud, SkyBlue)
            StatTile("CIDR loaded", "0", Icons.Outlined.Public, Violet)
            StatTile("Results", "0", Icons.Outlined.Insights, Mint)
            StatTile("Discoveries", "0", Icons.Outlined.Speed, SkyBlue)
        }

        Spacer(Modifier.height(20.dp))
        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Column {
                Text("Quick actions", color = TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Phase D1 skeleton — wired in D2-D4 with real database + scanner core.",
                    color = TextSecondary,
                    fontSize = 12.sp,
                )
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    NeonButton(
                        text = "Start scan",
                        accent = SkyBlue,
                        leadingIcon = Icons.Outlined.PlayArrow,
                        onClick = { },
                    )
                    NeonButton(
                        text = "Smart Append",
                        accent = Violet,
                        leadingIcon = Icons.Outlined.Bolt,
                        onClick = { },
                    )
                }
            }
        }
    }
}

@Composable
private fun RowScope.StatTile(
    label: String,
    value: String,
    icon: ImageVector,
    accent: Color,
) {
    GlassCard(modifier = Modifier.weight(1f)) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = accent)
                Spacer(Modifier.width(8.dp))
                Text(label, color = TextSecondary, fontSize = 12.sp)
            }
            Spacer(Modifier.height(8.dp))
            Text(value, color = TextPrimary, fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}
