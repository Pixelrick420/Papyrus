package com.papyrus.app.viewer

import java.io.IOException
import java.io.InputStream
import java.nio.charset.Charset
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** A file's bytes that can be opened again at an offset, which is what lets a chunk be decoded on demand. */
fun interface ByteSource {
    /** A new stream [offset] bytes in; the caller closes it. Throws [IOException] when the provider cannot. */
    fun open(offset: Long): InputStream
}

/**
 * Opens a text file as [TextChunks] whatever its size, so a reader has one path. A small file, a charset
 * whose characters can hold a line terminator's bytes (UTF-16, Shift_JIS), or a provider that will not open
 * a file twice is decoded into memory, up to [IN_MEMORY_LIMIT_BYTES]. Anything else is read once to index
 * where its chunks start and kept as bytes: [TextChunks.chunks] decodes a chunk when it is asked for, and
 * only a few stay decoded.
 */
object ChunkProvider {

    /** The most that is held as text; past it the file is cut, as the plain-text reader always did. */
    const val IN_MEMORY_LIMIT_BYTES = 32 * 1024 * 1024

    /** A file of this size or less is not worth an index. */
    const val SMALL_FILE_BYTES = 2L * 1024 * 1024

    /**
     * Reads [source] on [dispatcher], and can be cancelled between reads. [sizeHint] is the file's size when
     * known, zero or less when not; [forced] skips charset detection.
     */
    suspend fun open(
        source: ByteSource,
        sizeHint: Long = 0,
        forced: Charset? = null,
        guesser: CharsetGuesser? = null,
        dispatcher: CoroutineDispatcher = Dispatchers.IO,
    ): TextChunks = withContext(dispatcher) {
        openChunks(source, sizeHint, forced, guesser, ChunkLayout()) { ensureActive() }
    }
}

internal fun openChunks(
    source: ByteSource,
    sizeHint: Long,
    forced: Charset?,
    guesser: CharsetGuesser?,
    layout: ChunkLayout,
    ensureActive: () -> Unit = {},
): TextChunks {
    val first = source.open(0)
    try {
        val opened = openText(first, ChunkProvider.IN_MEMORY_LIMIT_BYTES, forced, guesser)
        val worthIndexing = !opened.sampleCoversFile && sizeHint !in 1..ChunkProvider.SMALL_FILE_BYTES
        if (worthIndexing && byteSplittable(opened.encoding.charset)) {
            // A provider that refuses a second open is read into memory from the stream already open.
            val pass = reopen(source, opened.encoding.bomLength.toLong())
            if (pass != null) {
                first.close()
                return pass.use { indexChunks(source, it, opened.encoding, layout, ensureActive) }
            }
        }
        return assembleChunks(opened, layout.linesPerChunk)
    } finally {
        first.close()
    }
}

/** The text of the file [input] is, from the first byte after any BOM, indexed and served from [source]. */
internal fun indexChunks(
    source: ByteSource,
    input: InputStream,
    encoding: TextEncoding,
    layout: ChunkLayout,
    ensureActive: () -> Unit = {},
): TextChunks {
    val index = buildChunkIndex(input, encoding.charset, encoding.bomLength.toLong(), layout, ensureActive)
    val chunks = IndexedChunks(source, index, encoding.charset, layout)
    return TextChunks(chunks, index.lineCount, index.maxLineColumns, encoding, index.truncated)
}

private fun reopen(source: ByteSource, offset: Long): InputStream? = try {
    source.open(offset)
} catch (_: IOException) {
    null
}

/**
 * Whether a file in [charset] can be cut at its `\n` and `\r` bytes without cutting a character: UTF-8 and
 * the single-byte charsets that keep ASCII where it is.
 */
internal fun byteSplittable(charset: Charset): Boolean {
    val oneByte = charset == Charsets.UTF_8 || (charset.canEncode() && charset.newEncoder().maxBytesPerChar() == 1f)
    return oneByte && String(byteArrayOf(LINE_FEED, CARRIAGE_RETURN, LETTER_A), charset) == "\n\rA"
}

private const val LINE_FEED: Byte = 0x0A
private const val CARRIAGE_RETURN: Byte = 0x0D
private const val LETTER_A: Byte = 0x41

/**
 * The chunks of an indexed file as a list, decoded when read. Reaching a chunk decodes its whole span
 * (see [ChunkIndex]) and keeps what fits in the [layout]'s cache, so a scroll or a scan in order decodes
 * each byte once. Equal only to itself, because comparing two lists would decode both files. A chunk that
 * cannot be read, because the file went away, is empty rather than an exception in the middle of a draw.
 */
internal class IndexedChunks(
    private val source: ByteSource,
    private val index: ChunkIndex,
    private val charset: Charset,
    layout: ChunkLayout,
) : AbstractList<String>(), RandomAccess {

    private val lock = Any()
    private val cache = ChunkCache(layout.cacheChunks, layout.cacheChars)

    override val size: Int get() = index.chunkCount

    /** How many chunks are held decoded; what the cache bound is tested against. */
    val cachedCount: Int get() = synchronized(lock) { cache.size }

    override fun get(index: Int): String {
        if (index !in 0 until size) throw IndexOutOfBoundsException("chunk $index of $size")
        synchronized(lock) {
            cache[index]?.let { return it }
            val span = this.index.spanOf(index)
            val first = this.index.firstChunkOf(span)
            val decoded = try {
                decodeSpan(span)
            } catch (_: IOException) {
                return ""
            }
            decoded.forEachIndexed { i, text -> cache.put(first + i, text) }
            return decoded.getOrElse(index - first) { "" }
        }
    }

    private fun decodeSpan(span: Int): List<String> {
        val from = index.startOffsetOf(span)
        val bytes = source.open(from).use { it.readExactly(index.endOffsetOf(span) - from) }
        val last = index.chunkCount - 1
        val texts = ArrayList<String>(index.endChunkOf(span) - index.firstChunkOf(span))
        val splitter = ChunkSplitter(
            index.linesPerChunk,
            from,
            index.firstChunkOf(span),
            object : ChunkListener {
                override fun chunkStarted(index: Int, offset: Long) = Unit

                override fun chunkEnded(index: Int, start: Long, end: Long) {
                    val text = String(bytes, (start - from).toInt(), (end - start).toInt(), charset)
                    // A cut through a multi-byte character decodes to U+FFFD, the cut, not the content.
                    texts += (if (index == last && this@IndexedChunks.index.truncated) text.removeSuffix("\uFFFD") else text)
                        .withLineFeeds()
                }
            },
        )
        splitter.feed(bytes, 0, bytes.size)
        splitter.finish()
        return texts
    }

    override fun equals(other: Any?): Boolean = this === other

    override fun hashCode(): Int = System.identityHashCode(this)

    override fun toString(): String = "IndexedChunks($size chunks)"
}

/** `\r\n` and a lone `\r` end a line the way `\n` does, as in [LineAssembler]'s chunks. */
private fun String.withLineFeeds(): String = if (indexOf('\r') < 0) this else replace("\r\n", "\n").replace('\r', '\n')

/** Up to [length] bytes, fewer if the file is shorter than it was when indexed. */
private fun InputStream.readExactly(length: Long): ByteArray {
    val buffer = ByteArray(length.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
    var total = 0
    while (total < buffer.size) {
        val n = read(buffer, total, buffer.size - total)
        if (n < 0) break
        total += n
    }
    return if (total == buffer.size) buffer else buffer.copyOf(total)
}

/** Least recently used chunks, at most [maxChunks] and [maxChars] between them, though always the latest. */
private class ChunkCache(private val maxChunks: Int, private val maxChars: Int) {

    private val chunks = LinkedHashMap<Int, String>(16, 0.75f, true)
    private var chars = 0

    val size: Int get() = chunks.size

    operator fun get(index: Int): String? = chunks[index]

    fun put(index: Int, text: String) {
        chunks.put(index, text)?.let { chars -= it.length }
        chars += text.length
        val eldest = chunks.entries.iterator()
        while (chunks.size > 1 && (chunks.size > maxChunks || chars > maxChars)) {
            chars -= eldest.next().value.length
            eldest.remove()
        }
    }
}
