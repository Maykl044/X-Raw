package ai.xrav.xravscan.ui.screens

import ai.xrav.xravscan.ui.components.GlassCard
import ai.xrav.xravscan.ui.theme.TextPrimary
import ai.xrav.xravscan.ui.theme.TextSecondary
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun ProvidersScreen(modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text("Providers", color = TextPrimary, fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
        Text("Enable cloud providers, edit ASN sets, view CIDR counts.", color = TextSecondary, fontSize = 13.sp)
        Spacer(Modifier.height(20.dp))
        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Column {
                Text("0 providers loaded", color = TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Wired in Phase D2 — providers seeded from JSON on first launch (29 providers, ~38k CIDR).",
                    color = TextSecondary,
                    fontSize = 12.sp,
                )
            }
        }
    }
}
