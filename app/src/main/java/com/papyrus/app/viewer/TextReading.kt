package com.papyrus.app.viewer

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.io.Reader
import java.io.SequenceInputStream
import java.nio.charset.Charset

/** Lines per `LazyColumn` item; a chunk's first line is `index * this + 1`. */
const val TEXT_LINES_PER_CHUNK = 40

/**
 * A text file as the plain-text viewer shows it. [lineCount] counts a trailing empty line, like an
 * editor; [maxLineColumns] is the widest line in monospace columns, what a no-wrap view sizes from.
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
 * Decodes straight into chunks, so a large file is never held as bytes, then one string, then chunks.
 * Lines end at `\n`, `\r\n` or a lone `\r`; reads at most [maxBytes]; [forced] skips detection.
 */
fun readTextChunks(
    input: InputStream,
    maxBytes: Int,
    forced: Charset? = null,
    guesser: CharsetGuesser? = null,
    linesPerChunk: Int = TEXT_LINES_PER_CHUNK,
): TextChunks {
    val opened = openText(input, maxBytes, forced, guesser)
    val assembler = LineAssembler(linesPerChunk)
    val buffer = CharArray(READ_BUFFER_CHARS)
    while (true) {
        val read = opened.reader.read(buffer)
        if (read < 0) break
        assembler.append(buffer, read)
    }
    return assembler.finish(opened.encoding, opened.truncated)
}

/**
 * The same chunking [readTextChunks] applies, over an already-decoded string — the pretty-printed
 * path, which holds the whole file as text first. Feeds [LineAssembler] in buffer-sized blocks, so
 * no second whole-file copy is made.
 */
fun chunkText(
    text: String,
    encoding: TextEncoding,
    truncated: Boolean,
    linesPerChunk: Int = TEXT_LINES_PER_CHUNK,
): TextChunks {
    val assembler = LineAssembler(linesPerChunk)
    val buffer = CharArray(READ_BUFFER_CHARS)
    var at = 0
    while (at < text.length) {
        val length = minOf(buffer.size, text.length - at)
        text.toCharArray(buffer, 0, at, at + length)
        assembler.append(buffer, length)
        at += length
    }
    return assembler.finish(encoding, truncated)
}

/**
 * The viewer's line rules, in one place for both read paths: lines end at `\n`, `\r\n` or a lone
 * `\r` (`\r\n` counted once, even across a buffer edge); 40 lines to a chunk, each terminated by
 * `\n`; a chunk is only full when its last line ends it; [lineCount] counts a trailing empty line;
 * [maxLineColumns] is the widest line in monospace columns.
 */
internal class LineAssembler(private val linesPerChunk: Int) {

    private val chunks = ArrayList<String>()
    private val chunk = StringBuilder()
    private var linesInChunk = 0
    private var lineCount = 1
    private var column = 0
    private var maxColumns = 0
    private var afterCarriageReturn = false

    fun append(chars: CharArray, length: Int) {
        for (i in 0 until length) {
            val c = chars[i]
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

    fun finish(encoding: TextEncoding, truncated: Boolean): TextChunks {
        if (column > maxColumns) maxColumns = column
        if (truncated) chunk.dropCutCharacter()
        // Last chunk, unless the text ended on a chunk's final newline, which would add an empty one.
        if (chunk.isNotEmpty() || chunks.isEmpty()) chunks += chunk.toString()
        return TextChunks(chunks, lineCount, maxColumns, encoding, truncated)
    }
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
 * Reads a sample to choose the charset, then hands it back in front of the rest of the stream, so
 * the file is opened once; providers differ on whether a second open is allowed.
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

/** Ends after [remaining] bytes and notes whether the source still had more: the file was cut short. */
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

/** A cut through a multi-byte character decodes to U+FFFD, which is the cut, not the file's content. */
private fun StringBuilder.dropCutCharacter() {
    if (isNotEmpty() && last() == '\uFFFD') setLength(length - 1)
}

private fun columnsOf(c: Char): Int = when {
    c == '\t' -> TAB_COLUMNS
    c.code < FIRST_WIDE -> 1
    isWide(c) -> 2
    else -> 1
}

/** East Asian wide and fullwidth blocks, enough to size a scroll extent; a surrogate counts one, so
 * a pair counts two. */
private const val FIRST_WIDE = 0x1100

private fun isWide(c: Char): Boolean =
    c in '\u1100'..'\u115F' || c in '\u2E80'..'\uA4CF' || c in '\uAC00'..'\uD7A3' ||
        c in '\uF900'..'\uFAFF' || c in '\uFE30'..'\uFE6F' || c in '\uFF00'..'\uFF60' || c in '\uFFE0'..'\uFFE6'
