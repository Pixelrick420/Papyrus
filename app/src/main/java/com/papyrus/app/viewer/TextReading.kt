package com.papyrus.app.viewer

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.io.Reader
import java.io.SequenceInputStream
import java.nio.charset.Charset

/** Lines per `LazyColumn` item of the plain-text viewer; a chunk's first line is `index * this + 1`. */
const val TEXT_LINES_PER_CHUNK = 40

/**
 * A text file as the plain-text viewer shows it. [lineCount] counts a trailing empty line, like an editor;
 * [maxLineColumns] is the widest line in monospace columns (wide CJK characters count twice, a tab four),
 * which is what a no-wrap view sizes its scroll extent from.
 */
class TextChunks(
    val chunks: List<String>,
    val lineCount: Int,
    val maxLineColumns: Int,
    val encoding: TextEncoding,
    val truncated: Boolean,
)

class DecodedString(val text: String, val encoding: TextEncoding, val truncated: Boolean)

/**
 * Decodes straight into chunks, so a large file is never held as bytes, then one big string, then
 * chunks. Lines end at `\n`, `\r\n` or a lone `\r`, and chunks join them with `\n`. Reads at most
 * [maxBytes]; [forced] skips detection.
 */
fun readTextChunks(
    input: InputStream,
    maxBytes: Int,
    forced: Charset? = null,
    guesser: CharsetGuesser? = null,
    linesPerChunk: Int = TEXT_LINES_PER_CHUNK,
): TextChunks {
    val opened = openText(input, maxBytes, forced, guesser)
    val buffer = CharArray(READ_BUFFER_CHARS)
    val chunks = ArrayList<String>()
    val chunk = StringBuilder()
    var linesInChunk = 0
    var lineCount = 1
    var column = 0
    var maxColumns = 0
    var afterCarriageReturn = false

    while (true) {
        val read = opened.reader.read(buffer)
        if (read < 0) break
        for (i in 0 until read) {
            val c = buffer[i]
            // The \n of a \r\n pair was already counted when its \r came through, possibly in the last buffer.
            if (c == '\n' && afterCarriageReturn) {
                afterCarriageReturn = false
                continue
            }
            afterCarriageReturn = c == '\r'
            if (c == '\n' || c == '\r') {
                lineCount++
                if (column > maxColumns) maxColumns = column
                column = 0
                if (++linesInChunk == linesPerChunk) {
                    chunks += chunk.toString()
                    chunk.setLength(0)
                    linesInChunk = 0
                } else {
                    chunk.append('\n')
                }
            } else {
                chunk.append(c)
                column += columnsOf(c)
            }
        }
    }
    if (column > maxColumns) maxColumns = column
    if (opened.truncated) chunk.dropCutCharacter()
    // The last chunk, unless the text ended exactly on a chunk's last newline: that would add an empty one.
    if (chunk.isNotEmpty() || chunks.isEmpty()) chunks += chunk.toString()
    return TextChunks(chunks, lineCount, maxColumns, opened.encoding, opened.truncated)
}

/** The whole file as one string, for Markdown, which Markwon takes whole. */
fun readTextString(
    input: InputStream,
    maxBytes: Int,
    forced: Charset? = null,
    guesser: CharsetGuesser? = null,
): DecodedString {
    val opened = openText(input, maxBytes, forced, guesser)
    val text = StringBuilder(opened.reader.readText())
    if (opened.truncated) text.dropCutCharacter()
    return DecodedString(text.toString(), opened.encoding, opened.truncated)
}

internal class OpenedText(
    val reader: Reader,
    val encoding: TextEncoding,
    private val limited: LimitedInputStream,
) {
    /** Only meaningful once [reader] has been read to its end. */
    val truncated: Boolean get() = limited.cutShort
}

/**
 * Reads a sample to choose the charset, then hands the sample back in front of the rest of the stream,
 * so the file is opened once; providers differ on whether a second open is allowed.
 */
internal fun openText(input: InputStream, maxBytes: Int, forced: Charset?, guesser: CharsetGuesser?): OpenedText {
    val head = ByteArray(TextEncodings.SAMPLE_BYTES)
    val got = input.readUpTo(head)
    val encoding = if (forced != null) {
        TextEncoding(forced, TextEncodings.bomLengthFor(forced, head, got), EncodingSource.MANUAL)
    } else {
        // A full sample may be a file's first 64 KB, so it can end mid-character.
        TextEncodings.detect(head, got, truncated = got == head.size, guesser = guesser)
    }
    val body = SequenceInputStream(ByteArrayInputStream(head, encoding.bomLength, got - encoding.bomLength), input)
    val limited = LimitedInputStream(body, maxBytes.toLong())
    return OpenedText(InputStreamReader(limited, encoding.charset), encoding, limited)
}

/** Ends at [limit] bytes, and notes whether the source still had more: that is the file being cut short. */
internal class LimitedInputStream(private val source: InputStream, private var remaining: Long) : InputStream() {

    var cutShort = false
        private set

    private var probed = false

    override fun read(): Int {
        if (remaining <= 0) return endOfLimit()
        val b = source.read()
        if (b >= 0) remaining--
        return b
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining <= 0) return endOfLimit()
        val n = source.read(buffer, offset, minOf(length.toLong(), remaining).toInt())
        if (n > 0) remaining -= n
        return n
    }

    override fun close() = source.close()

    private fun endOfLimit(): Int {
        if (!probed) {
            probed = true
            cutShort = source.read() >= 0
        }
        return -1
    }
}

private const val READ_BUFFER_CHARS = 8 * 1024
private const val TAB_COLUMNS = 4

private fun InputStream.readUpTo(buffer: ByteArray): Int {
    var total = 0
    while (total < buffer.size) {
        val n = read(buffer, total, buffer.size - total)
        if (n < 0) break
        total += n
    }
    return total
}

/** A cut through a multi-byte character decodes to U+FFFD, which is the cut and not the file's content. */
private fun StringBuilder.dropCutCharacter() {
    if (isNotEmpty() && last() == '\uFFFD') setLength(length - 1)
}

private fun columnsOf(c: Char): Int = when {
    c == '\t' -> TAB_COLUMNS
    c.code < FIRST_WIDE -> 1
    isWide(c) -> 2
    else -> 1
}

/** East Asian wide and fullwidth blocks, enough to size a scroll extent; a surrogate counts one, so a pair counts two. */
private const val FIRST_WIDE = 0x1100

private fun isWide(c: Char): Boolean =
    c in '\u1100'..'\u115F' || c in '\u2E80'..'\uA4CF' || c in '\uAC00'..'\uD7A3' ||
        c in '\uF900'..'\uFAFF' || c in '\uFE30'..'\uFE6F' || c in '\uFF00'..'\uFF60' || c in '\uFFE0'..'\uFFE6'
