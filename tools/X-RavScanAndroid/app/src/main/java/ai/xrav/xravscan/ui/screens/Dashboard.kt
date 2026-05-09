package ai.xrav.xravscan.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.xrav.xravscan.R
import ai.xrav.xravscan.ui.components.GlassCard
import ai.xrav.xravscan.ui.components.NeonButton
import ai.xrav.xravscan.ui.theme.Mint
import ai.xrav.xravscan.ui.theme.SkyBlue
import ai.xrav.xravscan.ui.theme.TextPrimary
import ai.xrav.xravscan.ui.theme.TextSecondary
import ai.xrav.xravscan.ui.theme.Violet

@Composable
fun DashboardScreen() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            text = stringResource(R.string.dashboard_title),
            color = TextPrimary,
            fontWeight = FontWeight.Bold,
            fontSize = 28.sp,
        )
        Text(
            text = stringResource(R.string.dashboard_subtitle),
            color = TextSecondary,
            fontSize = 14.sp,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatTile(
                modifier = Modifier.weight(1f),
                value = "29",
                label = stringResource(R.string.stat_providers),
                icon = Icons.Outlined.Cloud,
                accent = SkyBlue,
            )
            StatTile(
                modifier = Modifier.weight(1f),
                value = "—",
                label = stringResource(R.string.stat_cidr_loaded),
                icon = Icons.Outlined.Public,
                accent = Violet,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatTile(
                modifier = Modifier.weight(1f),
                value = "—",
                label = stringResource(R.string.stat_results),
                icon = Icons.Outlined.Insights,
                accent = Mint,
            )
            StatTile(
                modifier = Modifier.weight(1f),
                value = "0",
                label = stringResource(R.string.stat_active_scan),
                icon = Icons.Outlined.Speed,
                accent = SkyBlue,
            )
        }

        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = stringResource(R.string.dashboard_quick_actions),
                    color = TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp,
                )
                NeonButton(
                    text = stringResource(R.string.action_start_scan),
                    accent = SkyBlue,
                    leadingIcon = {
                        Icon(
                            Icons.Outlined.PlayArrow,
                            contentDescription = null,
                            tint = SkyBlue,
                            modifier = Modifier.size(18.dp),
                        )
                    },
                    onClick = {},
                    modifier = Modifier.fillMaxWidth(),
                )
                NeonButton(
                    text = stringResource(R.string.action_smart_append),
                    accent = Violet,
                    leadingIcon = {
                        Icon(
                            Icons.Outlined.Bolt,
                            contentDescription = null,
                            tint = Violet,
                            modifier = Modifier.size(18.dp),
                        )
                    },
                    onClick = {},
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.dashboard_status),
                    color = TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp,
                )
                Text(
                    text = stringResource(R.string.dashboard_status_idle),
                    color = TextSecondary,
                    fontSize = 13.sp,
                )
            }
        }

        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun StatTile(
    modifier: Modifier = Modifier,
    value: String,
    label: String,
    icon: ImageVector,
    accent: Color,
) {
    GlassCard(modifier = modifier, accentTint = accent) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = label,
                    color = TextSecondary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
            Text(
                text = value,
                color = TextPrimary,
                fontWeight = FontWeight.Bold,
                fontSize = 26.sp,
            )
        }
    }
}
