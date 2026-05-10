package ai.xrav.xravscan.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Radar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.background
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ai.xrav.xravscan.R
import ai.xrav.xravscan.domain.model.ScanResult
import ai.xrav.xravscan.ui.components.GlassCard
import ai.xrav.xravscan.ui.components.NeonButton
import ai.xrav.xravscan.ui.screens.results.ResultsViewModel
import ai.xrav.xravscan.ui.theme.Mint
import ai.xrav.xravscan.ui.theme.SkyBlue
import ai.xrav.xravscan.ui.theme.TextPrimary
import ai.xrav.xravscan.ui.theme.TextSecondary
import ai.xrav.xravscan.ui.theme.Violet
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ResultsScreen(viewModel: ResultsViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            text = stringResource(R.string.results_title),
            color = TextPrimary,
            fontWeight = FontWeight.Bold,
            fontSize = 28.sp,
        )
        Text(
            text = stringResource(R.string.results_subtitle),
            color = TextSecondary,
            fontSize = 14.sp,
        )

        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                NeonButton(
                    text = stringResource(R.string.action_quick_scan),
                    accent = SkyBlue,
                    leadingIcon = {
                        Icon(
                            Icons.Outlined.PlayArrow,
                            contentDescription = null,
                            tint = SkyBlue,
                            modifier = Modifier.size(18.dp),
                        )
                    },
                    onClick = viewModel::runQuickScan,
                    modifier = Modifier.weight(1f),
                )
                NeonButton(
                    text = stringResource(R.string.action_clear_results),
                    accent = TextSecondary,
                    leadingIcon = {
                        Icon(
                            Icons.Outlined.DeleteOutline,
                            contentDescription = null,
                            tint = TextSecondary,
                            modifier = Modifier.size(18.dp),
                        )
                    },
                    onClick = viewModel::clearAll,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        FullScanCard(
            providers = state.providers.filter { it.enabled },
            progress = state.fullScanProgress,
            onStart = { slug -> viewModel.runFullProviderScan(slug) },
            onCancel = viewModel::cancelFullScan,
            onDismiss = viewModel::dismissFullScanProgress,
        )

        if (state.running || state.log.isNotEmpty()) {
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = stringResource(R.string.discovery_log_title),
                            color = TextPrimary,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 14.sp,
                            modifier = Modifier.weight(1f),
                        )
                        if (state.running) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = Violet,
                            )
                        }
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 120.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            state.log.takeLast(50).forEach { line ->
                                Text(line, color = TextSecondary, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }

        if (state.results.isEmpty()) {
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(R.string.results_placeholder_body),
                    color = TextSecondary,
                    fontSize = 13.sp,
                )
            }
        } else {
            Text(
                text = stringResource(R.string.results_count, state.results.size),
                color = TextPrimary,
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp,
            )
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(items = state.results, key = { it.id }) { r ->
                    ScanResultRow(r)
                }
            }
        }
    }
}

@Composable
private fun ScanResultRow(result: ScanResult) {
    val rttColor = rttColor(result.rttMs)
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "${result.ip}:${result.port}",
                    color = TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                )
                val subline = buildString {
                    append(result.providerSlug)
                    result.tlsCertCn?.let {
                        append(" · CN=")
                        append(it)
                    }
                    append(" · ")
                    append(SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(result.scannedAt)))
                }
                Text(subline, color = TextSecondary, fontSize = 11.sp)
            }
            if (result.rttMs != null) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(rttColor.copy(alpha = 0.18f))
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    Text(
                        "${result.rttMs} ms",
                        color = rttColor,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

private fun rttColor(rttMs: Int?): Color = when {
    rttMs == null -> TextSecondary
    rttMs < 80 -> Mint
    rttMs < 200 -> SkyBlue
    rttMs < 500 -> Violet
    else -> Color(0xFFFF6B6B)
}

@Composable
private fun FullScanCard(
    providers: List<ai.xrav.xravscan.domain.model.Provider>,
    progress: ai.xrav.xravscan.domain.model.FullScanProgress?,
    onStart: (String) -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
) {
    val running = progress != null && !progress.done
    var menuExpanded by remember { mutableStateOf(false) }
    var selectedSlug by remember(providers) { mutableStateOf(providers.firstOrNull()?.slug) }
    val selectedProvider = providers.firstOrNull { it.slug == selectedSlug }

    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                text = stringResource(R.string.full_scan_title),
                color = TextPrimary,
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp,
            )
            Text(
                text = stringResource(R.string.full_scan_subtitle),
                color = TextSecondary,
                fontSize = 12.sp,
            )

            Box {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color.White.copy(alpha = 0.06f))
                        .clickable(enabled = !running && providers.isNotEmpty()) {
                            menuExpanded = true
                        }
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(
                        Icons.Outlined.Cloud,
                        contentDescription = null,
                        tint = SkyBlue,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = selectedProvider?.name
                            ?: stringResource(R.string.full_scan_pick),
                        color = TextPrimary,
                        fontSize = 14.sp,
                        modifier = Modifier.weight(1f),
                    )
                    if (selectedProvider != null) {
                        Text(
                            text = "${selectedProvider.cidrCount} CIDR",
                            color = TextSecondary,
                            fontSize = 11.sp,
                        )
                    }
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                ) {
                    providers.forEach { p ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = "${p.name} · ${p.cidrCount} CIDR",
                                    fontSize = 13.sp,
                                )
                            },
                            onClick = {
                                selectedSlug = p.slug
                                menuExpanded = false
                            },
                        )
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NeonButton(
                    text = stringResource(R.string.action_full_scan),
                    accent = Violet,
                    leadingIcon = {
                        Icon(
                            Icons.Outlined.Radar,
                            contentDescription = null,
                            tint = Violet,
                            modifier = Modifier.size(18.dp),
                        )
                    },
                    enabled = !running && selectedSlug != null,
                    onClick = { selectedSlug?.let(onStart) },
                    modifier = Modifier.weight(1f),
                )
                if (running) {
                    NeonButton(
                        text = stringResource(R.string.action_cancel),
                        accent = Color(0xFFFF6B6B),
                        leadingIcon = {
                            Icon(
                                Icons.Outlined.Cancel,
                                contentDescription = null,
                                tint = Color(0xFFFF6B6B),
                                modifier = Modifier.size(18.dp),
                            )
                        },
                        onClick = onCancel,
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            if (progress != null) {
                FullScanProgressView(
                    progress = progress,
                    onDismiss = onDismiss,
                )
            }
        }
    }
}

@Composable
private fun FullScanProgressView(
    progress: ai.xrav.xravscan.domain.model.FullScanProgress,
    onDismiss: () -> Unit,
) {
    val accent = when {
        progress.cancelled -> Color(0xFFFF6B6B)
        progress.done -> Mint
        else -> Violet
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        LinearProgressIndicator(
            progress = { progress.percent.coerceIn(0f, 1f) },
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp)),
            color = accent,
            trackColor = Color.White.copy(alpha = 0.08f),
        )
        val label = when {
            progress.currentCidr != null -> stringResource(
                R.string.full_scan_progress,
                progress.providerName,
                progress.ipsScanned.coerceAtMost(progress.ipsTotal),
                progress.ipsTotal,
                progress.currentCidr,
            )
            else -> stringResource(
                R.string.full_scan_progress_no_cidr,
                progress.providerName,
                progress.ipsScanned.coerceAtMost(progress.ipsTotal),
                progress.ipsTotal,
            )
        }
        Text(label, color = TextSecondary, fontSize = 12.sp)
        if (progress.message != null) {
            Text(progress.message, color = TextPrimary, fontSize = 12.sp)
        }
        if (progress.done) {
            NeonButton(
                text = stringResource(R.string.action_clear_results),
                accent = TextSecondary,
                leadingIcon = null,
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
