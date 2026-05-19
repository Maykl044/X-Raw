package ai.xrav.xravscan.ui.localization

import ai.xrav.xravscan.data.local.Preferences
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * `tag = null` follows the device system locale; otherwise a BCP-47 tag
 * (e.g. `"ru"`, `"en"`, `"tk"`).
 */
data class AppLocale(val tag: String?) {
    fun toLocale(systemDefault: Locale): Locale =
        tag?.takeIf { it.isNotBlank() }?.let(Locale::forLanguageTag) ?: systemDefault

    companion object {
        val System = AppLocale(null)
        fun ofTag(tag: String?): AppLocale = AppLocale(tag?.takeIf { it.isNotBlank() })
    }
}

/**
 * In-process locale manager. Holds the active [AppLocale] in a Flow so
 * Compose can react to changes instantly without recreating the Activity
 * (the Manifest already declares `configChanges=locale`).
 *
 * The single source of truth on the Compose side is [LocalAppLocale];
 * UI code should read it via `LocalAppLocale.current` rather than
 * touching the manager directly.
 *
 * NOTE: We deliberately do *not* call `AppCompatDelegate.setApplicationLocales`
 * here — our theme is not an AppCompat theme, and the platform-level
 * locale isn't required for Compose `stringResource()` to honour our
 * choice. The CompositionLocal override below is enough.
 */
@Singleton
class LocalizationManager @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val preferences: Preferences,
) {
    private val _state = MutableStateFlow(AppLocale.ofTag(preferences.languageTag))
    val state: StateFlow<AppLocale> = _state.asStateFlow()

    /** Apply the persisted preference at app start. Safe to call before Compose runs. */
    fun applyPersisted() {
        _state.value = AppLocale.ofTag(preferences.languageTag)
    }

    /** Update the active locale. Persists the choice and notifies Compose subscribers. */
    fun setLanguage(tag: String?) {
        preferences.languageTag = tag
        _state.value = AppLocale.ofTag(tag)
    }

    fun currentTag(): String? = _state.value.tag
}

/**
 * Tiny façade exposed to UI code. Gives screens a [tag] to render and a
 * [setLanguage] to call without pulling Hilt into the UI layer.
 */
class AppLocaleController internal constructor(
    private val tagState: () -> String?,
    private val onChange: (String?) -> Unit,
) {
    val tag: String? get() = tagState()
    fun setLanguage(newTag: String?) = onChange(newTag)
}

val LocalAppLocale = compositionLocalOf<AppLocaleController> {
    error("LocalAppLocale not provided — wrap your content in ProvideAppLocale().")
}

/**
 * [ContextWrapper] that keeps the chain to the *original* Activity intact
 * (so consumers like [androidx.hilt.navigation.HiltViewModelFactory.findActivity]
 * still find the host Activity through `getBaseContext()`) while serving
 * locale-overridden [Resources] from [getResources].
 *
 * `Context.createConfigurationContext(...)` returns a fresh `ContextImpl`
 * that is **not** a `ContextWrapper` — using it as `LocalContext`
 * therefore breaks every API that walks up the wrapper chain looking
 * for an Activity. This wrapper avoids that pitfall.
 */
private class LocalizedContextWrapper(
    base: Context,
    private val localizedResources: Resources,
) : ContextWrapper(base) {
    override fun getResources(): Resources = localizedResources
}

/**
 * Bridges [LocalizationManager] (Hilt singleton) into the Compose tree.
 *
 * Wraps content in three CompositionLocals:
 *   1. [LocalAppLocale]      — UI-only access object
 *   2. [LocalConfiguration]  — override carrying the requested locale
 *      (drives Compose recomposition of `stringResource()` callers)
 *   3. [LocalContext]        — a [LocalizedContextWrapper] whose
 *      `getResources()` resolves via the requested locale, so every
 *      existing `stringResource(R.string.foo)` call instantly re-reads
 *      from `values-XX/strings.xml` when the user picks a new language.
 */
@Composable
fun ProvideAppLocale(
    manager: LocalizationManager,
    content: @Composable () -> Unit,
) {
    val current by manager.state.collectAsState()

    val controller = remember(manager) {
        AppLocaleController(
            tagState = { manager.state.value.tag },
            onChange = { tag -> manager.setLanguage(tag) },
        )
    }

    val baseConfig = LocalConfiguration.current
    val baseContext = LocalContext.current
    val systemDefault = remember(baseConfig) {
        baseConfig.locales.takeIf { !it.isEmpty }?.get(0) ?: Locale.getDefault()
    }
    val targetLocale = current.toLocale(systemDefault)

    val newConfig = remember(baseConfig, targetLocale) {
        Configuration(baseConfig).apply {
            setLocale(targetLocale)
            setLayoutDirection(targetLocale)
        }
    }
    // Build locale-aware Resources from `createConfigurationContext`,
    // but only consume the Resources — never the Context itself. We
    // then re-wrap the *original* (Activity) context so Hilt can still
    // find the Activity through ContextWrapper.getBaseContext().
    val localizedResources = remember(baseContext, targetLocale) {
        baseContext.createConfigurationContext(newConfig).resources
    }
    val localizedContext = remember(baseContext, localizedResources) {
        LocalizedContextWrapper(baseContext, localizedResources)
    }

    CompositionLocalProvider(
        LocalAppLocale provides controller,
        LocalConfiguration provides newConfig,
        LocalContext provides localizedContext,
    ) {
        content()
    }
}
