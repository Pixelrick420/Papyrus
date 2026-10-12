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
    /** Plain text, logs and code wrap at the screen's edge; off, each line stays on one row and the view scrolls sideways. */
    val wrapLines: Boolean = true,
    /** A gutter of line numbers beside plain text, logs and code. */
    val showLineNumbers: Boolean = false,
    /** Colour for comments, strings and keywords in the source and config files that have a language definition. */
    val syntaxHighlighting: Boolean = true,
    /** Use colour-blind safe palette. */
    val colorblindMode: Boolean = false,
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

    fun setWrapLines(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_WRAP_LINES, enabled) }
        _settings.update { it.copy(wrapLines = enabled) }
    }

    fun setShowLineNumbers(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_LINE_NUMBERS, enabled) }
        _settings.update { it.copy(showLineNumbers = enabled) }
    }

    fun setSyntaxHighlighting(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_SYNTAX_HIGHLIGHTING, enabled) }
        _settings.update { it.copy(syntaxHighlighting = enabled) }
    }

    fun setColorblindMode(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_COLORBLIND_MODE, enabled) }
        _settings.update { it.copy(colorblindMode = enabled) }
    }

    private fun read(): AppSettings {
        val defaults = AppSettings()
        val storedTheme = prefs.getString(KEY_THEME_MODE, null)
        return AppSettings(
            // An unknown name (a value from a newer build, say) falls back instead of crashing on launch.
            themeMode = ThemeMode.entries.firstOrNull { it.name == storedTheme } ?: defaults.themeMode,
            autosaveExternal = prefs.getBoolean(KEY_AUTOSAVE_EXTERNAL, defaults.autosaveExternal),
            autosaveEncrypted = prefs.getBoolean(KEY_AUTOSAVE_ENCRYPTED, defaults.autosaveEncrypted),
            wrapLines = prefs.getBoolean(KEY_WRAP_LINES, defaults.wrapLines),
            showLineNumbers = prefs.getBoolean(KEY_LINE_NUMBERS, defaults.showLineNumbers),
            syntaxHighlighting = prefs.getBoolean(KEY_SYNTAX_HIGHLIGHTING, defaults.syntaxHighlighting),
            colorblindMode = prefs.getBoolean(KEY_COLORBLIND_MODE, defaults.colorblindMode),
        )
    }

    private companion object {
        const val PREFS_NAME = "papyrus_settings"
        const val KEY_THEME_MODE = "theme_mode"
        const val KEY_AUTOSAVE_EXTERNAL = "autosave_external"
        const val KEY_AUTOSAVE_ENCRYPTED = "autosave_encrypted"
        const val KEY_WRAP_LINES = "wrap_lines"
        const val KEY_LINE_NUMBERS = "line_numbers"
        const val KEY_SYNTAX_HIGHLIGHTING = "syntax_highlighting"
        const val KEY_COLORBLIND_MODE = "colorblind_mode"
    }
}
