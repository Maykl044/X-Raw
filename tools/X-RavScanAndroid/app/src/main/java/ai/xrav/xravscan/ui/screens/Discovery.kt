package ai.xrav.xravscan.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.outlined.Radar
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
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
fun DiscoveryScreen() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            text = stringResource(R.string.discovery_title),
            color = TextPrimary,
            fontWeight = FontWeight.Bold,
            fontSize = 28.sp,
        )
        Text(
            text = stringResource(R.string.discovery_subtitle),
            color = TextSecondary,
            fontSize = 14.sp,
        )
        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                NeonButton(
                    text = stringResource(R.string.action_deep_discovery),
                    accent = SkyBlue,
                    leadingIcon = {
                        Icon(
                            Icons.Outlined.Radar,
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
                            Icons.Outlined.AutoAwesome,
                            contentDescription = null,
                            tint = Violet,
                            modifier = Modifier.size(18.dp),
                        )
                    },
                    onClick = {},
                    modifier = Modifier.fillMaxWidth(),
                )
                NeonButton(
                    text = stringResource(R.string.action_clean_optimize),
                    accent = Mint,
                    leadingIcon = {
                        Icon(
                            Icons.Outlined.CleaningServices,
                            contentDescription = null,
                            tint = Mint,
                            modifier = Modifier.size(18.dp),
                        )
                    },
                    onClick = {},
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = stringResource(R.string.discovery_placeholder_body),
                color = TextSecondary,
                fontSize = 13.sp,
            )
        }
    }
}
