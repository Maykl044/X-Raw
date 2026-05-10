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
fun SettingsScreen(modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text("Settings", color = TextPrimary, fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
        Text("Language, export paths, external engines.", color = TextSecondary, fontSize = 13.sp)
        Spacer(Modifier.height(20.dp))
        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Column {
                Text("Language", color = TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Phase D5 — switches between EN / RU / TK in-place via runtime locale state, and exports through JFileChooser.",
                    color = TextSecondary,
                    fontSize = 12.sp,
                )
            }
        }
    }
}
