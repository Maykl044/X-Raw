package ai.xrav.xravscan.ui.localization

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import java.util.Locale
import java.util.prefs.Preferences

/**
 * Static strings table. Two-language map (EN + RU) plus a system fallback
 * that picks based on `Locale.getDefault()`.
 *
 * The desktop project doesn't use Compose Multiplatform Resources yet (it
 * targets the JVM directly via `compose.desktop.currentOs`), so a plain
 * data class is the simplest, build-safe way to expose translatable
 * strings to Compose without introducing a code-generation step.
 */
data class AppStrings(
    val tag: String,                                    // "en", "ru" (resolved)
    val sidebarDashboard: String,
    val sidebarProviders: String,
    val sidebarDiscovery: String,
    val sidebarResults: String,
    val sidebarSettings: String,
    val brandTagline: String,
    val networkOnlineWifi: String,
    val networkOnlineEthernet: String,
    val networkOnlineCellular: String,
    val networkVpnPaused: String,
    val networkOffline: String,
    val actionStartScan: String,
    val actionSmartAppend: String,
    val actionDeepDiscovery: String,
    val actionCleanOptimize: String,
    val actionDismissAll: String,
    val actionApply: String,
    val actionDismiss: String,
    val actionQuickScan: String,
    val actionClearResults: String,
    val settingsLanguage: String,
    val settingsLanguageSystem: String,
    val settingsLanguageEn: String,
    val settingsLanguageRu: String,
    val settingsExport: String,
    val settingsExportHostsTxt: String,
    val settingsExportRangesJson: String,
    val settingsData: String,
    val settingsClearScanResults: String,
    val settingsClearPending: String,
    val settingsAbout: String,
    val settingsTitle: String,
    val settingsSubtitle: String,
    val fullScanTitle: String,
    val fullScanSubtitle: String,
    val fullScanPick: String,
    val fullScanStart: String,
    val fullScanCancel: String,
    val fullScanNoCidr: String,
    val fullScanStarting: String,
    val fullScanCompleting: String,
    val fullScanComplete: String,
    val fullScanCancelled: String,
    val fullScanPausedVpn: String,
    val fullScanPausedOffline: String,
    val fullScanDismiss: String,
    val bunnyDirectApiBadge: String,
    val bunnyLoadingViaApi: String,
    val bunnyReceivedGrouped: (Int, Int) -> String,
    val bunnyFallbackUsed: (Int) -> String,
    val updateUsingDohBypass: String,
) {
    companion object {
        val EN = AppStrings(
            tag = "en",
            sidebarDashboard = "Dashboard",
            sidebarProviders = "Providers",
            sidebarDiscovery = "Discovery",
            sidebarResults = "Results",
            sidebarSettings = "Settings",
            brandTagline = "X-RavScan",
            networkOnlineWifi = "Online · Wi-Fi",
            networkOnlineEthernet = "Online · Ethernet",
            networkOnlineCellular = "Online · Cellular",
            networkVpnPaused = "VPN active — paused",
            networkOffline = "Offline",
            actionStartScan = "Start scan",
            actionSmartAppend = "Smart Append",
            actionDeepDiscovery = "Deep Discovery",
            actionCleanOptimize = "Clean & Optimize",
            actionDismissAll = "Dismiss all",
            actionApply = "Apply",
            actionDismiss = "Dismiss",
            actionQuickScan = "Quick scan",
            actionClearResults = "Clear results",
            settingsLanguage = "Language",
            settingsLanguageSystem = "System",
            settingsLanguageEn = "English",
            settingsLanguageRu = "Русский",
            settingsExport = "Export",
            settingsExportHostsTxt = "Export hosts (TXT)",
            settingsExportRangesJson = "Export ranges (JSON)",
            settingsData = "Data management",
            settingsClearScanResults = "Clear scan results",
            settingsClearPending = "Clear pending discoveries",
            settingsAbout = "About",
            settingsTitle = "Settings",
            settingsSubtitle = "Language, export and data management",
            fullScanTitle = "Full provider scan",
            fullScanSubtitle = "Walk every IP in every CIDR for the selected provider.",
            fullScanPick = "Pick a provider…",
            fullScanStart = "Full provider scan",
            fullScanCancel = "Cancel",
            fullScanNoCidr = "Pick an enabled provider with at least one CIDR.",
            fullScanStarting = "Full scan starting",
            fullScanCompleting = "Finalising…",
            fullScanComplete = "Full scan complete",
            fullScanCancelled = "Full scan cancelled",
            fullScanPausedVpn = "VPN active — Full scan paused.",
            fullScanPausedOffline = "Offline — Full scan paused.",
            fullScanDismiss = "Dismiss",
            bunnyDirectApiBadge = "Direct API",
            bunnyLoadingViaApi = "Loading Bunny CDN ranges via direct API…",
            bunnyReceivedGrouped = { ips, cidrs -> "Bunny: $ips IPs received, grouped into $cidrs CIDRs" },
            bunnyFallbackUsed = { n -> "Bunny CDN: fell back to built-in list ($n CIDR)" },
            updateUsingDohBypass = "VPN active — using DoH for update fetches.",
        )

        val RU = AppStrings(
            tag = "ru",
            sidebarDashboard = "Главная",
            sidebarProviders = "Провайдеры",
            sidebarDiscovery = "Поиск",
            sidebarResults = "Результаты",
            sidebarSettings = "Настройки",
            brandTagline = "X-RavScan",
            networkOnlineWifi = "Онлайн · Wi-Fi",
            networkOnlineEthernet = "Онлайн · Ethernet",
            networkOnlineCellular = "Онлайн · Сотовая",
            networkVpnPaused = "VPN активен — пауза",
            networkOffline = "Нет сети",
            actionStartScan = "Запустить",
            actionSmartAppend = "Smart Append",
            actionDeepDiscovery = "Deep Discovery",
            actionCleanOptimize = "Очистить и оптимизировать",
            actionDismissAll = "Отклонить всё",
            actionApply = "Применить",
            actionDismiss = "Отклонить",
            actionQuickScan = "Быстрое сканирование",
            actionClearResults = "Очистить результаты",
            settingsLanguage = "Язык",
            settingsLanguageSystem = "Системный",
            settingsLanguageEn = "English",
            settingsLanguageRu = "Русский",
            settingsExport = "Экспорт",
            settingsExportHostsTxt = "Экспорт хостов (TXT)",
            settingsExportRangesJson = "Экспорт диапазонов (JSON)",
            settingsData = "Управление данными",
            settingsClearScanResults = "Очистить результаты сканирования",
            settingsClearPending = "Очистить найденные подсети",
            settingsAbout = "О приложении",
            settingsTitle = "Настройки",
            settingsSubtitle = "Язык, экспорт и управление данными",
            fullScanTitle = "Полное сканирование провайдера",
            fullScanSubtitle = "Перебрать каждый IP во всех CIDR выбранного провайдера.",
            fullScanPick = "Выберите провайдера…",
            fullScanStart = "Полный скан провайдера",
            fullScanCancel = "Отмена",
            fullScanNoCidr = "Выберите включённого провайдера с хотя бы одним CIDR.",
            fullScanStarting = "Полный скан запускается",
            fullScanCompleting = "Завершение…",
            fullScanComplete = "Полный скан завершён",
            fullScanCancelled = "Полный скан отменён",
            fullScanPausedVpn = "VPN активен — полный скан на паузе.",
            fullScanPausedOffline = "Нет сети — полный скан на паузе.",
            fullScanDismiss = "Скрыть",
            bunnyDirectApiBadge = "Прямой API",
            bunnyLoadingViaApi = "Загрузка диапазонов Bunny CDN через прямой API…",
            bunnyReceivedGrouped = { ips, cidrs -> "Bunny: получено $ips IP, объединены в $cidrs CIDR" },
            bunnyFallbackUsed = { n -> "Bunny CDN: fallback на встроенный список ($n CIDR)" },
            updateUsingDohBypass = "VPN активен — обновление через DoH.",
        )

        /** Resolve a tag (`null` = system default) into the matching table. */
        fun forTag(tag: String?): AppStrings = when (tag?.lowercase()) {
            "en" -> EN
            "ru" -> RU
            null, "" -> when (Locale.getDefault().language.lowercase()) {
                "ru" -> RU
                else -> EN
            }
            else -> EN
        }
    }
}

/**
 * Tiny façade exposed to UI code so it doesn't have to know about Java
 * preferences. Gives screens a [tag] to render and a [setLanguage] to
 * call.
 */
class AppLocaleController internal constructor(
    private val tagState: () -> String?,
    private val onChange: (String?) -> Unit,
) {
    val tag: String? get() = tagState()
    fun setLanguage(newTag: String?) = onChange(newTag)
}

val LocalAppStrings = compositionLocalOf { AppStrings.EN }
val LocalAppLocale = compositionLocalOf<AppLocaleController> {
    error("LocalAppLocale not provided — wrap your content in ProvideAppLocale().")
}

/**
 * Persists the chosen language tag in the user's Java preferences so
 * the next launch starts in the same language.
 */
private object LocalePreferences {
    private val node = Preferences.userRoot().node("ai/xrav/xravscan/desktop")
    private const val KEY = "language_tag"
    fun read(): String? = node.get(KEY, null)?.takeIf { it.isNotBlank() }
    fun write(tag: String?) {
        if (tag.isNullOrBlank()) node.remove(KEY) else node.put(KEY, tag)
    }
}

@Composable
fun ProvideAppLocale(content: @Composable () -> Unit) {
    var tag by remember { mutableStateOf(LocalePreferences.read()) }
    val strings = remember(tag) { AppStrings.forTag(tag) }
    val controller = remember {
        AppLocaleController(
            tagState = { tag },
            onChange = { newTag ->
                LocalePreferences.write(newTag)
                tag = newTag
            },
        )
    }
    CompositionLocalProvider(
        LocalAppStrings provides strings,
        LocalAppLocale provides controller,
    ) {
        content()
    }
}
