package ai.xrav.xravscan.ui.screens

import ai.xrav.xravscan.AppContainer
import ai.xrav.xravscan.domain.model.ScanResult
import ai.xrav.xravscan.ui.components.GlassCard
import ai.xrav.xravscan.ui.components.NeonButton
import ai.xrav.xravscan.ui.theme.Mint
import ai.xrav.xravscan.ui.theme.SkyBlue
import ai.xrav.xravscan.ui.theme.TextPrimary
import ai.xrav.xravscan.ui.theme.TextSecondary
import ai.xrav.xravscan.ui.theme.Violet
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@Composable
fun ResultsScreen(modifier: Modifier = Modifier) {
    val container = remember { AppContainer.get() }
    val scope = rememberCoroutineScope()
    var running by remember { mutableStateOf(false) }

    val logState = remember { MutableStateFlow<List<String>>(emptyList()) }
    val log by logState.asStateFlow().collectAsState()
    val results by container.scanRepository.observeRecent().collectAsState(initial = emptyList())

    fun appendLog(line: String) {
        logState.value = (logState.value + line).takeLast(40)
    }

    fun launchScan() {
        if (running) return
        running = true
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

    fun clearAll() {
        scope.launch { container.scanRepository.clearAll() }
    }

    Column(modifier = modifier.fillMaxSize()) {
        Text("Results", color = TextPrimary, fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
        Text(
            "Reachable hosts, RTT, TLS certificate CN.",
            color = TextSecondary,
            fontSize = 13.sp,
        )
        Spacer(Modifier.height(20.dp))

        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Column {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    NeonButton(
                        text = "Quick scan",
                        accent = Violet,
                        leadingIcon = Icons.Outlined.Bolt,
                        enabled = !running,
                        onClick = ::launchScan,
                    )
                    NeonButton(
                        text = "Clear results",
                        accent = TextSecondary,
                        leadingIcon = Icons.Outlined.Delete,
                        enabled = !running && results.isNotEmpty(),
                        onClick = ::clearAll,
                    )
                }
                if (running || log.isNotEmpty()) {
                    Spacer(Modifier.height(14.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (running) {
                            CircularProgressIndicator(
                                color = SkyBlue,
                                strokeWidth = 2.dp,
                                modifier = Modifier.height(14.dp).padding(end = 8.dp),
                            )
                        }
                        Text(
                            if (running) "Scanning…" else "Activity",
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

        Spacer(Modifier.height(18.dp))

        Text(
            "Reachable hosts — ${results.size}",
            color = TextPrimary,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(10.dp))

        if (results.isEmpty()) {
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Text(
                    "No scan results yet — run Quick scan to probe random IPs from each enabled provider.",
                    color = TextSecondary,
                    fontSize = 13.sp,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(results, key = { it.id }) { ResultRow(it) }
            }
        }
    }
}

@Composable
private fun ResultRow(result: ScanResult) {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            RttBadge(result.rttMs)
            Spacer(Modifier.height(0.dp))
            Column(modifier = Modifier.padding(start = 12.dp)) {
                Text(
                    result.ip + ":" + result.port,
                    color = TextPrimary,
                    fontSize = 14.sp,
                    fontFamily = FontFamily.Monospace,
                )
                val subtitle = buildString {
                    append(result.providerSlug)
                    if (!result.tlsCn.isNullOrBlank()) {
                        append("  ·  CN=")
                        append(result.tlsCn)
                    }
                }
                Text(subtitle, color = TextSecondary, fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun RttBadge(rttMs: Long) {
    val colour: Color = when {
        rttMs < 80 -> Mint
        rttMs < 200 -> SkyBlue
        rttMs < 500 -> Violet
        else -> Color(0xFFFF6B6B)
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(colour.copy(alpha = 0.18f))
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(
            "$rttMs ms",
            color = colour,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            fontFamily = FontFamily.Monospace,
        )
    }
}
