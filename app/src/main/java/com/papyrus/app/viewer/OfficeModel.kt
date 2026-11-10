package com.papyrus.app.viewer

import java.io.File

// Justified text is read as [START]: it opens wide gaps at phone widths, and the reader can already wrap freely.
enum class BlockAlign { START, CENTER, END }

// A model enum of its own, not the UI toolkit's: extraction must not depend on it. A block that says nothing inherits the document's.
enum class BlockDirection { LTR, RTL }

enum class ScriptShift { NONE, SUPER, SUB }

// Colours are `0xRRGGBB`; null means "the theme's own", so a document that says "automatic" is not pinned to black on a dark background.
data class CharFormat(
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val strike: Boolean = false,
    val script: ScriptShift = ScriptShift.NONE,
    val mono: Boolean = false,
    val color: Int? = null,
    val background: Int? = null,
    /** Only ever http(s), mailto or tel: see [safeLink]. */
    val link: String? = null,
    val bookLink: BookLink? = null,
) {
    val isPlain: Boolean get() = this == PLAIN

    companion object {
        val PLAIN = CharFormat()
    }
}

// Spans are an overlay: the block's `text` is the same string with or without them, which keeps every find offset valid.
data class TextSpan(val start: Int, val end: Int, val format: CharFormat)

// A cell covered by a rowspan above is absent: the renderer reads the merge off the origin cell, so a repeated blank would shift every later cell.
data class OfficeCell(
    val text: String,
    val column: Int = 0,
    val colspan: Int = 1,
    val rowspan: Int = 1,
    val spans: List<TextSpan> = emptyList(),
    val background: Int? = null,
    val align: BlockAlign = BlockAlign.START,
) {
    val lastColumn: Int get() = column + colspan - 1
}

// Not pixel-accurate to Word/LibreOffice: headings, paragraphs, lists, tables and inline images in document order is the fidelity targeted.
sealed interface OfficeBlock {
    data class Heading(
        val text: String,
        val level: Int,
        val spans: List<TextSpan> = emptyList(),
        val align: BlockAlign = BlockAlign.START,
        val direction: BlockDirection? = null,
    ) : OfficeBlock

    data class Paragraph(
        val text: String,
        val spans: List<TextSpan> = emptyList(),
        val align: BlockAlign = BlockAlign.START,
        val indent: Int = 0,
        val direction: BlockDirection? = null,
    ) : OfficeBlock

    // [marker] is drawn beside [text], never inside it, so find offsets stay on the document's own words.
    data class ListItem(
        val text: String,
        val marker: String,
        val level: Int,
        val spans: List<TextSpan> = emptyList(),
        val align: BlockAlign = BlockAlign.START,
        val direction: BlockDirection? = null,
    ) : OfficeBlock

    data class Table(val rows: List<List<OfficeCell>>) : OfficeBlock

    // The viewer downsamples on decode, so the bytes are not held resident.
    data class Image(
        val file: File,
        val mimeType: String,
        val widthDp: Float? = null,
        val altText: String? = null,
        val tintable: Boolean = false,
        val aspectRatio: Float? = null,
    ) : OfficeBlock

    data object Divider : OfficeBlock

    data class Note(
        val label: String,
        val text: String,
        val spans: List<TextSpan> = emptyList(),
        val byline: String? = null,
        val direction: BlockDirection? = null,
    ) : OfficeBlock
}

internal val OfficeBlock.direction: BlockDirection?
    get() = when (this) {
        is OfficeBlock.Heading -> this.direction
        is OfficeBlock.Paragraph -> this.direction
        is OfficeBlock.ListItem -> this.direction
        is OfficeBlock.Note -> this.direction
        is OfficeBlock.Table, is OfficeBlock.Image, OfficeBlock.Divider -> null
    }

data class LineText(val text: String, val spans: List<TextSpan>)

internal class RichSnapshot(val text: String, val spans: List<TextSpan>)

// Every span offset is taken from the string it describes; equal-format appends (a run split by spell-check or revision ids) coalesce into one.
internal class RichTextBuilder {
    private val text = StringBuilder()
    private val spans = ArrayList<TextSpan>()

    val length: Int get() = text.length

    fun isEmpty(): Boolean = text.isEmpty()

    fun isBlank(): Boolean = text.isBlank()

    fun append(value: String, format: CharFormat = CharFormat.PLAIN) {
        if (value.isEmpty()) return
        val start = text.length
        text.append(value)
        if (format.isPlain) return
        val last = spans.lastOrNull()
        if (last != null && last.end == start && last.format == format) {
            spans[spans.lastIndex] = last.copy(end = text.length)
        } else {
            spans += TextSpan(start, text.length, format)
        }
    }

    fun appendRich(value: String, valueSpans: List<TextSpan>) {
        val offset = text.length
        text.append(value)
        valueSpans.forEach { spans += TextSpan(it.start + offset, it.end + offset, it.format) }
    }

    // A span wholly after the insertion moves along; one that straddles it grows to cover it.
    fun insert(offset: Int, value: String) {
        if (value.isEmpty()) return
        val at = offset.coerceIn(0, text.length)
        text.insert(at, value)
        for (i in spans.indices) {
            val span = spans[i]
            spans[i] = when {
                span.start >= at -> TextSpan(span.start + value.length, span.end + value.length, span.format)
                span.end > at -> span.copy(end = span.end + value.length)
                else -> span
            }
        }
    }

    fun clear() {
        text.setLength(0)
        spans.clear()
    }

    fun snapshot(trim: (Char) -> Boolean = { false }): RichSnapshot {
        val whole = text.toString()
        var from = 0
        var to = whole.length
        while (from < to && trim(whole[from])) from++
        while (to > from && trim(whole[to - 1])) to--
        if (from == 0 && to == whole.length) return RichSnapshot(whole, spans.toList())
        return RichSnapshot(whole.substring(from, to), sliceSpans(spans, from, to))
    }
}

internal fun sliceSpans(spans: List<TextSpan>, from: Int, to: Int): List<TextSpan> {
    if (spans.isEmpty()) return spans
    val out = ArrayList<TextSpan>()
    for (span in spans) {
        val start = maxOf(span.start, from)
        val end = minOf(span.end, to)
        if (start < end) out += TextSpan(start - from, end - from, span.format)
    }
    return out
}

internal fun shiftSpans(spans: List<TextSpan>, by: Int): List<TextSpan> =
    spans.map { TextSpan(it.start + by, it.end + by, it.format) }

// Matches splitting on newlines and dropping empty lines exactly: each line is its own text, the way a cell's paragraphs are drawn.
internal fun splitLines(text: String, spans: List<TextSpan>): List<LineText> {
    val out = ArrayList<LineText>()
    var from = 0
    while (from <= text.length) {
        val newline = text.indexOf('\n', from)
        val end = if (newline < 0) text.length else newline
        if (end > from) out += LineText(text.substring(from, end), sliceSpans(spans, from, end))
        if (newline < 0) break
        from = newline + 1
    }
    return out
}

/** Marks "no colour": an explicit reset to the theme's, as opposed to "not said", which is null. */
internal const val COLOR_AUTO = -1

// Layers merge most specific over least: document defaults, paragraph style, character style, direct formatting.
internal class RunProps {
    var bold: Boolean? = null
    var italic: Boolean? = null
    var underline: Boolean? = null
    var strike: Boolean? = null
    var script: ScriptShift? = null
    var mono: Boolean? = null
    var color: Int? = null
    var background: Int? = null

    fun mergedOver(base: RunProps): RunProps {
        val out = RunProps()
        out.bold = bold ?: base.bold
        out.italic = italic ?: base.italic
        out.underline = underline ?: base.underline
        out.strike = strike ?: base.strike
        out.script = script ?: base.script
        out.mono = mono ?: base.mono
        out.color = color ?: base.color
        out.background = background ?: base.background
        return out
    }

    fun toFormat(link: String?): CharFormat = CharFormat(
        bold = bold == true,
        italic = italic == true,
        underline = underline == true,
        strike = strike == true,
        script = script ?: ScriptShift.NONE,
        mono = mono == true,
        color = color?.takeIf { it != COLOR_AUTO },
        background = background?.takeIf { it != COLOR_AUTO },
        link = link,
    )
}

internal fun parseHexColor(raw: String?): Int? {
    val hex = raw?.trim()?.removePrefix("#") ?: return null
    if (hex.length != HEX_COLOR_LENGTH) return null
    return hex.toIntOrNull(HEX_RADIX)
}

private const val HEX_COLOR_LENGTH = 6
private const val HEX_RADIX = 16

private val HIGHLIGHTS = mapOf(
    "yellow" to 0xFFFF00, "green" to 0x00FF00, "cyan" to 0x00FFFF, "magenta" to 0xFF00FF,
    "blue" to 0x0000FF, "red" to 0xFF0000, "darkBlue" to 0x000080, "darkCyan" to 0x008080,
    "darkGreen" to 0x008000, "darkMagenta" to 0x800080, "darkRed" to 0x800000, "darkYellow" to 0x808000,
    "darkGray" to 0x808080, "lightGray" to 0xC0C0C0, "black" to 0x000000, "white" to 0xFFFFFF,
)

internal fun highlightColor(name: String?): Int? = HIGHLIGHTS[name]

private val MONO_HINTS = listOf(
    "mono", "courier", "consolas", "menlo", "monaco", "lucida console", "source code", "cascadia",
    "inconsolata", "jetbrains",
)

internal fun isMonoFont(name: String?): Boolean {
    val lower = name?.lowercase() ?: return false
    return MONO_HINTS.any { lower.contains(it) }
}

private val SAFE_SCHEMES = listOf("http://", "https://", "mailto:", "tel:")

// A document chooses where its links go, and a tap opens them, so only schemes that cannot run
// anything are let through: no `javascript:`, `intent:`, `file:` or `content:`.
internal fun safeLink(url: String?): String? {
    val trimmed = url?.trim().orEmpty()
    val lower = trimmed.lowercase()
    return if (SAFE_SCHEMES.any { lower.startsWith(it) }) trimmed else null
}

// It never leaves the app, so it needs no scheme check of its own.
data class BookLink(val path: String, val fragment: String? = null)

internal fun indentLevel(twips: Int?): Int = ((twips ?: 0) / INDENT_STEP_TWIPS).coerceIn(0, MAX_INDENT_STEPS)

private const val INDENT_STEP_TWIPS = 360
private const val MAX_INDENT_STEPS = 6

internal fun lengthToTwips(value: String?): Int? {
    val text = value?.trim() ?: return null
    val unitStart = text.indexOfFirst { !(it.isDigit() || it == '.' || it == '-') }
    if (unitStart <= 0) return null
    val amount = text.substring(0, unitStart).toDoubleOrNull() ?: return null
    val perUnit = when (text.substring(unitStart).lowercase()) {
        "in" -> 1440.0
        "cm" -> 566.93
        "mm" -> 56.693
        "pt" -> 20.0
        "px" -> 15.0
        else -> return null
    }
    return (amount * perUnit).toInt()
}

internal fun twipsToDp(twips: Int): Float = twips / TWIPS_PER_DP

private const val TWIPS_PER_DP = 9f

internal fun alignOf(value: String?): BlockAlign? = when (value) {
    "center" -> BlockAlign.CENTER
    "right", "end" -> BlockAlign.END
    "left", "start", "both", "justify", "distribute" -> BlockAlign.START
    else -> null
}

private val DEFAULT_BULLETS = arrayOf("•", "◦", "▪")

private const val PRIVATE_USE_FIRST = 0xE000
private const val PRIVATE_USE_LAST = 0xF8FF

// A Symbol/Wingdings glyph in the private-use area, an empty glyph, or anything not one character falls back to a plain bullet that steps down with the level.
internal fun displayBullet(raw: String?, level: Int): String {
    val fallback = DEFAULT_BULLETS[level.coerceAtLeast(0) % DEFAULT_BULLETS.size]
    val glyph = raw?.trim().orEmpty()
    return when {
        glyph.isEmpty() -> fallback
        glyph == "o" -> "◦"
        glyph.length == 1 && glyph[0].code in PRIVATE_USE_FIRST..PRIVATE_USE_LAST -> fallback
        glyph.codePointCount(0, glyph.length) == 1 -> glyph
        else -> fallback
    }
}

internal fun formatListNumber(n: Int, format: String): String = when (format) {
    "lowerLetter" -> letters(n)
    "upperLetter" -> letters(n).uppercase()
    "lowerRoman" -> roman(n)
    "upperRoman" -> roman(n).uppercase()
    "decimalZero" -> if (n in 0..9) "0$n" else n.toString()
    else -> n.toString()
}

private const val ALPHABET = 26

/** 1 is a, 26 is z, 27 is aa: the spreadsheet-column scheme Word uses. */
private fun letters(n: Int): String {
    if (n <= 0) return n.toString()
    var rest = n
    val out = StringBuilder()
    while (rest > 0) {
        rest--
        out.append('a' + rest % ALPHABET)
        rest /= ALPHABET
    }
    return out.reverse().toString()
}

private val ROMAN = listOf(
    1000 to "m", 900 to "cm", 500 to "d", 400 to "cd", 100 to "c", 90 to "xc",
    50 to "l", 40 to "xl", 10 to "x", 9 to "ix", 5 to "v", 4 to "iv", 1 to "i",
)

private const val MAX_ROMAN = 3999

private fun roman(n: Int): String {
    if (n !in 1..MAX_ROMAN) return n.toString()
    var rest = n
    val out = StringBuilder()
    for ((value, symbol) in ROMAN) {
        while (rest >= value) {
            out.append(symbol)
            rest -= value
        }
    }
    return out.toString()
}
