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
    /** Password-protected PDFs saved to the library on open; off, the menu offers Save to library. */
    val autosaveEncrypted: Boolean = false,
    /** Reopen a library document where the reader stopped. Off, the position is neither read nor
     * written and stored values apply again when it is back on. */
    val rememberPosition: Boolean = true,
    /** Text, logs and code wrap at the screen's edge; off, lines stay on one row and the view scrolls sideways. */
    val wrapLines: Boolean = false,
    val showLineNumbers: Boolean = false,
    /** Colour for comments, strings and keywords in source files that have a language definition. */
    val syntaxHighlighting: Boolean = true,
    /** JSON, XML, HTML and SVG files are indented as they open; off, they show exactly as stored. */
    val prettyPrint: Boolean = true,
    val colorblindMode: Boolean = false,
    /** How Home orders the library. */
    val librarySort: LibrarySort = LibrarySort.LAST_OPENED,
)

/** App preferences in plain SharedPreferences, read synchronously so a forced theme is known
 * before the first frame. */
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

    fun setRememberPosition(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_REMEMBER_POSITION, enabled) }
        _settings.update { it.copy(rememberPosition = enabled) }
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

    fun setPrettyPrint(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_PRETTY_PRINT, enabled) }
        _settings.update { it.copy(prettyPrint = enabled) }
    }

    fun setColorblindMode(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_COLORBLIND_MODE, enabled) }
        _settings.update { it.copy(colorblindMode = enabled) }
    }

    fun setLibrarySort(sort: LibrarySort) {
        prefs.edit { putString(KEY_LIBRARY_SORT, sort.name) }
        _settings.update { it.copy(librarySort = sort) }
    }

    private fun read(): AppSettings {
        val defaults = AppSettings()
        val storedTheme = prefs.getString(KEY_THEME_MODE, null)
        val storedSort = prefs.getString(KEY_LIBRARY_SORT, null)
        return AppSettings(
            // An unknown name (a value from a newer build, say) falls back instead of crashing on launch.
            themeMode = ThemeMode.entries.firstOrNull { it.name == storedTheme } ?: defaults.themeMode,
            autosaveExternal = prefs.getBoolean(KEY_AUTOSAVE_EXTERNAL, defaults.autosaveExternal),
            autosaveEncrypted = prefs.getBoolean(KEY_AUTOSAVE_ENCRYPTED, defaults.autosaveEncrypted),
            rememberPosition = prefs.getBoolean(KEY_REMEMBER_POSITION, defaults.rememberPosition),
            wrapLines = prefs.getBoolean(KEY_WRAP_LINES, defaults.wrapLines),
            showLineNumbers = prefs.getBoolean(KEY_LINE_NUMBERS, defaults.showLineNumbers),
            syntaxHighlighting = prefs.getBoolean(KEY_SYNTAX_HIGHLIGHTING, defaults.syntaxHighlighting),
            prettyPrint = prefs.getBoolean(KEY_PRETTY_PRINT, defaults.prettyPrint),
            colorblindMode = prefs.getBoolean(KEY_COLORBLIND_MODE, defaults.colorblindMode),
            librarySort = LibrarySort.entries.firstOrNull { it.name == storedSort } ?: defaults.librarySort,
        )
    }

    private companion object {
        const val PREFS_NAME = "papyrus_settings"
        const val KEY_THEME_MODE = "theme_mode"
        const val KEY_AUTOSAVE_EXTERNAL = "autosave_external"
        const val KEY_AUTOSAVE_ENCRYPTED = "autosave_encrypted"
        const val KEY_REMEMBER_POSITION = "remember_position"
        const val KEY_WRAP_LINES = "wrap_lines"
        const val KEY_LINE_NUMBERS = "line_numbers"
        const val KEY_SYNTAX_HIGHLIGHTING = "syntax_highlighting"
        const val KEY_PRETTY_PRINT = "pretty_print"
        const val KEY_COLORBLIND_MODE = "colorblind_mode"
        const val KEY_LIBRARY_SORT = "library_sort"
    }
}
