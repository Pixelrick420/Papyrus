package com.papyrus.app.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

enum class DocumentFormat(
    val label: String,
    val extensions: Set<String>,
    val mimeTypes: Set<String>,
) {
    PDF("PDF", setOf("pdf"), setOf("application/pdf")),
    MARKDOWN("MD", setOf("md", "markdown", "mdown"), setOf("text/markdown", "text/x-markdown")),
    TEXT("TXT", setOf("txt", "text", "log"), setOf("text/plain")),
    DOC("DOC", setOf("doc"), setOf("application/msword")),
    // Macro-enabled files and templates share the .docx package layout (`word/document.xml`); nothing
    // here runs a macro, it only reads the body. `.dot` is left out on purpose: it is also Graphviz's
    // extension, and a text file must not be claimed as a Word 97 template.
    DOCX(
        "DOCX", setOf("docx", "docm", "dotx", "dotm"),
        setOf(
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.template",
            // Providers disagree on case for these two (IANA writes macroEnabled, MimeTypeMap lowercases
            // it), and the picker filter compares case-sensitively, so both spellings are offered.
            "application/vnd.ms-word.document.macroEnabled.12",
            "application/vnd.ms-word.document.macroenabled.12",
            "application/vnd.ms-word.template.macroEnabled.12",
            "application/vnd.ms-word.template.macroenabled.12",
        ),
    ),
    ODT(
        "ODT", setOf("odt", "ott"),
        setOf("application/vnd.oasis.opendocument.text", "application/vnd.oasis.opendocument.text-template"),
    ),

    /** Split from TEXT only so the home list labels a .py file CODE; it renders identically. */
    CODE(
        "CODE",
        setOf(
            "c", "h", "cc", "cpp", "cxx", "hpp", "cs", "java", "kt", "kts", "scala", "groovy", "clj",
            "js", "jsx", "mjs", "cjs", "ts", "tsx", "py", "pyw", "rb", "rake", "go", "rs", "php",
            "swift", "m", "mm", "dart", "lua", "pl", "r", "ex", "exs", "erl", "hs", "ml", "jl",
            "sh", "bash", "zsh", "fish", "ksh", "ps1", "bat", "cmd",
            "sql", "graphql", "gql",
            "json", "jsonc", "json5", "yaml", "yml", "toml", "ini", "cfg", "conf", "env", "properties",
            "xml", "svg", "html", "htm", "css", "scss", "sass", "less", "vue", "svelte", "gradle",
            "cmake", "mk", "makefile", "dockerfile", "patch", "diff", "gitignore", "editorconfig",
        ),
        // No MIME types: providers report these as text/plain or octet-stream, which the picker filter admits anyway.
        emptySet(),
    ),
    UNKNOWN("?", emptySet(), emptySet());

    companion object {
        /** Picker MIME filter. octet-stream and the text wildcard cover providers that mislabel .md and .docx. */
        val pickerMimeTypes: Array<String> =
            (entries.filter { it != UNKNOWN }.flatMap { it.mimeTypes } + "text/*" + "application/octet-stream")
                .toTypedArray()

        /** Extension beats MIME; the whole name is matched only when there is no dot, or `report.conf.txt` claims `conf`. */
        fun detect(displayName: String?, mimeType: String?): DocumentFormat {
            val name = displayName?.lowercase().orEmpty()
            val ext = name.substringAfterLast('.', "").let { if (name.contains('.')) it else "" }
            val mime = mimeType?.lowercase()
            return entries.firstOrNull { ext.isNotEmpty() && ext in it.extensions }
                ?: entries.firstOrNull { name.isNotEmpty() && name in it.extensions }
                ?: entries.firstOrNull { format -> mime != null && format.mimeTypes.any { it.equals(mime, ignoreCase = true) } }
                ?: UNKNOWN
        }
    }
}

/**
 * No MIME type or page count: the info sheet is the only reader of either, so it takes them live
 * from the provider and the open PDF. No `lastOpenedAt` index either: the one list query reads
 * every row, so SQLite gains nothing from it. Add it back with the sort and filter work.
 */
@Entity(
    tableName = "documents",
    indices = [Index(value = ["uri"], unique = true)],
)
data class DocumentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** SAF content URI (document or tree child). */
    val uri: String,
    val title: String,
    val format: DocumentFormat,
    val sizeBytes: Long,
    val createdAt: Long,
    val lastOpenedAt: Long,
)
