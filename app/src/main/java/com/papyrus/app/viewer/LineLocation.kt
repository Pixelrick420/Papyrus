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
