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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Radar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ai.xrav.xravscan.R
import ai.xrav.xravscan.domain.model.Discovery
import ai.xrav.xravscan.ui.components.GlassCard
import ai.xrav.xravscan.ui.components.NeonButton
import ai.xrav.xravscan.ui.screens.discovery.DiscoveryViewModel
import ai.xrav.xravscan.ui.theme.Mint
import ai.xrav.xravscan.ui.theme.SkyBlue
import ai.xrav.xravscan.ui.theme.TextPrimary
import ai.xrav.xravscan.ui.theme.TextSecondary
import ai.xrav.xravscan.ui.theme.Violet

@Composable
fun DiscoveryScreen(viewModel: DiscoveryViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            text = stringResource(R.string.discovery_title),
            color = TextPrimary,
            fontWeight = FontWeight.Bold,
            fontSize = 28.sp,
        )
        Text(
            text = stringResource(R.string.discovery_subtitle),
            color = TextSecondary,
            fontSize = 14.sp,
        )
        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                NeonButton(
                    text = stringResource(R.string.action_deep_discovery),
                    accent = SkyBlue,
                    leadingIcon = {
                        Icon(
                            Icons.Outlined.Radar,
                            contentDescription = null,
                            tint = SkyBlue,
                            modifier = Modifier.size(18.dp),
                        )
                    },
                    onClick = viewModel::runSmartAppend,
                    modifier = Modifier.fillMaxWidth(),
                )
                NeonButton(
                    text = stringResource(R.string.action_smart_append),
                    accent = Violet,
                    leadingIcon = {
                        Icon(
                            Icons.Outlined.AutoAwesome,
                            contentDescription = null,
                            tint = Violet,
                            modifier = Modifier.size(18.dp),
                        )
                    },
                    onClick = viewModel::runSmartAppend,
                    modifier = Modifier.fillMaxWidth(),
                )
                NeonButton(
                    text = stringResource(R.string.action_clean_optimize),
                    accent = Mint,
                    leadingIcon = {
                        Icon(
                            Icons.Outlined.CleaningServices,
                            contentDescription = null,
                            tint = Mint,
                            modifier = Modifier.size(18.dp),
                        )
                    },
                    onClick = viewModel::runCleanAndOptimize,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

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
                            .heightIn(max = 140.dp)
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

        if (state.pending.isEmpty()) {
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(R.string.discovery_placeholder_body),
                    color = TextSecondary,
                    fontSize = 13.sp,
                )
            }
        } else {
            Text(
                text = stringResource(R.string.discovery_pending_title, state.pending.size),
                color = TextPrimary,
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp,
            )
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(items = state.pending, key = { it.id }) { d ->
                    DiscoveryRow(
                        discovery = d,
                        onApply = { viewModel.applyDiscovery(d.id) },
                        onDismiss = { viewModel.dismiss(d.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun DiscoveryRow(
    discovery: Discovery,
    onApply: () -> Unit,
    onDismiss: () -> Unit,
) {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    discovery.cidr,
                    color = TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                )
                Text(
                    "${discovery.providerSlug} · ${discovery.sourceApi}" +
                        (discovery.asn?.let { " · AS$it" } ?: ""),
                    color = TextSecondary,
                    fontSize = 11.sp,
                )
            }
            IconButton(onClick = onApply) {
                Icon(
                    Icons.Outlined.Check,
                    contentDescription = null,
                    tint = Mint,
                    modifier = Modifier.size(20.dp),
                )
            }
            IconButton(onClick = onDismiss) {
                Icon(
                    Icons.Outlined.Close,
                    contentDescription = null,
                    tint = TextSecondary,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}
