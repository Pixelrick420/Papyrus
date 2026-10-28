package com.papyrus.app.viewer

/**
 * Whether a plain-text file is prose, the one kind worth hyphenating. A log is columns and identifiers, and
 * source is not words at all, so both keep breaking at the edge of the screen. [isCode] is how the viewer
 * opened the file; a `.txt` that looks like code is still shown as plain text, and is still prose.
 */
internal fun isProse(displayName: String?, isCode: Boolean): Boolean {
    if (isCode) return false
    val name = displayName?.lowercase().orEmpty()
    // A name with no dot is its own extension, as everywhere else a name is matched.
    val extension = if (name.contains('.')) name.substringAfterLast('.') else name
    return extension != "log"
}
