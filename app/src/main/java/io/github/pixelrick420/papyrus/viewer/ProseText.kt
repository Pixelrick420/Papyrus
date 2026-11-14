package io.github.pixelrick420.papyrus.viewer

// A log is columns and identifiers and source is not words, so both keep breaking at the screen edge; a `.txt` that looks like code is still prose.
internal fun isProse(displayName: String?, isCode: Boolean): Boolean {
    if (isCode) return false
    val name = displayName?.lowercase().orEmpty()
    // A name with no dot is its own extension, as everywhere else a name is matched.
    val extension = if (name.contains('.')) name.substringAfterLast('.') else name
    return extension != "log"
}
