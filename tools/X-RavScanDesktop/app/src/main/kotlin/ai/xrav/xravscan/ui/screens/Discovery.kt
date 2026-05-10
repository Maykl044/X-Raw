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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoFixHigh
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun DiscoveryScreen(modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text("Discovery", color = TextPrimary, fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
        Text("Smart Append, Deep Discovery, Clean & Optimize.", color = TextSecondary, fontSize = 13.sp)
        Spacer(Modifier.height(20.dp))
        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Column {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    NeonButton(
                        text = "Deep Discovery",
                        accent = SkyBlue,
                        leadingIcon = Icons.Outlined.AutoFixHigh,
                        onClick = { },
                    )
                    NeonButton(
                        text = "Smart Append",
                        accent = Violet,
                        leadingIcon = Icons.Outlined.Bolt,
                        onClick = { },
                    )
                    NeonButton(
                        text = "Clean & Optimize",
                        accent = Mint,
                        leadingIcon = Icons.Outlined.CleaningServices,
                        onClick = { },
                    )
                }
                Spacer(Modifier.height(14.dp))
                Text(
                    "Phase D1 placeholder — wired in D3 with BGPView prefix walks + Smart Append over coroutines.",
                    color = TextSecondary,
                    fontSize = 12.sp,
                )
            }
        }
    }
}
