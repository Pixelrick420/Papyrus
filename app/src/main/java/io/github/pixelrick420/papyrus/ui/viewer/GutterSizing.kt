package io.github.pixelrick420.papyrus.ui.viewer

import io.github.pixelrick420.papyrus.viewer.numberedLines

// How many digits the line-number column must hold, ignoring a trailing empty line left by a
// file ending in newline.
internal fun gutterDigits(lineCount: Int, endsWithNewline: Boolean): Int =
    numberedLines(lineCount, endsWithNewline).coerceAtLeast(1).toString().length
