package ai.xrav.xravscan.ui.screens

import ai.xrav.xravscan.AppContainer
import ai.xrav.xravscan.domain.model.FullScanProgress
import ai.xrav.xravscan.domain.model.Provider
import ai.xrav.xravscan.domain.model.ScanResult
import ai.xrav.xravscan.ui.components.GlassCard
import ai.xrav.xravscan.ui.components.NeonButton
import ai.xrav.xravscan.ui.localization.LocalAppStrings
import ai.xrav.xravscan.ui.theme.GlassStrokeStrong
import ai.xrav.xravscan.ui.theme.Mint
import ai.xrav.xravscan.ui.theme.SkyBlue
import ai.xrav.xravscan.ui.theme.TextPrimary
import ai.xrav.xravscan.ui.theme.TextSecondary
import ai.xrav.xravscan.ui.theme.Violet
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch

@Composable
fun ResultsScreen(modifier: Modifier = Modifier) {
    val container = remember { AppContainer.get() }
    val scope = rememberCoroutineScope()
    val strings = LocalAppStrings.current
    var running by remember { mutableStateOf(false) }

    val logState = remember { MutableStateFlow<List<String>>(emptyList()) }
    val log by logState.asStateFlow().collectAsState()
    val results by container.scanRepository.observeRecent().collectAsState(initial = emptyList())

    // Full scan state
    var providers by remember { mutableStateOf<List<Provider>>(emptyList()) }
    var pickedSlug by remember { mutableStateOf<String?>(null) }
    var dropdownOpen by remember { mutableStateOf(false) }
    var fullScanJob by remember { mutableStateOf<Job?>(null) }
    var fullScanProgress by remember { mutableStateOf<FullScanProgress?>(null) }
    var pausedScans by remember {
        mutableStateOf<Map<String, ai.xrav.xravscan.data.repository.ScanRepository.PausedScan>>(emptyMap())
    }

    suspend fun refreshPausedScans() {
        val all = container.providerRepository.listAll()
        val out = mutableMapOf<String, ai.xrav.xravscan.data.repository.ScanRepository.PausedScan>()
        for (p in all) {
            val snap = container.scanRepository.pausedScanFor(p.slug)
            if (snap != null) out[p.slug] = snap
        }
        pausedScans = out
    }

    LaunchedEffect(Unit) {
        providers = container.providerRepository.listAll()
            .filter { it.enabled && it.cidrCount > 0 }
        if (pickedSlug == null) {
            pickedSlug = providers.firstOrNull()?.slug
        }
        refreshPausedScans()
    }

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

    fun startFullScan(slug: String, resume: Boolean) {
        if (fullScanJob?.isActive == true) return
        fullScanProgress = null
        fullScanJob = scope.launch {
            try {
                // Phase J — uncapped. No maxIps argument; the repository
                // default is Long.MAX_VALUE so the iterator runs every
                // IP in every CIDR until exhausted (e.g. all ~6.6M
                // Cloudflare hosts).
                container.scanRepository.runFullProviderScan(
                    providerSlug = slug,
                    resume = resume,
                ).conflate().collect { p ->
                    fullScanProgress = p
                    p.message?.let { appendLog(it) }
                }
            } catch (t: Throwable) {
                appendLog("Full scan failed: ${t.message}")
            } finally {
                refreshPausedScans()
            }
        }
    }

    fun pauseFullScan() {
        fullScanJob?.cancel()
        fullScanJob = null
        appendLog("Full scan paused — cursor written to disk, tap Resume to continue.")
        fullScanProgress = fullScanProgress?.copy(done = true, cancelled = true)
        scope.launch { refreshPausedScans() }
    }

    fun discardPausedScan(slug: String) {
        scope.launch {
            container.scanRepository.discardPausedScan(slug)
            refreshPausedScans()
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        Text("Results", color = TextPrimary, fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
        Text(
            "Reachable hosts, RTT, TLS certificate CN.",
            color = TextSecondary,
            fontSize = 13.sp,
        )
        Spacer(Modifier.height(20.dp))

        // Quick scan card
        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Column {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    NeonButton(
                        text = strings.actionQuickScan,
                        accent = Violet,
                        leadingIcon = Icons.Outlined.Bolt,
                        enabled = !running,
                        onClick = ::launchScan,
                    )
                    NeonButton(
                        text = strings.actionClearResults,
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

        Spacer(Modifier.height(14.dp))

        // Full scan card
        FullScanCard(
            providers = providers,
            pickedSlug = pickedSlug,
            dropdownOpen = dropdownOpen,
            running = fullScanJob?.isActive == true,
            progress = fullScanProgress,
            pausedScans = pausedScans,
            onPick = { slug ->
                pickedSlug = slug
                dropdownOpen = false
            },
            onToggleDropdown = { dropdownOpen = !dropdownOpen },
            onCloseDropdown = { dropdownOpen = false },
            onStart = { pickedSlug?.let { startFullScan(it, resume = false) } },
            onResume = { slug -> startFullScan(slug, resume = true) },
            onDiscard = ::discardPausedScan,
            onPause = ::pauseFullScan,
            onDismiss = { fullScanProgress = null },
        )

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
private fun FullScanCard(
    providers: List<Provider>,
    pickedSlug: String?,
    dropdownOpen: Boolean,
    running: Boolean,
    progress: FullScanProgress?,
    pausedScans: Map<String, ai.xrav.xravscan.data.repository.ScanRepository.PausedScan>,
    onPick: (String) -> Unit,
    onToggleDropdown: () -> Unit,
    onCloseDropdown: () -> Unit,
    onStart: () -> Unit,
    onResume: (String) -> Unit,
    onDiscard: (String) -> Unit,
    onPause: () -> Unit,
    onDismiss: () -> Unit,
) {
    val strings = LocalAppStrings.current
    val picked = providers.firstOrNull { it.slug == pickedSlug }
    val pausedForPicked = pickedSlug?.let(pausedScans::get)

    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Column {
            Text(
                strings.fullScanTitle,
                color = TextPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                strings.fullScanSubtitle,
                color = TextSecondary,
                fontSize = 12.sp,
            )
            Spacer(Modifier.height(12.dp))

            // Dropdown
            Box {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color.White.copy(alpha = 0.04f))
                        .border(BorderStroke(1.dp, GlassStrokeStrong), RoundedCornerShape(14.dp))
                        .clickable(enabled = !running) { onToggleDropdown() }
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                ) {
                    val label = picked?.let { "${it.name} · ${it.cidrCount} CIDR" }
                        ?: strings.fullScanPick
                    Text(label, color = TextPrimary, fontSize = 13.sp)
                }
                DropdownMenu(
                    expanded = dropdownOpen,
                    onDismissRequest = onCloseDropdown,
                ) {
                    if (providers.isEmpty()) {
                        DropdownMenuItem(
                            text = { Text(strings.fullScanNoCidr, color = TextSecondary) },
                            onClick = onCloseDropdown,
                        )
                    } else {
                        for (p in providers) {
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        "${p.name} · ${p.cidrCount} CIDR",
                                        color = TextPrimary,
                                    )
                                },
                                onClick = { onPick(p.slug) },
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                NeonButton(
                    text = strings.fullScanStart,
                    accent = Violet,
                    leadingIcon = Icons.Outlined.PlayArrow,
                    enabled = !running && picked != null && pausedForPicked == null,
                    onClick = onStart,
                )
                if (running) {
                    NeonButton(
                        text = strings.actionPause,
                        accent = Color(0xFFFFB347),
                        leadingIcon = Icons.Outlined.Cancel,
                        enabled = true,
                        onClick = onPause,
                    )
                }
            }

            if (pausedForPicked != null && !running) {
                Spacer(Modifier.height(12.dp))
                Column {
                    Text(
                        strings.pausedScanCardTitle(picked?.name ?: pausedForPicked.providerSlug),
                        color = TextPrimary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        strings.pausedScanCardProgress(
                            pausedForPicked.ipsScanned,
                            pausedForPicked.ipsTotal,
                            pausedForPicked.hits,
                        ),
                        color = TextSecondary,
                        fontSize = 12.sp,
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        NeonButton(
                            text = strings.actionResume,
                            accent = SkyBlue,
                            leadingIcon = Icons.Outlined.PlayArrow,
                            enabled = true,
                            onClick = { onResume(pausedForPicked.providerSlug) },
                        )
                        NeonButton(
                            text = strings.actionDiscard,
                            accent = Color(0xFFFF6B6B),
                            leadingIcon = Icons.Outlined.Delete,
                            enabled = true,
                            onClick = { onDiscard(pausedForPicked.providerSlug) },
                        )
                    }
                }
            }

            if (progress != null) {
                Spacer(Modifier.height(12.dp))
                FullScanProgressView(progress = progress, onDismiss = onDismiss)
            }
        }
    }
}

@Composable
private fun FullScanProgressView(
    progress: FullScanProgress,
    onDismiss: () -> Unit,
) {
    val strings = LocalAppStrings.current
    val barColor: Color = when {
        progress.cancelled -> Color(0xFFFF6B6B)
        progress.done -> Mint
        else -> Violet
    }
    Column {
        LinearProgressIndicator(
            progress = { progress.percent.coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
            color = barColor,
            trackColor = Color.White.copy(alpha = 0.08f),
        )
        Spacer(Modifier.height(8.dp))
        val label = buildString {
            append(progress.providerName)
            append(" · ")
            append(progress.ipsScanned)
            append(" / ")
            append(progress.ipsTotal)
            progress.currentCidr?.let {
                append(" · ")
                append(it)
            }
        }
        Text(label, color = TextPrimary, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
        if (progress.message != null) {
            Spacer(Modifier.height(4.dp))
            Text(progress.message, color = TextSecondary, fontSize = 11.sp)
        }
        if (progress.done) {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${progress.hits} hits",
                    color = Mint,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.width(12.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color.White.copy(alpha = 0.06f))
                        .clickable { onDismiss() }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Text(strings.fullScanDismiss, color = TextSecondary, fontSize = 11.sp)
                }
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
