package com.papyrus.app.data

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK;

    /** [systemDark] is only consulted for [SYSTEM]; the other two ignore the device setting. */
    fun isDark(systemDark: Boolean): Boolean = when (this) {
        SYSTEM -> systemDark
        LIGHT -> false
        DARK -> true
    }
}

data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    /** Documents another app hands over are copied into the library as they open. */
    val autosaveExternal: Boolean = false,
    /** Password-protected PDFs kept in the library when opened; off, the viewer menu offers Save to library instead. */
    val autosaveEncrypted: Boolean = false,
)

/** App preferences in plain SharedPreferences, read synchronously so a forced theme is known before the first frame. */
class SettingsRepository(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(read())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    /** The latest values, for code that is not collecting the flow. */
    val current: AppSettings get() = _settings.value

    fun setThemeMode(mode: ThemeMode) {
        prefs.edit { putString(KEY_THEME_MODE, mode.name) }
        _settings.update { it.copy(themeMode = mode) }
    }

    fun setAutosaveExternal(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_AUTOSAVE_EXTERNAL, enabled) }
        _settings.update { it.copy(autosaveExternal = enabled) }
    }

    fun setAutosaveEncrypted(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_AUTOSAVE_ENCRYPTED, enabled) }
        _settings.update { it.copy(autosaveEncrypted = enabled) }
    }

    private fun read(): AppSettings {
        val defaults = AppSettings()
        val storedTheme = prefs.getString(KEY_THEME_MODE, null)
        return AppSettings(
            // An unknown name (a value from a newer build, say) falls back instead of crashing on launch.
            themeMode = ThemeMode.entries.firstOrNull { it.name == storedTheme } ?: defaults.themeMode,
            autosaveExternal = prefs.getBoolean(KEY_AUTOSAVE_EXTERNAL, defaults.autosaveExternal),
            autosaveEncrypted = prefs.getBoolean(KEY_AUTOSAVE_ENCRYPTED, defaults.autosaveEncrypted),
        )
    }

    private companion object {
        const val PREFS_NAME = "papyrus_settings"
        const val KEY_THEME_MODE = "theme_mode"
        const val KEY_AUTOSAVE_EXTERNAL = "autosave_external"
        const val KEY_AUTOSAVE_ENCRYPTED = "autosave_encrypted"
    }
}
