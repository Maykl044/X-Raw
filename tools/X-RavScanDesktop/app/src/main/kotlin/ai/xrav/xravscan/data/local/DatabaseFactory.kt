package ai.xrav.xravscan.data.local

import ai.xrav.xravscan.db.XRavScanDb
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.io.File
import java.util.Properties

/**
 * Builds [XRavScanDb] backed by a JDBC SQLite file under the user's home dir.
 *
 * Storage path:
 *   - Windows: %APPDATA%\X-RavScan\x_ravscan.db
 *   - macOS:   ~/Library/Application Support/X-RavScan/x_ravscan.db
 *   - Linux:   ~/.local/share/X-RavScan/x_ravscan.db
 */
object DatabaseFactory {
    fun create(): XRavScanDb {
        val dbFile = resolveDbFile()
        dbFile.parentFile?.mkdirs()
        val url = "jdbc:sqlite:${dbFile.absolutePath}"
        val driver = JdbcSqliteDriver(url, Properties())
        // SQLDelight expects an explicit schema bootstrap on a fresh JDBC connection.
        XRavScanDb.Schema.create(driver)
        return XRavScanDb(driver)
    }

    fun resolveDbFile(): File {
        val home = System.getProperty("user.home") ?: "."
        val osName = System.getProperty("os.name")?.lowercase().orEmpty()
        val baseDir: File = when {
            osName.contains("windows") -> {
                val appData = System.getenv("APPDATA") ?: (home + File.separator + "AppData" + File.separator + "Roaming")
                File(appData, "X-RavScan")
            }
            osName.contains("mac") || osName.contains("darwin") -> {
                File(File(home, "Library"), "Application Support").let { File(it, "X-RavScan") }
            }
            else -> {
                val xdg = System.getenv("XDG_DATA_HOME")
                val baseDataDir = xdg?.let(::File) ?: File(File(home, ".local"), "share")
                File(baseDataDir, "X-RavScan")
            }
        }
        return File(baseDir, "x_ravscan.db")
    }
}
