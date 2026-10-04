package com.papyrus.app.viewer

/**
 * Decodes a text file's bytes: UTF-8 unless a byte-order mark says otherwise, never leaving the BOM
 * in the string.
 *
 * The BOM matters most for Markdown: U+FEFF is not CommonMark whitespace, so `\uFEFF# Title` parses
 * as a paragraph showing a literal `# Title`. Windows editors save UTF-8 with a BOM by default, and
 * PowerShell's `>` writes UTF-16LE, which UTF-8 decoding turns into NUL-separated garbage.
 */
internal fun decodeText(bytes: ByteArray): String = when {
    bytes.startsWith(0xEF, 0xBB, 0xBF) -> String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
    bytes.startsWith(0xFF, 0xFE) -> String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
    bytes.startsWith(0xFE, 0xFF) -> String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
    else -> String(bytes, Charsets.UTF_8)
}

private fun ByteArray.startsWith(vararg prefix: Int): Boolean =
    size >= prefix.size && prefix.indices.all { this[it] == prefix[it].toByte() }
