package ai.xrav.xravscan.ui.screens

import ai.xrav.xravscan.AppContainer
import ai.xrav.xravscan.data.repository.DiscoveryRepository
import ai.xrav.xravscan.domain.model.Discovery
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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoFixHigh
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.outlined.PlaylistRemove
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@Composable
fun DiscoveryScreen(modifier: Modifier = Modifier) {
    val container = remember { AppContainer.get() }
    val scope = rememberCoroutineScope()
    val strings = LocalAppStrings.current
    val messages = remember(strings.tag) {
        DiscoveryRepository.Messages(
            noProviders = strings.smartAppendNoProviders,
            starting = strings.smartAppendStarting,
            bunnyLoadingViaApi = strings.bunnyLoadingViaApi,
            bunnyReceivedGrouped = strings.bunnyReceivedGrouped,
            bunnyFallbackUsed = strings.bunnyFallbackUsed,
            noAsnConfigured = strings.smartAppendNoAsnConfigured,
            errorLine = strings.smartAppendErrorLine,
            noPrefixes = strings.smartAppendNoPrefixes,
            fallback = strings.smartAppendFallback,
            fallbackWithReason = strings.smartAppendFallbackWithReason,
            providerLine = strings.smartAppendProviderLine,
            providerLineWithInvalid = strings.smartAppendProviderLineWithInvalid,
        )
    }

    var running by remember { mutableStateOf(false) }
    val logState = remember { MutableStateFlow<List<String>>(emptyList()) }
    val log by logState.asStateFlow().collectAsState()
    val pending by container.discoveryRepository.observeAll().collectAsState(initial = emptyList())

    fun appendLog(line: String) {
        val updated = (logState.value + line).takeLast(40)
        logState.value = updated
    }

    fun launchSmartAppend() {
        if (running) return
        running = true
        logState.value = emptyList()
        scope.launch {
            try {
                container.discoveryRepository.runSmartAppend(messages) { appendLog(it) }
            } catch (t: Throwable) {
                appendLog("Smart Append failed: ${t.message}")
            } finally {
                running = false
            }
        }
    }

    fun launchClean() {
        if (running) return
        running = true
        logState.value = emptyList()
        scope.launch {
            try {
                container.discoveryRepository.cleanAndOptimize { appendLog(it) }
            } catch (t: Throwable) {
                appendLog("Clean failed: ${t.message}")
            } finally {
                running = false
            }
        }
    }

    fun dismissAll() {
        scope.launch { container.discoveryRepository.dismissAllPending() }
    }

    Column(modifier = modifier.fillMaxSize()) {
        Text("Discovery", color = TextPrimary, fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
        Text(
            "Smart Append, Deep Discovery, Clean & Optimize.",
            color = TextSecondary,
            fontSize = 13.sp,
        )
        Spacer(Modifier.height(20.dp))

        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Column {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    NeonButton(
                        text = "Deep Discovery",
                        accent = SkyBlue,
                        leadingIcon = Icons.Outlined.AutoFixHigh,
                        enabled = !running,
                        onClick = ::launchSmartAppend,
                    )
                    NeonButton(
                        text = "Smart Append",
                        accent = Violet,
                        leadingIcon = Icons.Outlined.Bolt,
                        enabled = !running,
                        onClick = ::launchSmartAppend,
                    )
                    NeonButton(
                        text = "Clean & Optimize",
                        accent = Mint,
                        leadingIcon = Icons.Outlined.CleaningServices,
                        enabled = !running,
                        onClick = ::launchClean,
                    )
                    NeonButton(
                        text = "Dismiss all",
                        accent = TextSecondary,
                        leadingIcon = Icons.Outlined.PlaylistRemove,
                        enabled = !running && pending.any { !it.applied },
                        onClick = ::dismissAll,
                    )
                }
                if (running || log.isNotEmpty()) {
                    Spacer(Modifier.height(14.dp))
                    ActivityLog(running = running, lines = log)
                }
            }
        }

        Spacer(Modifier.height(18.dp))

        val pendingOnly = remember(pending) { pending.filter { !it.applied } }
        Text(
            "Pending discoveries — ${pendingOnly.size}",
            color = TextPrimary,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(10.dp))

        if (pendingOnly.isEmpty()) {
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Text(
                    "No pending networks. Run Smart Append to fetch the latest BGPView prefixes.",
                    color = TextSecondary,
                    fontSize = 13.sp,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(pendingOnly, key = { it.id }) { discovery ->
                    PendingRow(
                        discovery = discovery,
                        onApply = { scope.launch { container.discoveryRepository.applyDiscovery(it) } },
                        onDismiss = { scope.launch { container.discoveryRepository.dismiss(it) } },
                    )
                }
            }
        }
    }
}

@Composable
private fun ActivityLog(running: Boolean, lines: List<String>) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (running) {
                CircularProgressIndicator(
                    color = SkyBlue,
                    strokeWidth = 2.dp,
                    modifier = Modifier.height(14.dp).padding(end = 8.dp),
                )
            }
            Text(
                if (running) "Running…" else "Activity",
                color = TextPrimary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
            )
        }
        Spacer(Modifier.height(6.dp))
        for (line in lines.takeLast(8)) {
            Text(
                line,
                color = TextSecondary,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

@Composable
private fun PendingRow(
    discovery: Discovery,
    onApply: (Long) -> Unit,
    onDismiss: (Long) -> Unit,
) {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(end = 12.dp)) {
                Text(discovery.cidr, color = TextPrimary, fontSize = 14.sp, fontFamily = FontFamily.Monospace)
                Text(
                    "from ${discovery.providerSlug}",
                    color = TextSecondary,
                    fontSize = 11.sp,
                )
            }
            Spacer(Modifier.height(0.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                NeonButton(
                    text = "Apply",
                    accent = Mint,
                    onClick = { onApply(discovery.id) },
                )
                NeonButton(
                    text = "Dismiss",
                    accent = TextSecondary,
                    onClick = { onDismiss(discovery.id) },
                )
            }
        }
    }
}
