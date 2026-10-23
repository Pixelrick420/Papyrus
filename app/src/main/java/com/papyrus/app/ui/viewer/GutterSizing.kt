package com.papyrus.app.ui.viewer

/**
 * How many digits the line-number column must hold, ignoring a trailing empty line left by a
 * file ending in newline.
 */
internal fun gutterDigits(lineCount: Int, endsWithNewline: Boolean): Int {
    val numbered = if (lineCount > 1 && endsWithNewline) lineCount - 1 else lineCount
    return numbered.coerceAtLeast(1).toString().length
}
