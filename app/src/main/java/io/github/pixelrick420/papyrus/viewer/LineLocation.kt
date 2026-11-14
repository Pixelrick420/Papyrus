package io.github.pixelrick420.papyrus.viewer

// A file ending in a newline has an empty line after it that no gutter numbers and no one goes to.
fun numberedLines(lineCount: Int, endsWithNewline: Boolean): Int =
    if (lineCount > 1 && endsWithNewline) lineCount - 1 else lineCount

// Chunks are a fixed number of lines, so this is arithmetic and no chunk is read.
class LineTarget(val chunk: Int, val lineInChunk: Int, val fraction: Float)

// The last chunk is shorter than the rest, so it is measured by its own lines.
fun locateLine(line: Int, lines: Int, linesPerChunk: Int = TEXT_LINES_PER_CHUNK): LineTarget? {
    if (line !in 1..lines) return null
    val chunk = (line - 1) / linesPerChunk
    val lineInChunk = (line - 1) % linesPerChunk
    val linesInChunk = minOf(linesPerChunk, lines - chunk * linesPerChunk)
    return LineTarget(chunk, lineInChunk, lineInChunk / linesInChunk.toFloat())
}

// The nudge keeps a fraction [locateLine] made from rounding down to the line before it; a position past the end is the last line.
fun lineAt(chunk: Int, fraction: Float, lines: Int, linesPerChunk: Int = TEXT_LINES_PER_CHUNK): Int {
    if (lines < 1) return 1
    val first = chunk.coerceAtLeast(0).toLong() * linesPerChunk
    if (first >= lines) return lines
    val linesInChunk = minOf(linesPerChunk.toLong(), lines - first).toInt()
    val lineInChunk = ((fraction.coerceIn(0f, 1f) * linesInChunk) + LINE_NUDGE).toInt().coerceIn(0, linesInChunk - 1)
    return (first + lineInChunk + 1).toInt()
}

private const val LINE_NUDGE = 1e-3f

const val SNIPPET_CHARS = 32

// Reads no further than [max] characters into the line, so a minified file costs the same as a short one.
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
