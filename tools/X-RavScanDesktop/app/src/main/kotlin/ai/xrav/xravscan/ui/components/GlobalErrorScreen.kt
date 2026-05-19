package ai.xrav.xravscan.ui.components

import ai.xrav.xravscan.ui.theme.Coral
import ai.xrav.xravscan.ui.theme.DeepMidnight
import ai.xrav.xravscan.ui.theme.TextPrimary
import ai.xrav.xravscan.ui.theme.TextSecondary
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.PrintWriter
import java.io.StringWriter

@Composable
fun GlobalErrorScreen(
    throwable: Throwable,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val trace = remember(throwable) { stackTraceOf(throwable) }
    val message = throwable::class.simpleName + ": " + (throwable.message ?: "(no message)")

    Box(
        modifier = modifier.background(DeepMidnight).padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.ErrorOutline, contentDescription = null, tint = Coral)
                    Spacer(Modifier.width(10.dp))
                    Text("Something went wrong", color = TextPrimary, fontSize = 20.sp)
                }
                Spacer(Modifier.height(8.dp))
                Text(message, color = TextSecondary, fontSize = 13.sp)
                Spacer(Modifier.height(12.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(360.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Text(
                        trace,
                        color = TextSecondary,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                    )
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    NeonButton(text = "Dismiss", accent = Coral, onClick = onDismiss)
                }
            }
        }
    }
}

private fun stackTraceOf(t: Throwable): String {
    val sw = StringWriter()
    t.printStackTrace(PrintWriter(sw))
    return sw.toString()
}
