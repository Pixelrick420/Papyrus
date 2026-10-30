package com.papyrus.app.viewer

import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.Charset

private const val LF = '\n'.code.toByte()
private const val CR = '\r'.code.toByte()
private const val INDEX_READ_CHARS = 8 * 1024
private const val INITIAL_CHECKPOINTS = 64

/**
 * How a large file is carved up and kept. A chunk is [linesPerChunk] lines, the unit saved positions and
 * bookmarks are in; the index remembers where one starts every [checkpointBytes] bytes or [checkpointChunks]
 * chunks, whichever comes first, so a chunk is at most one such span of decoding away. A chunk longer than
 * [maxChunkBytes] ends the read there, like the in-memory limit did for the whole file. Decoded chunks are
 * kept while they fit in [cacheChunks] and [cacheChars].
 */
internal data class ChunkLayout(
    val linesPerChunk: Int = TEXT_LINES_PER_CHUNK,
    val checkpointBytes: Int = 64 * 1024,
    val checkpointChunks: Int = 64,
    val maxChunkBytes: Long = 32L * 1024 * 1024,
    val cacheChunks: Int = 64,
    val cacheChars: Int = 4 * 1024 * 1024,
)

/** Told where chunks begin and end as bytes pass through a [ChunkSplitter]. */
internal interface ChunkListener {
    /** Chunk [index] begins at byte [offset]. */
    fun chunkStarted(index: Int, offset: Long)

    /** Chunk [index] is the bytes from [start] to [end], where the terminator that closes it begins. */
    fun chunkEnded(index: Int, start: Long, end: Long)
}

/**
 * [LineAssembler]'s line rules over raw bytes, so a chunk's extent is known without decoding it: lines end
 * at `\n`, `\r\n` or a lone `\r`, `\r\n` counted once even across a feed edge; a chunk ends at its
 * [linesPerChunk]th terminator, which belongs to neither chunk; the bytes after a final terminator that
 * closes a chunk are not another. Safe for UTF-8 and single-byte charsets, where those bytes are never
 * part of a longer character. The index pass and the decoder of a span both run this, so they cannot
 * disagree about a boundary.
 */
internal class ChunkSplitter(
    private val linesPerChunk: Int,
    startOffset: Long,
    startChunk: Int,
    private val listener: ChunkListener,
) {
    /** The offset of the next byte. */
    var position = startOffset
        private set

    /** Terminators seen, so `lineCount - 1`. */
    private var terminators = 0L
    private var chunkIndex = startChunk
    private var chunkStart = startOffset
    private var linesInChunk = 0
    private var afterCarriageReturn = false

    /** A chunk just closed: the next one starts at the next byte that is not the `\n` of a `\r\n`. */
    private var starting = false

    val lineCount: Int get() = (terminators + 1).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

    init {
        listener.chunkStarted(startChunk, startOffset)
    }

    /** How many more bytes the chunk in progress may take before it is [cap] long. */
    fun roomIn(cap: Long): Long = if (starting) cap else chunkStart + cap - position

    fun feed(bytes: ByteArray, offset: Int, length: Int) {
        for (i in offset until offset + length) accept(bytes[i])
    }

    private fun accept(b: Byte) {
        // The \n of a \r\n pair was already counted when its \r came through, possibly in the last feed.
        if (b == LF && afterCarriageReturn) {
            afterCarriageReturn = false
            position++
            return
        }
        afterCarriageReturn = false
        if (starting) {
            starting = false
            chunkStart = position
            listener.chunkStarted(chunkIndex, position)
        }
        if (b == LF || b == CR) {
            terminators++
            afterCarriageReturn = b == CR
            if (++linesInChunk == linesPerChunk) {
                listener.chunkEnded(chunkIndex, chunkStart, position)
                chunkIndex++
                linesInChunk = 0
                starting = true
            }
        }
        position++
    }

    /** Ends the chunk in progress at the last byte fed, unless the data ended on a chunk's final terminator. */
    fun finish() {
        if (starting) return
        // An empty file is one empty chunk; after that, no chunk is empty.
        if (position > chunkStart || chunkIndex == 0) listener.chunkEnded(chunkIndex, chunkStart, position)
    }
}

/**
 * Where chunks start, sparsely: a checkpoint is a chunk and the byte it begins at, kept every
 * [ChunkLayout.checkpointBytes] bytes or [ChunkLayout.checkpointChunks] chunks. The chunks from one checkpoint
 * up to the next are a span, the most that is decoded to reach any chunk in it. A chunk's first line is
 * `chunk * linesPerChunk + 1`, so the index holds no line numbers of its own.
 */
internal class ChunkIndex(
    val chunkCount: Int,
    val lineCount: Int,
    val maxLineColumns: Int,
    /** The offset the read ended at: the file's end, or where a chunk over the limit was cut. */
    val contentEnd: Long,
    val truncated: Boolean,
    val linesPerChunk: Int,
    private val checkpointChunks: IntArray,
    private val checkpointOffsets: LongArray,
) {
    val spanCount: Int get() = checkpointChunks.size

    /** The span chunk [chunk] is in. */
    fun spanOf(chunk: Int): Int {
        require(chunk in 0 until chunkCount) { "chunk $chunk of $chunkCount" }
        val at = checkpointChunks.binarySearch(chunk)
        return if (at >= 0) at else -at - 2
    }

    fun firstChunkOf(span: Int): Int = checkpointChunks[span]

    /** One past the span's last chunk. */
    fun endChunkOf(span: Int): Int = if (span + 1 < spanCount) checkpointChunks[span + 1] else chunkCount

    fun startOffsetOf(span: Int): Long = checkpointOffsets[span]

    /** Where the span's bytes end: the next span's start, which is just past its last chunk's terminator. */
    fun endOffsetOf(span: Int): Long = if (span + 1 < spanCount) checkpointOffsets[span + 1] else contentEnd
}

/**
 * One streaming pass over [input], which is [charset] text from [startOffset], that notes where chunks start
 * and how wide the widest line is, and keeps no text. A chunk that would pass [ChunkLayout.maxChunkBytes]
 * ends the pass there and the index is [ChunkIndex.truncated]. [ensureActive] runs before every read.
 */
internal fun buildChunkIndex(
    input: InputStream,
    charset: Charset,
    startOffset: Long,
    layout: ChunkLayout,
    ensureActive: () -> Unit = {},
): ChunkIndex {
    val builder = IndexBuilder(layout)
    val splitter = ChunkSplitter(layout.linesPerChunk, startOffset, 0, builder)
    val stream = IndexingStream(input, splitter, layout.maxChunkBytes, ensureActive)
    val reader = InputStreamReader(stream, charset)
    val buffer = CharArray(INDEX_READ_CHARS)
    var column = 0
    var widest = 0
    while (true) {
        val read = reader.read(buffer)
        if (read < 0) break
        for (i in 0 until read) {
            val c = buffer[i]
            if (c == '\n' || c == '\r') {
                if (column > widest) widest = column
                column = 0
            } else {
                column += columnsOf(c)
            }
        }
    }
    splitter.finish()
    if (column > widest) widest = column
    return builder.build(splitter.lineCount, widest, splitter.position, stream.cut)
}

private class IndexBuilder(private val layout: ChunkLayout) : ChunkListener {

    private var chunkCount = 0
    private var checkpointChunks = IntArray(INITIAL_CHECKPOINTS)
    private var checkpointOffsets = LongArray(INITIAL_CHECKPOINTS)
    private var checkpoints = 0

    override fun chunkStarted(index: Int, offset: Long) {
        val last = checkpoints - 1
        val due = last < 0 ||
            offset - checkpointOffsets[last] >= layout.checkpointBytes ||
            index - checkpointChunks[last] >= layout.checkpointChunks
        if (!due) return
        if (checkpoints == checkpointChunks.size) {
            checkpointChunks = checkpointChunks.copyOf(checkpoints * 2)
            checkpointOffsets = checkpointOffsets.copyOf(checkpoints * 2)
        }
        checkpointChunks[checkpoints] = index
        checkpointOffsets[checkpoints] = offset
        checkpoints++
    }

    override fun chunkEnded(index: Int, start: Long, end: Long) {
        chunkCount = index + 1
    }

    fun build(lineCount: Int, maxLineColumns: Int, contentEnd: Long, truncated: Boolean) = ChunkIndex(
        chunkCount = chunkCount,
        lineCount = lineCount,
        maxLineColumns = maxLineColumns,
        contentEnd = contentEnd,
        truncated = truncated,
        linesPerChunk = layout.linesPerChunk,
        checkpointChunks = checkpointChunks.copyOf(checkpoints),
        checkpointOffsets = checkpointOffsets.copyOf(checkpoints),
    )
}

/**
 * Passes bytes to the [splitter] on their way to the decoder, and ends the stream when the chunk in
 * progress is [chunkCap] bytes long with more to come: [cut].
 */
private class IndexingStream(
    private val source: InputStream,
    private val splitter: ChunkSplitter,
    private val chunkCap: Long,
    private val ensureActive: () -> Unit,
) : InputStream() {

    var cut = false
        private set

    private val one = ByteArray(1)

    override fun read(): Int = if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        ensureActive()
        if (cut) return -1
        val room = splitter.roomIn(chunkCap)
        if (room <= 0) {
            // Only a source with more behind it is cut; a last chunk of exactly the cap is just the end.
            if (source.read() >= 0) cut = true
            return -1
        }
        val n = source.read(buffer, offset, minOf(length.toLong(), room).toInt())
        if (n > 0) splitter.feed(buffer, offset, n)
        return n
    }

    override fun close() = source.close()
}
