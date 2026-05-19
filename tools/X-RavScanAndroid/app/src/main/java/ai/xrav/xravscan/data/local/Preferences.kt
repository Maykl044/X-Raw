package ai.xrav.xravscan.data.local

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class Preferences @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    var languageTag: String?
        get() = prefs.getString(KEY_LANGUAGE_TAG, null)
        set(value) {
            prefs.edit().apply {
                if (value.isNullOrBlank()) remove(KEY_LANGUAGE_TAG)
                else putString(KEY_LANGUAGE_TAG, value)
            }.apply()
        }

    companion object {
        private const val NAME = "x_ravscan_prefs"
        private const val KEY_LANGUAGE_TAG = "language_tag"
    }
}
