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
fun ResultsScreen(modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text("Results", color = TextPrimary, fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
        Text("Reachable hosts, RTT, TLS certificate CN.", color = TextSecondary, fontSize = 13.sp)
        Spacer(Modifier.height(20.dp))
        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Column {
                Text("0 results", color = TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Wired in Phase D3 — TCP/TLS quick scan with RTT colour badges and live activity log.",
                    color = TextSecondary,
                    fontSize = 12.sp,
                )
            }
        }
    }
}
