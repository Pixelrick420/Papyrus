package com.papyrus.app.data

import androidx.room.ColumnInfo
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
    // Macro-enabled files and templates share the .docx layout (`word/document.xml`); nothing runs a macro here.
    // `.dot` is out: it is also Graphviz's extension, so a text file is not read as a Word 97 template.
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
        // No MIME types: providers report these as text/plain or octet-stream, which the picker
        // filter admits anyway.
        emptySet(),
    ),
    UNKNOWN("?", emptySet(), emptySet());

    companion object {
        /**
         * Picker MIME filter; octet-stream and the text wildcard cover providers that mislabel .md/.docx.
         */
        val pickerMimeTypes: Array<String> =
            (entries.filter { it != UNKNOWN }.flatMap { it.mimeTypes } + "text/*" + "application/octet-stream")
                .toTypedArray()

        /**
         * Extension beats MIME; the whole name is matched only when there is no dot, or `report.conf.txt`
         * claims `conf`.
         */
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
 * No MIME type, page count or `lastOpenedAt` index: the info sheet reads the first two live, and the one
 * list query reads every row (sorting and filtering happen in memory, in [libraryView]), so an index
 * would buy nothing.
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
    /**
     * Pinned by the reader. The default is spelled out so the migration's `ADD COLUMN ... DEFAULT 0` matches
     * Room's expectation; `upsertByUri` copies the row, so the flag survives re-opening and re-granting.
     */
    @ColumnInfo(defaultValue = "0") val isFavourite: Boolean = false,
)

/**
 * This row as it is after being opened again as [fresh]: what the provider reports now (name, format,
 * size) and the new open time. Everything the reader owns survives: the id (so progress and bookmarks keep
 * their parent), when it was added, the stored URI and the favourite flag. Never replace the row instead:
 * a replace is a delete plus an insert and would cascade that away.
 */
fun DocumentEntity.reopenedWith(fresh: DocumentEntity): DocumentEntity = copy(
    title = fresh.title,
    format = fresh.format,
    sizeBytes = fresh.sizeBytes,
    lastOpenedAt = fresh.lastOpenedAt,
)
