package com.papyrus.app.data

import android.content.Context
import androidx.core.content.edit
import com.papyrus.app.viewer.PrettyPrint
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
    val autosaveExternal: Boolean = false,
    val autosaveEncrypted: Boolean = false,
    val rememberPosition: Boolean = true,
    val wrapLines: Boolean = false,
    val hyphenation: Boolean = true,
    val showLineNumbers: Boolean = false,
    val syntaxHighlighting: Boolean = true,
    val prettyPrint: Boolean = true,
    val tabSize: Int = PrettyPrint.DEFAULT_INDENT,
    val colorblindMode: Boolean = false,
    val nightMode: Boolean = false,
    val librarySort: LibrarySort = LibrarySort.LAST_OPENED,
    val contentIndexing: Boolean = true,
)

// Plain SharedPreferences, read synchronously so a forced theme is known before the first frame.
class SettingsRepository(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(read())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

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

    fun setHyphenation(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_HYPHENATION, enabled) }
        _settings.update { it.copy(hyphenation = enabled) }
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

    fun setTabSize(spaces: Int) {
        val size = PrettyPrint.indentOrDefault(spaces)
        prefs.edit { putInt(KEY_TAB_SIZE, size) }
        _settings.update { it.copy(tabSize = size) }
    }

    fun setColorblindMode(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_COLORBLIND_MODE, enabled) }
        _settings.update { it.copy(colorblindMode = enabled) }
    }

    fun setNightMode(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_NIGHT_MODE, enabled) }
        _settings.update { it.copy(nightMode = enabled) }
    }

    fun setLibrarySort(sort: LibrarySort) {
        prefs.edit { putString(KEY_LIBRARY_SORT, sort.name) }
        _settings.update { it.copy(librarySort = sort) }
    }

    fun setContentIndexing(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_CONTENT_INDEXING, enabled) }
        _settings.update { it.copy(contentIndexing = enabled) }
    }

    // Asked once, at the first Read aloud; a refusal is an answer.
    fun notificationPromptShown(): Boolean = prefs.getBoolean(KEY_NOTIFICATION_PROMPT_SHOWN, false)

    fun markNotificationPromptShown() {
        prefs.edit { putBoolean(KEY_NOTIFICATION_PROMPT_SHOWN, true) }
    }

    // Clearing the file alone leaves values this process already read in place.
    fun resetToDefaults() {
        prefs.edit { clear() }
        _settings.value = AppSettings()
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
            hyphenation = prefs.getBoolean(KEY_HYPHENATION, defaults.hyphenation),
            showLineNumbers = prefs.getBoolean(KEY_LINE_NUMBERS, defaults.showLineNumbers),
            syntaxHighlighting = prefs.getBoolean(KEY_SYNTAX_HIGHLIGHTING, defaults.syntaxHighlighting),
            prettyPrint = prefs.getBoolean(KEY_PRETTY_PRINT, defaults.prettyPrint),
            tabSize = PrettyPrint.indentOrDefault(prefs.getInt(KEY_TAB_SIZE, defaults.tabSize)),
            colorblindMode = prefs.getBoolean(KEY_COLORBLIND_MODE, defaults.colorblindMode),
            nightMode = prefs.getBoolean(KEY_NIGHT_MODE, defaults.nightMode),
            librarySort = LibrarySort.entries.firstOrNull { it.name == storedSort } ?: defaults.librarySort,
            contentIndexing = prefs.getBoolean(KEY_CONTENT_INDEXING, defaults.contentIndexing),
        )
    }

    private companion object {
        const val PREFS_NAME = "papyrus_settings"
        const val KEY_THEME_MODE = "theme_mode"
        const val KEY_AUTOSAVE_EXTERNAL = "autosave_external"
        const val KEY_AUTOSAVE_ENCRYPTED = "autosave_encrypted"
        const val KEY_REMEMBER_POSITION = "remember_position"
        const val KEY_WRAP_LINES = "wrap_lines"
        const val KEY_HYPHENATION = "hyphenation"
        const val KEY_LINE_NUMBERS = "line_numbers"
        const val KEY_SYNTAX_HIGHLIGHTING = "syntax_highlighting"
        const val KEY_PRETTY_PRINT = "pretty_print"
        const val KEY_TAB_SIZE = "tab_size"
        const val KEY_COLORBLIND_MODE = "colorblind_mode"
        const val KEY_NIGHT_MODE = "night_mode"
        const val KEY_LIBRARY_SORT = "library_sort"
        const val KEY_CONTENT_INDEXING = "content_indexing"
        const val KEY_NOTIFICATION_PROMPT_SHOWN = "notification_prompt_shown"
    }
}
