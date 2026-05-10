package ai.xrav.xravscan.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ai.xrav.xravscan.BuildConfig
import ai.xrav.xravscan.R
import ai.xrav.xravscan.ui.components.GlassCard
import ai.xrav.xravscan.ui.components.NeonButton
import ai.xrav.xravscan.ui.screens.settings.SettingsViewModel
import ai.xrav.xravscan.ui.theme.Mint
import ai.xrav.xravscan.ui.theme.SkyBlue
import ai.xrav.xravscan.ui.theme.TextPrimary
import ai.xrav.xravscan.ui.theme.TextSecondary
import ai.xrav.xravscan.ui.theme.Violet

@Composable
fun SettingsScreen(viewModel: SettingsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            text = stringResource(R.string.settings_title),
            color = TextPrimary,
            fontWeight = FontWeight.Bold,
            fontSize = 28.sp,
        )
        Text(
            text = stringResource(R.string.settings_subtitle),
            color = TextSecondary,
            fontSize = 14.sp,
        )

        // Language section
        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = stringResource(R.string.settings_language),
                    color = TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp,
                )
                LanguagePill(
                    label = stringResource(R.string.settings_language_system),
                    selected = state.languageTag == null,
                    accent = Violet,
                    onClick = { viewModel.pickLanguage(null) },
                )
                LanguagePill(
                    label = stringResource(R.string.settings_language_en),
                    selected = state.languageTag == "en",
                    accent = SkyBlue,
                    onClick = { viewModel.pickLanguage("en") },
                )
                LanguagePill(
                    label = stringResource(R.string.settings_language_ru),
                    selected = state.languageTag == "ru",
                    accent = SkyBlue,
                    onClick = { viewModel.pickLanguage("ru") },
                )
                LanguagePill(
                    label = stringResource(R.string.settings_language_tk),
                    selected = state.languageTag == "tk",
                    accent = SkyBlue,
                    onClick = { viewModel.pickLanguage("tk") },
                )
            }
        }

        // Export section
        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = stringResource(R.string.settings_export),
                    color = TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp,
                )
                Text(
                    text = stringResource(R.string.settings_export_body),
                    color = TextSecondary,
                    fontSize = 13.sp,
                )
                NeonButton(
                    text = stringResource(R.string.settings_export_hosts_txt),
                    accent = Mint,
                    leadingIcon = {
                        Icon(
                            Icons.Outlined.Download,
                            contentDescription = null,
                            tint = Mint,
                            modifier = Modifier.size(18.dp),
                        )
                    },
                    onClick = viewModel::exportHosts,
                    modifier = Modifier.fillMaxWidth(),
                )
                NeonButton(
                    text = stringResource(R.string.settings_export_ranges_json),
                    accent = SkyBlue,
                    leadingIcon = {
                        Icon(
                            Icons.Outlined.Download,
                            contentDescription = null,
                            tint = SkyBlue,
                            modifier = Modifier.size(18.dp),
                        )
                    },
                    onClick = viewModel::exportRanges,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (state.log.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        state.log.takeLast(6).forEach { line ->
                            Text(line, color = TextSecondary, fontSize = 11.sp)
                        }
                    }
                }
            }
        }

        // Data section
        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = stringResource(R.string.settings_data),
                    color = TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp,
                )
                Text(
                    text = stringResource(R.string.settings_data_body),
                    color = TextSecondary,
                    fontSize = 13.sp,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NeonButton(
                        text = stringResource(R.string.settings_reset_results),
                        accent = TextSecondary,
                        leadingIcon = {
                            Icon(
                                Icons.Outlined.DeleteOutline,
                                contentDescription = null,
                                tint = TextSecondary,
                                modifier = Modifier.size(18.dp),
                            )
                        },
                        onClick = viewModel::clearScanResults,
                        modifier = Modifier.weight(1f),
                    )
                    NeonButton(
                        text = stringResource(R.string.settings_reset_discoveries),
                        accent = TextSecondary,
                        leadingIcon = {
                            Icon(
                                Icons.Outlined.DeleteOutline,
                                contentDescription = null,
                                tint = TextSecondary,
                                modifier = Modifier.size(18.dp),
                            )
                        },
                        onClick = viewModel::clearPendingDiscoveries,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        // External engines
        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.settings_external_engines),
                    color = TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp,
                )
                Text(
                    text = stringResource(R.string.settings_external_engines_body),
                    color = TextSecondary,
                    fontSize = 13.sp,
                )
            }
        }

        // About
        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.settings_about),
                    color = TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp,
                )
                Text(
                    text = stringResource(R.string.settings_about_body),
                    color = TextSecondary,
                    fontSize = 13.sp,
                )
                Text(
                    text = stringResource(R.string.settings_app_version, BuildConfig.VERSION_NAME),
                    color = TextSecondary,
                    fontSize = 11.sp,
                )
            }
        }
    }
}

@Composable
private fun LanguagePill(
    label: String,
    selected: Boolean,
    accent: Color,
    onClick: () -> Unit,
) {
    val border = if (selected) accent else accent.copy(alpha = 0.25f)
    val bg = if (selected) accent.copy(alpha = 0.18f) else Color.Transparent
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .border(width = 1.dp, color = border, shape = RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text = label,
            color = if (selected) accent else TextPrimary,
            fontSize = 14.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}
