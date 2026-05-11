package ai.xrav.xravscan.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import ai.xrav.xravscan.R
import ai.xrav.xravscan.domain.model.Provider
import ai.xrav.xravscan.ui.components.GlassCard
import ai.xrav.xravscan.ui.screens.providers.ProvidersViewModel
import ai.xrav.xravscan.ui.theme.Mint
import ai.xrav.xravscan.ui.theme.SkyBlue
import ai.xrav.xravscan.ui.theme.TextPrimary
import ai.xrav.xravscan.ui.theme.TextSecondary
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape

@Composable
fun ProvidersScreen(viewModel: ProvidersViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            text = stringResource(R.string.providers_title),
            color = TextPrimary,
            fontWeight = FontWeight.Bold,
            fontSize = 28.sp,
        )
        Text(
            text = stringResource(R.string.providers_subtitle),
            color = TextSecondary,
            fontSize = 14.sp,
        )

        if (state.providers.isNotEmpty()) {
            val enabledCount = state.providers.count { it.enabled }
            Text(
                text = stringResource(
                    R.string.providers_enabled_count,
                    enabledCount,
                    state.providers.size,
                ),
                color = TextSecondary,
                fontSize = 12.sp,
            )

            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = {
                    Text(stringResource(R.string.providers_search_hint), color = TextSecondary)
                },
                leadingIcon = {
                    Icon(Icons.Outlined.Search, contentDescription = null, tint = TextSecondary)
                },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary,
                    focusedBorderColor = SkyBlue.copy(alpha = 0.6f),
                    unfocusedBorderColor = TextSecondary.copy(alpha = 0.35f),
                    cursorColor = SkyBlue,
                ),
            )
        }

        if (state.providers.isEmpty()) {
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = stringResource(R.string.providers_placeholder_title),
                        color = TextPrimary,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 16.sp,
                    )
                    Text(
                        text = stringResource(R.string.providers_placeholder_body),
                        color = TextSecondary,
                        fontSize = 13.sp,
                    )
                }
            }
        } else {
            val needle = query.trim().lowercase()
            val filtered = if (needle.isEmpty()) state.providers
            else state.providers.filter {
                it.name.lowercase().contains(needle) ||
                    it.slug.lowercase().contains(needle) ||
                    it.asns.any { asn -> asn.toString().contains(needle) }
            }
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(items = filtered, key = { it.slug }) { p ->
                    ProviderRow(
                        provider = p,
                        onToggle = { viewModel.toggle(p.slug, it) },
                    )
                }
                item { Spacer(Modifier.height(8.dp)) }
            }
        }
    }
}

@Composable
private fun ProviderRow(
    provider: Provider,
    onToggle: (Boolean) -> Unit,
) {
    val accent = parseHexColor(provider.color) ?: SkyBlue
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // brand swatch
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(accent.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = provider.name.first().uppercase(),
                    color = accent,
                    fontWeight = FontWeight.Bold,
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = provider.name,
                        color = TextPrimary,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 15.sp,
                    )
                    if (provider.slug.equals("bunny", ignoreCase = true)) {
                        Spacer(Modifier.width(8.dp))
                        DirectApiBadge()
                    }
                }
                Text(
                    text = "${provider.cidrCount} CIDR · ASN " +
                        provider.asns.take(3).joinToString(", ") +
                        if (provider.asns.size > 3) "…" else "",
                    color = TextSecondary,
                    fontSize = 12.sp,
                )
            }
            Switch(
                checked = provider.enabled,
                onCheckedChange = onToggle,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = accent,
                    checkedTrackColor = accent.copy(alpha = 0.35f),
                    uncheckedThumbColor = TextSecondary,
                    uncheckedTrackColor = TextSecondary.copy(alpha = 0.25f),
                ),
            )
        }
    }
}

/**
 * Compact "Direct API" pill shown next to Bunny CDN (and anything else
 * we eventually wire up to a vendor-specific endpoint instead of
 * BGPView). Uses the Mint accent so it reads as "good / extended".
 */
@Composable
private fun DirectApiBadge() {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Mint.copy(alpha = 0.18f))
            .border(
                width = 0.5.dp,
                color = Mint.copy(alpha = 0.45f),
                shape = RoundedCornerShape(8.dp),
            )
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(
            text = stringResource(R.string.bunny_direct_api_badge),
            color = Mint,
            fontWeight = FontWeight.SemiBold,
            fontSize = 10.sp,
        )
    }
}

private fun parseHexColor(hex: String): Color? = runCatching {
    val cleaned = hex.removePrefix("#")
    if (cleaned.length != 6 && cleaned.length != 8) return@runCatching null
    val offset = if (cleaned.length == 8) 2 else 0
    val a = if (offset == 2) cleaned.substring(0, 2).toInt(16) else 0xFF
    val r = cleaned.substring(offset, offset + 2).toInt(16)
    val g = cleaned.substring(offset + 2, offset + 4).toInt(16)
    val b = cleaned.substring(offset + 4, offset + 6).toInt(16)
    Color(red = r, green = g, blue = b, alpha = a)
}.getOrNull()
