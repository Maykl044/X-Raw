package ai.xrav.xravscan.ui.screens

import ai.xrav.xravscan.AppContainer
import ai.xrav.xravscan.domain.model.Provider
import ai.xrav.xravscan.ui.components.GlassCard
import ai.xrav.xravscan.ui.localization.LocalAppStrings
import ai.xrav.xravscan.ui.theme.GlassStroke
import ai.xrav.xravscan.ui.theme.Mint
import ai.xrav.xravscan.ui.theme.SkyBlue
import ai.xrav.xravscan.ui.theme.TextMuted
import ai.xrav.xravscan.ui.theme.TextPrimary
import ai.xrav.xravscan.ui.theme.TextSecondary
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

@Composable
fun ProvidersScreen(modifier: Modifier = Modifier) {
    var providers by remember { mutableStateOf<List<Provider>>(emptyList()) }
    val container = remember { runCatching { AppContainer.get() }.getOrNull() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(container) {
        container?.let { providers = it.providerRepository.listAll() }
    }

    var query by remember { mutableStateOf("") }
    val filtered by remember(providers, query) {
        derivedStateOf {
            val q = query.trim().lowercase()
            if (q.isEmpty()) providers
            else providers.filter { p ->
                q in p.name.lowercase() ||
                    q in p.slug.lowercase() ||
                    p.asns.any { q in it.toString() }
            }
        }
    }

    val enabled = providers.count { it.enabled }
    Column(modifier = modifier) {
        Text("Providers", color = TextPrimary, fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
        Text(
            "$enabled of ${providers.size} enabled" +
                if (query.isNotBlank()) " · ${filtered.size} match" else "",
            color = TextSecondary,
            fontSize = 13.sp,
        )
        Spacer(Modifier.height(14.dp))

        SearchField(query = query, onChange = { query = it })

        Spacer(Modifier.height(14.dp))
        if (providers.isEmpty()) {
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Column {
                    Text("Loading providers…", color = TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "On first launch the seed file is imported into SQLite (29 providers, ~38k CIDR).",
                        color = TextSecondary,
                        fontSize = 12.sp,
                    )
                }
            }
        } else if (filtered.isEmpty()) {
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Text(
                    "No providers match \"$query\".",
                    color = TextSecondary,
                    fontSize = 13.sp,
                )
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                items(filtered, key = { it.id }) { p ->
                    ProviderRow(
                        provider = p,
                        onToggle = { newEnabled ->
                            scope.launch {
                                container?.providerRepository?.setEnabled(p.id, newEnabled)
                                providers = container?.providerRepository?.listAll().orEmpty()
                            }
                        },
                    )
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }
}

@Composable
private fun SearchField(query: String, onChange: (String) -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF1B2233))
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Outlined.Search,
                contentDescription = null,
                tint = TextMuted,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Box(modifier = Modifier.fillMaxWidth()) {
                if (query.isEmpty()) {
                    Text(
                        "Search by name, slug, or ASN…",
                        color = TextMuted,
                        fontSize = 13.sp,
                    )
                }
                BasicTextField(
                    value = query,
                    onValueChange = onChange,
                    singleLine = true,
                    cursorBrush = SolidColor(SkyBlue),
                    textStyle = TextStyle(color = TextPrimary, fontSize = 13.sp),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun ProviderRow(
    provider: Provider,
    onToggle: (Boolean) -> Unit,
) {
    GlassCard(modifier = Modifier.fillMaxWidth(), contentPadding = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            ProviderDot(provider.color)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(provider.name, color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    if (provider.slug.equals("bunny", ignoreCase = true)) {
                        Spacer(Modifier.width(8.dp))
                        DirectApiBadge()
                    }
                }
                Text(
                    text = if (provider.asns.isEmpty()) provider.slug
                    else "AS " + provider.asns.joinToString(", "),
                    color = TextMuted,
                    fontSize = 11.sp,
                )
            }
            Spacer(Modifier.width(12.dp))
            Text(
                text = "${provider.cidrCount} CIDR",
                color = TextSecondary,
                fontSize = 12.sp,
            )
            Spacer(Modifier.width(14.dp))
            Switch(
                checked = provider.enabled,
                onCheckedChange = onToggle,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = TextPrimary,
                    checkedTrackColor = SkyBlue,
                    uncheckedThumbColor = TextMuted,
                    uncheckedTrackColor = Color.Transparent,
                    uncheckedBorderColor = GlassStroke,
                ),
            )
        }
    }
}

/**
 * Compact "Direct API" pill shown next to Bunny CDN. Mirrors the
 * Android badge — Mint accent, 0.5dp hairline, 18% fill.
 */
@Composable
private fun DirectApiBadge() {
    val strings = LocalAppStrings.current
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
            text = strings.bunnyDirectApiBadge,
            color = Mint,
            fontWeight = FontWeight.SemiBold,
            fontSize = 10.sp,
        )
    }
}

@Composable
private fun ProviderDot(hex: String) {
    val parsed = remember(hex) { runCatching { parseHexColor(hex) }.getOrDefault(SkyBlue) }
    Box(
        modifier = Modifier
            .size(12.dp)
            .clip(CircleShape)
            .background(parsed),
    )
}

private fun parseHexColor(hex: String): Color {
    val cleaned = hex.removePrefix("#")
    val argb = when (cleaned.length) {
        6 -> "FF$cleaned".toLong(16)
        8 -> cleaned.toLong(16)
        else -> error("invalid hex colour: $hex")
    }
    return Color(argb.toInt())
}
