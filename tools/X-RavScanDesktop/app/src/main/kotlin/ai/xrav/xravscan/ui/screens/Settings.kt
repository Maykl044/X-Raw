package ai.xrav.xravscan.ui.screens

import ai.xrav.xravscan.AppContainer
import ai.xrav.xravscan.data.export.ExportOutcome
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
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.SaveAlt
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(modifier: Modifier = Modifier) {
    val container = remember { runCatching { AppContainer.get() }.getOrNull() }
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }

    fun runExport(block: suspend () -> ExportOutcome) {
        if (working || container == null) return
        working = true
        scope.launch {
            status = when (val outcome = block()) {
                is ExportOutcome.Saved -> "Saved → ${outcome.file.absolutePath}"
                is ExportOutcome.Cancelled -> "Cancelled."
                is ExportOutcome.Empty -> "Nothing to export."
                is ExportOutcome.Failure -> "Failed — ${outcome.message}"
            }
            working = false
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Text("Settings", color = TextPrimary, fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
        Text("Export, data management, app info.", color = TextSecondary, fontSize = 13.sp)
        Spacer(Modifier.height(20.dp))

        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Column {
                Text(
                    "Export",
                    color = TextPrimary,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "JFileChooser opens — pick a folder, X-RavScan writes UTF-8 with a timestamped name.",
                    color = TextSecondary,
                    fontSize = 12.sp,
                )
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    NeonButton(
                        text = "Export hosts (TXT)",
                        accent = SkyBlue,
                        leadingIcon = Icons.Outlined.SaveAlt,
                        enabled = !working,
                        onClick = { runExport { container!!.exporter.exportHostsTxt() } },
                    )
                    NeonButton(
                        text = "Export ranges (JSON)",
                        accent = Violet,
                        leadingIcon = Icons.Outlined.SaveAlt,
                        enabled = !working,
                        onClick = { runExport { container!!.exporter.exportRangesJson() } },
                    )
                }
                if (status != null) {
                    Spacer(Modifier.height(10.dp))
                    Text(status!!, color = TextSecondary, fontSize = 11.sp)
                }
            }
        }

        Spacer(Modifier.height(18.dp))

        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Column {
                Text(
                    "Data management",
                    color = TextPrimary,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Wipes only the chosen table — providers + CIDRs are kept.",
                    color = TextSecondary,
                    fontSize = 12.sp,
                )
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    NeonButton(
                        text = "Clear scan results",
                        accent = TextSecondary,
                        leadingIcon = Icons.Outlined.Delete,
                        enabled = !working,
                        onClick = {
                            scope.launch {
                                container?.scanRepository?.clearAll()
                                status = "Scan results cleared."
                            }
                        },
                    )
                    NeonButton(
                        text = "Clear pending discoveries",
                        accent = Mint,
                        leadingIcon = Icons.Outlined.Delete,
                        enabled = !working,
                        onClick = {
                            scope.launch {
                                container?.discoveryRepository?.dismissAllPending()
                                status = "Pending discoveries dismissed."
                            }
                        },
                    )
                }
            }
        }

        Spacer(Modifier.height(18.dp))

        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Column {
                Text("About", color = TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text(
                    "X-RavScan Desktop — Compose Multiplatform 1.7.3 + Kotlin 2.0.21. " +
                        "Database lives at " + ai.xrav.xravscan.data.local.DatabaseFactory.resolveDbFile()
                            .absolutePath + ".",
                    color = TextSecondary,
                    fontSize = 12.sp,
                )
            }
        }
    }
}
