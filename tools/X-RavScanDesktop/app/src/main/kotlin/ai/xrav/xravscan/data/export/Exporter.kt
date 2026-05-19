package ai.xrav.xravscan.data.export

import ai.xrav.xravscan.db.XRavScanDb
import java.awt.Toolkit
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.swing.JFileChooser
import javax.swing.SwingUtilities
import javax.swing.filechooser.FileNameExtensionFilter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import org.slf4j.LoggerFactory

sealed class ExportOutcome {
    data class Saved(val file: File) : ExportOutcome()
    data object Cancelled : ExportOutcome()
    data object Empty : ExportOutcome()
    data class Failure(val message: String) : ExportOutcome()
}

/**
 * Cross-platform file exporter for the desktop. Uses Swing JFileChooser
 * (always available with the JDK) so we never rely on the OS-specific
 * native dialog and we never accidentally raise something analogous to
 * the Android FileUriExposedException.
 */
class Exporter(private val db: XRavScanDb) {
    private val log = LoggerFactory.getLogger("XRavScan.Exporter")
    private val q get() = db.xRavScanDbQueries

    suspend fun exportHostsTxt(): ExportOutcome {
        val rows = withContext(Dispatchers.IO) {
            q.recentScanResults(5_000).executeAsList()
        }
        if (rows.isEmpty()) return ExportOutcome.Empty

        val payload = buildString {
            append("# X-RavScan reachable hosts — ")
            append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))
            append('\n')
            rows.forEach { r ->
                append(r.ip).append(':').append(r.port)
                r.tlsCn?.let { append("  CN=").append(it) }
                append("  rtt=").append(r.rttMs).append("ms")
                append("  provider=").append(r.providerSlug)
                append('\n')
            }
        }
        return saveWithDialog(
            defaultName = "xravscan-hosts-${stamp()}.txt",
            description = "Text files (*.txt)",
            extension = "txt",
            payload = payload,
        )
    }

    suspend fun exportRangesJson(): ExportOutcome {
        val providers = withContext(Dispatchers.IO) {
            q.allProviders().executeAsList()
        }
        if (providers.isEmpty()) return ExportOutcome.Empty

        val sb = StringBuilder()
        sb.append("{\n  \"exported_at\": \"")
            .append(SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US).format(Date()))
            .append("\",\n  \"providers\": [\n")
        providers.forEachIndexed { idx, p ->
            val ranges = withContext(Dispatchers.IO) {
                q.cidrsForProvider(p.id).executeAsList()
            }
            sb.append("    {\n")
                .append("      \"slug\": \"").append(p.slug).append("\",\n")
                .append("      \"name\": \"").append(p.name.escape()).append("\",\n")
                .append("      \"enabled\": ").append(p.enabled == 1L).append(",\n")
                .append("      \"cidrs\": [")
            ranges.forEachIndexed { i, c ->
                sb.append('"').append(c).append('"')
                if (i < ranges.size - 1) sb.append(", ")
            }
            sb.append("]\n    }")
            if (idx < providers.size - 1) sb.append(',')
            sb.append('\n')
        }
        sb.append("  ]\n}\n")
        return saveWithDialog(
            defaultName = "xravscan-ranges-${stamp()}.json",
            description = "JSON files (*.json)",
            extension = "json",
            payload = sb.toString(),
        )
    }

    private suspend fun saveWithDialog(
        defaultName: String,
        description: String,
        extension: String,
        payload: String,
    ): ExportOutcome {
        val target = chooseFile(defaultName, description, extension) ?: return ExportOutcome.Cancelled
        return runCatching {
            withContext(Dispatchers.IO) {
                target.parentFile?.mkdirs()
                target.writeText(payload, Charsets.UTF_8)
            }
            ExportOutcome.Saved(target)
        }.getOrElse { t ->
            log.warn("Failed writing {}: {}", target, t.message)
            ExportOutcome.Failure(t.message ?: t::class.simpleName ?: "unknown error")
        }
    }

    private suspend fun chooseFile(
        defaultName: String,
        description: String,
        extension: String,
    ): File? = suspendCancellableCoroutine { cont ->
        val runOnEdt = Runnable {
            val chooser = JFileChooser().apply {
                dialogTitle = "Save export — $defaultName"
                fileSelectionMode = JFileChooser.FILES_ONLY
                fileFilter = FileNameExtensionFilter(description, extension)
                selectedFile = File(defaultName)
            }
            val parent = Toolkit.getDefaultToolkit()
            val result = chooser.showSaveDialog(null)
            if (result == JFileChooser.APPROVE_OPTION) {
                var f = chooser.selectedFile
                if (!f.name.endsWith(".$extension", ignoreCase = true)) {
                    f = File(f.parentFile, "${f.name}.$extension")
                }
                cont.resume(f)
            } else {
                cont.resume(null)
            }
            // parent is referenced just to keep AWT initialised on headless boxes.
            @Suppress("UNUSED_VARIABLE") val ignored = parent
        }
        if (SwingUtilities.isEventDispatchThread()) runOnEdt.run()
        else SwingUtilities.invokeLater(runOnEdt)
    }

    private fun stamp(): String =
        SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())

    private fun String.escape(): String = replace("\\", "\\\\").replace("\"", "\\\"")
}
