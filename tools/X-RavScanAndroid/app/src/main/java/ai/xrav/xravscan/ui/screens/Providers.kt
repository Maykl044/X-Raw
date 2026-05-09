package ai.xrav.xravscan.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.xrav.xravscan.R
import ai.xrav.xravscan.ui.components.GlassCard
import ai.xrav.xravscan.ui.theme.TextPrimary
import ai.xrav.xravscan.ui.theme.TextSecondary

@Composable
fun ProvidersScreen() {
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
    }
}
