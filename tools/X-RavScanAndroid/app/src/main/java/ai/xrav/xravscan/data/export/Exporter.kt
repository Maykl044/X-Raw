package ai.xrav.xravscan.data.export

import ai.xrav.xravscan.data.local.dao.CidrRangeDao
import ai.xrav.xravscan.data.local.dao.ProviderDao
import ai.xrav.xravscan.data.local.dao.ScanResultDao
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Saves text payloads to the public Downloads collection on Android 10+
 * (via MediaStore — no WRITE_EXTERNAL_STORAGE needed) and falls back to
 * the app's external files directory + FileProvider on older devices, so
 * we never raise [android.os.FileUriExposedException].
 */
@Singleton
class Exporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val scanResultDao: ScanResultDao,
    private val cidrRangeDao: CidrRangeDao,
    private val providerDao: ProviderDao,
) {

    suspend fun exportHostsTxt(): ExportOutcome = withContext(Dispatchers.IO) {
        val rows = scanResultDao.observeRecent(limit = 5_000).first()
        if (rows.isEmpty()) return@withContext ExportOutcome.Empty
        val payload = buildString {
            append("# X-RavScan reachable hosts — ")
            append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))
            append('\n')
            rows.forEach { r ->
                append(r.ip).append(':').append(r.port)
                r.tlsCertCn?.let { append("  CN=").append(it) }
                r.rttMs?.let { append("  rtt=").append(it).append("ms") }
                append("  provider=").append(r.providerSlug)
                append('\n')
            }
        }
        write("xravscan-hosts-${stamp()}.txt", "text/plain", payload)
    }

    suspend fun exportRangesJson(): ExportOutcome = withContext(Dispatchers.IO) {
        val providers = providerDao.observeAllWithCount().first()
        val sb = StringBuilder()
        sb.append("{\n  \"exported_at\": \"")
            .append(SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US).format(Date()))
            .append("\",\n  \"providers\": [\n")
        providers.forEachIndexed { idx, p ->
            val ranges = cidrRangeDao.rangesForProvider(p.slug).map { it.cidr }
            sb.append("    {\n")
                .append("      \"slug\": \"").append(p.slug).append("\",\n")
                .append("      \"name\": \"").append(p.name.escape()).append("\",\n")
                .append("      \"enabled\": ").append(p.enabled).append(",\n")
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
        write("xravscan-ranges-${stamp()}.json", "application/json", sb.toString())
    }

    private fun write(name: String, mime: String, payload: String): ExportOutcome {
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                writeViaMediaStore(name, mime, payload)
            } else {
                writeLegacy(name, payload)
            }
        }.getOrElse { t ->
            ExportOutcome.Failure(t.message ?: t::class.simpleName ?: "unknown error")
        }
    }

    private fun writeViaMediaStore(name: String, mime: String, payload: String): ExportOutcome {
        val resolver = context.contentResolver
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/X-RavScan")
        }
        val uri = resolver.insert(collection, values)
            ?: return ExportOutcome.Failure("MediaStore insert returned null")
        resolver.openOutputStream(uri).use { os: OutputStream? ->
            os?.write(payload.toByteArray(Charsets.UTF_8))
                ?: return ExportOutcome.Failure("OutputStream null")
        }
        return ExportOutcome.Saved(name = name, location = "Downloads/X-RavScan", uri = uri)
    }

    private fun writeLegacy(name: String, payload: String): ExportOutcome {
        val dir = File(context.getExternalFilesDir(null), "exports").apply { mkdirs() }
        val file = File(dir, name)
        file.writeText(payload, Charsets.UTF_8)
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )
        return ExportOutcome.Saved(name = name, location = file.parentFile?.absolutePath ?: "exports", uri = uri)
    }

    private fun stamp() = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())

    private fun String.escape(): String =
        replace("\\", "\\\\").replace("\"", "\\\"")

    sealed interface ExportOutcome {
        data class Saved(val name: String, val location: String, val uri: Uri) : ExportOutcome
        data object Empty : ExportOutcome
        data class Failure(val message: String) : ExportOutcome
    }
}
