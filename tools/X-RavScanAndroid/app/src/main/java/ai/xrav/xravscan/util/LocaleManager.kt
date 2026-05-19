package ai.xrav.xravscan.util

import ai.xrav.xravscan.data.local.Preferences
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Wraps [AppCompatDelegate.setApplicationLocales] so the rest of the app
 * never has to think about per-app locale APIs vs. legacy
 * `Configuration.locale` plumbing. Persisting the chosen tag is handled
 * here too — system upgrades to Android 13+ will sync to the platform
 * locale store automatically.
 */
@Singleton
class LocaleManager @Inject constructor(
    private val preferences: Preferences,
) {
    /** Apply the persisted preference at app start. */
    fun applyPersisted() {
        val tag = preferences.languageTag
        if (tag.isNullOrBlank()) {
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
        } else {
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))
        }
    }

    /** [tag] = null for system default, or a BCP-47 language tag like "ru". */
    fun setLanguage(tag: String?) {
        preferences.languageTag = tag
        if (tag.isNullOrBlank()) {
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
        } else {
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))
        }
    }

    fun currentTag(): String? = preferences.languageTag
}
