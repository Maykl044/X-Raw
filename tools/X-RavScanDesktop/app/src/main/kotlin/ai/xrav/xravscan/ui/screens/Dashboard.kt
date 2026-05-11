package ai.xrav.xravscan.ui.screens

import ai.xrav.xravscan.AppContainer
import ai.xrav.xravscan.data.repository.DiscoveryRepository
import ai.xrav.xravscan.domain.model.DashboardStats
import ai.xrav.xravscan.ui.components.GlassCard
import ai.xrav.xravscan.ui.localization.LocalAppStrings
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

@Composable
fun DashboardScreen(modifier: Modifier = Modifier) {
    val container = remember { runCatching { AppContainer.get() }.getOrNull() }
    val statsFlow: Flow<DashboardStats> = remember {
        container?.providerRepository?.observeStats()
            ?: flowOf(DashboardStats(0, 0, 0, 0))
    }
    val stats by statsFlow.collectAsState(initial = DashboardStats(0, 0, 0, 0))

    val scope = rememberCoroutineScope()
    val strings = LocalAppStrings.current
    val bunnyMessages = remember(strings.tag) {
        DiscoveryRepository.Messages(
            bunnyLoadingViaApi = strings.bunnyLoadingViaApi,
            bunnyReceivedGrouped = strings.bunnyReceivedGrouped,
            bunnyFallbackUsed = strings.bunnyFallbackUsed,
        )
    }
    var running by remember { mutableStateOf(false) }
    var runningKind by remember { mutableStateOf("") }
    val logState = remember { MutableStateFlow<List<String>>(emptyList()) }
    val log by logState.asStateFlow().collectAsState()

    fun appendLog(line: String) {
        logState.value = (logState.value + line).takeLast(40)
    }

    fun launchScan() {
        if (container == null || running) return
        running = true
        runningKind = "scan"
        logState.value = emptyList()
        scope.launch {
            try {
                container.scanRepository.runQuickScan(sampleSize = 24) { appendLog(it) }
            } catch (t: Throwable) {
                appendLog("Quick scan failed: ${t.message}")
            } finally {
                running = false
            }
        }
    }

    fun launchSmartAppend() {
        if (container == null || running) return
        running = true
        runningKind = "smartappend"
        logState.value = emptyList()
        scope.launch {
            try {
                container.discoveryRepository.runSmartAppend(bunnyMessages) { appendLog(it) }
            } catch (t: Throwable) {
                appendLog("Smart Append failed: ${t.message}")
            } finally {
                running = false
            }
        }
    }

    Column(modifier = modifier) {
        Text("Dashboard", color = TextPrimary, fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
        Text(
            "Live network reconnaissance overview",
            color = TextSecondary,
            fontSize = 13.sp,
        )
        Spacer(Modifier.height(20.dp))

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            StatTile("Providers", stats.providers.toString(), Icons.Outlined.Cloud, SkyBlue)
            StatTile("CIDR loaded", stats.cidrLoaded.toString(), Icons.Outlined.Public, Violet)
            StatTile("Results", stats.results.toString(), Icons.Outlined.Insights, Mint)
            StatTile("Discoveries", stats.discoveries.toString(), Icons.Outlined.Speed, SkyBlue)
        }

        Spacer(Modifier.height(20.dp))
        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Column {
                Text(
                    "Quick actions",
                    color = TextPrimary,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Run a Quick scan against random hosts in every enabled provider, " +
                        "or refresh prefixes via BGPView Smart Append.",
                    color = TextSecondary,
                    fontSize = 12.sp,
                )
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    NeonButton(
                        text = "Start scan",
                        accent = SkyBlue,
                        leadingIcon = Icons.Outlined.PlayArrow,
                        enabled = !running,
                        onClick = ::launchScan,
                    )
                    NeonButton(
                        text = "Smart Append",
                        accent = Violet,
                        leadingIcon = Icons.Outlined.Bolt,
                        enabled = !running,
                        onClick = ::launchSmartAppend,
                    )
                }
                if (running || log.isNotEmpty()) {
                    Spacer(Modifier.height(14.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (running) {
                            CircularProgressIndicator(
                                color = if (runningKind == "scan") SkyBlue else Violet,
                                strokeWidth = 2.dp,
                                modifier = Modifier.height(14.dp).padding(end = 8.dp),
                            )
                        }
                        Text(
                            when {
                                running && runningKind == "scan" -> "Scanning…"
                                running -> "Smart Append running…"
                                else -> "Activity"
                            },
                            color = TextPrimary,
                            fontSize = 12.sp,
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    for (line in log.takeLast(8)) {
                        Text(
                            line,
                            color = TextSecondary,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
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
