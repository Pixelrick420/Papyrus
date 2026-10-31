package com.papyrus.app.viewer

/**
 * The lines a reader counts in a file the reader found [lineCount] lines in: a file ending in a newline has
 * an empty line after it that no gutter numbers and no one goes to. [lineCount] is [TextChunks.lineCount].
 */
fun numberedLines(lineCount: Int, endsWithNewline: Boolean): Int =
    if (lineCount > 1 && endsWithNewline) lineCount - 1 else lineCount

/**
 * Where a line is among the chunks: the chunk, the line's place in it counting from 0, and how far down the
 * chunk that is, from 0 to just under 1, which is how the viewer is told to scroll there. Chunks are a fixed
 * number of lines, so this is arithmetic and no chunk is read; the index only matters when the chunk is.
 */
class LineTarget(val chunk: Int, val lineInChunk: Int, val fraction: Float)

/**
 * Where the one-based [line] of a file of [lines] numbered lines is, or null when there is no such line.
 * The last chunk is shorter than the rest, so it is measured by its own lines.
 */
fun locateLine(line: Int, lines: Int, linesPerChunk: Int = TEXT_LINES_PER_CHUNK): LineTarget? {
    if (line !in 1..lines) return null
    val chunk = (line - 1) / linesPerChunk
    val lineInChunk = (line - 1) % linesPerChunk
    val linesInChunk = minOf(linesPerChunk, lines - chunk * linesPerChunk)
    return LineTarget(chunk, lineInChunk, lineInChunk / linesInChunk.toFloat())
}

/**
 * The line a [fraction] of the way down chunk [chunk] is at, which is [locateLine] the other way: where the
 * top of the screen is, in lines. A position past the end, as the notice after the last chunk is, is the last
 * line. The nudge keeps a fraction [locateLine] made from rounding down to the line before it.
 */
fun lineAt(chunk: Int, fraction: Float, lines: Int, linesPerChunk: Int = TEXT_LINES_PER_CHUNK): Int {
    if (lines < 1) return 1
    val first = chunk.coerceAtLeast(0).toLong() * linesPerChunk
    if (first >= lines) return lines
    val linesInChunk = minOf(linesPerChunk.toLong(), lines - first).toInt()
    val lineInChunk = ((fraction.coerceIn(0f, 1f) * linesInChunk) + LINE_NUDGE).toInt().coerceIn(0, linesInChunk - 1)
    return (first + lineInChunk + 1).toInt()
}

private const val LINE_NUDGE = 1e-3f

/** How much of a line [lineSnippet] shows. */
const val SNIPPET_CHARS = 32

/**
 * A look at the line [lineInChunk], counting from 0, of [chunk]: its text without the indent, cut to [max]
 * characters with an ellipsis if there is more, and empty for a blank line or one the chunk does not have.
 * Reads no further than [max] characters into the line, so a line that is a whole minified file costs the same.
 */
fun lineSnippet(chunk: String, lineInChunk: Int, max: Int = SNIPPET_CHARS): String {
    var start = 0
    repeat(lineInChunk.coerceAtLeast(0)) {
        val newline = chunk.indexOf('\n', start)
        if (newline < 0) return ""
        start = newline + 1
    }
    while (start < chunk.length && chunk[start] != '\n' && chunk[start].isWhitespace()) start++
    var end = start
    while (end < chunk.length && chunk[end] != '\n' && end - start < max) end++
    if (end == start) return ""
    val more = end < chunk.length && chunk[end] != '\n'
    // Not between the halves of a character outside the BMP.
    if (more && chunk[end - 1].isHighSurrogate()) end--
    val text = chunk.substring(start, end).replace('\t', ' ').trimEnd()
    return if (more) "$text\u2026" else text
}
