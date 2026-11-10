package com.papyrus.app.viewer

import java.io.ByteArrayInputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.util.Random
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ChunkProviderTest {

    private val utf8 = TextEncoding(Charsets.UTF_8, 0, EncodingSource.UTF8)

    private fun source(bytes: ByteArray, opens: AtomicInteger = AtomicInteger()) = ByteSource { offset ->
        opens.incrementAndGet()
        ByteArrayInputStream(bytes, offset.toInt(), bytes.size - offset.toInt())
    }

    private class OneByteAtATime(input: InputStream) : FilterInputStream(input) {
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            if (length == 0) 0 else super.read(buffer, offset, 1)
    }

    private fun layout(lines: Int = 3, bytes: Int = 1, chunks: Int = 4) =
        ChunkLayout(linesPerChunk = lines, checkpointBytes = bytes, checkpointChunks = chunks)

    private fun indexed(bytes: ByteArray, layout: ChunkLayout, slow: Boolean = false, src: ByteSource = source(bytes)): TextChunks {
        val input = src.open(0).let { if (slow) OneByteAtATime(it) else it }
        return indexChunks(src, input, utf8, layout)
    }

    private fun oracle(bytes: ByteArray, lines: Int) =
        readTextChunks(ByteArrayInputStream(bytes), Int.MAX_VALUE, forced = Charsets.UTF_8, linesPerChunk = lines)

    private fun assertSameAs(expected: TextChunks, actual: TextChunks, label: String) {
        assertEquals("$label chunks", expected.chunks, actual.chunks.toList())
        assertEquals("$label lines", expected.lineCount, actual.lineCount)
        assertEquals("$label columns", expected.maxLineColumns, actual.maxLineColumns)
        assertEquals("$label truncated", expected.truncated, actual.truncated)
    }

    @Test
    fun `chunks come out exactly as the in-memory reader makes them`() {
        val texts = listOf(
            "", "a", "a\n", "a\nb", "\n", "\n\n\n", "\r\n", "\r", "\r\n\r\n", "a\nb\nc", "a\nb\nc\n", "a\nb\nc\nd",
            "a\nb\nc\r\nd", "a\nb\nc\rd", "a\nb\nc\r", "a\nb\nc\r\n", "a\r\nb\r\nc\r\nd", "a\rb\rc\rd\r",
            "tab\there\n日本語\n😀\n", "é\nê\në\nì\nî\nï", "a\n\n\nb\n\n\n",
        )
        for (lines in 1..4) for (text in texts) for (slow in listOf(false, true)) {
            val bytes = text.toByteArray()
            assertSameAs(oracle(bytes, lines), indexed(bytes, layout(lines), slow), "lines=$lines slow=$slow ${text.escaped()}")
        }
    }

    @Test
    fun `random text agrees with the in-memory reader at every layout`() {
        val pieces = listOf("a", "bc", "é", "日", "😀", " ", "\t", "\n", "\r\n", "\r", "\n\n")
        val random = Random(20260430)
        repeat(400) { round ->
            val text = buildString { repeat(random.nextInt(70)) { append(pieces[random.nextInt(pieces.size)]) } }
            val bytes = text.toByteArray()
            val lines = 1 + random.nextInt(5)
            val layout = layout(lines, bytes = 1 + random.nextInt(30), chunks = 1 + random.nextInt(6))
            assertSameAs(oracle(bytes, lines), indexed(bytes, layout, slow = round % 2 == 0), "round $round ${text.escaped()}")
        }
    }

    @Test
    fun `a chunk is the same read in any order as in sequence`() {
        val text = (1..200).joinToString("\r\n") { "line $it 日本語 😀" } + "\n"
        val bytes = text.toByteArray()
        val sequential = indexed(bytes, layout(lines = 3, bytes = 100, chunks = 7)).chunks.toList()
        val shuffled = indexed(bytes, layout(lines = 3, bytes = 100, chunks = 7), slow = true).chunks
        val order = sequential.indices.shuffled(Random(7))

        for (i in order) assertEquals("chunk $i", sequential[i], shuffled[i])
        assertEquals(oracle(bytes, 3).chunks, sequential)
    }

    @Test
    fun `a multi-byte character beside a chunk edge is never split`() {
        // Every chunk is its own span, and each line ends in a four-byte character beside the terminator.
        val text = (1..12).joinToString("\n") { "x😀" } + "\n😀"
        val bytes = text.toByteArray()

        val chunks = indexed(bytes, layout(lines = 2, bytes = 1, chunks = 1)).chunks

        assertEquals(oracle(bytes, 2).chunks, chunks.toList())
        assertFalse(chunks.any { '\uFFFD' in it })
    }

    @Test
    fun `a CRLF straddling a chunk edge is one terminator and ends in neither chunk`() {
        // The third terminator closes the chunk and is a CR whose LF comes in the next read.
        val bytes = "a\nb\nc\r\nd\ne\nf\r\ng".toByteArray()

        val read = indexed(bytes, layout(lines = 3, bytes = 1), slow = true)

        assertEquals(listOf("a\nb\nc", "d\ne\nf", "g"), read.chunks.toList())
        assertEquals(7, read.lineCount)
    }

    @Test
    fun `a file ending on the terminator that fills a chunk gets no empty chunk after it`() {
        val full = indexed("a\nb\nc\n".toByteArray(), layout(lines = 3))
        val over = indexed("a\nb\nc\nd\n".toByteArray(), layout(lines = 3))

        assertEquals(listOf("a\nb\nc"), full.chunks.toList())
        assertEquals(4, full.lineCount)
        assertEquals(listOf("a\nb\nc", "d\n"), over.chunks.toList())
    }

    @Test
    fun `line numbers follow from the chunk and the count includes a trailing empty line`() {
        val bytes = (1..100).joinToString("") { "line$it\n" }.toByteArray()

        val read = indexed(bytes, ChunkLayout(checkpointBytes = 64, checkpointChunks = 2))

        assertEquals(3, read.chunks.size)
        assertEquals(101, read.lineCount)
        for (chunk in read.chunks.indices) {
            val firstLine = chunk * TEXT_LINES_PER_CHUNK + 1
            assertEquals("line$firstLine", read.chunks[chunk].substringBefore('\n'))
        }
        assertTrue(read.chunks.last().endsWith("line100\n"))
    }

    @Test
    fun `an empty file is one empty chunk and a file with no trailing newline keeps its last line`() {
        val empty = indexed(ByteArray(0), layout())
        val bare = indexed("one\ntwo".toByteArray(), layout(lines = 40))

        assertEquals(listOf(""), empty.chunks.toList())
        assertEquals(1, empty.lineCount)
        assertEquals(listOf("one\ntwo"), bare.chunks.toList())
        assertEquals(2, bare.lineCount)
    }

    @Test
    fun `the widest line is measured the way the in-memory reader does`() {
        val bytes = "ab\n\tx\n日本語日本語\nshort".toByteArray()

        assertEquals(oracle(bytes, 40).maxLineColumns, indexed(bytes, layout(lines = 40)).maxLineColumns)
        assertEquals(12, indexed(bytes, layout(lines = 40)).maxLineColumns)
    }

    @Test
    fun `checkpoints come every few chunks and every span ends where the next begins`() {
        val bytes = (1..60).joinToString("") { "l$it\n" }.toByteArray()

        val index = buildChunkIndex(ByteArrayInputStream(bytes), Charsets.UTF_8, 0, layout(lines = 2, bytes = 1_000_000, chunks = 5))

        assertEquals(30, index.chunkCount)
        assertEquals(6, index.spanCount)
        for (span in 0 until index.spanCount) {
            assertEquals(span * 5, index.firstChunkOf(span))
            assertEquals(span, index.spanOf(span * 5))
            assertEquals(span, index.spanOf(span * 5 + 4))
        }
        assertEquals(0L, index.startOffsetOf(0))
        assertEquals(bytes.size.toLong(), index.endOffsetOf(index.spanCount - 1))
        assertEquals(index.startOffsetOf(3), index.endOffsetOf(2))
    }

    @Test
    fun `a long chunk gets a checkpoint of its own once it passes the byte spacing`() {
        val bytes = (listOf("x".repeat(500)) + (1..6).map { "s$it" }).joinToString("\n").toByteArray()

        val index = buildChunkIndex(ByteArrayInputStream(bytes), Charsets.UTF_8, 0, layout(lines = 1, bytes = 100, chunks = 1000))

        // The 500-byte first chunk is far past the spacing, so the second chunk starts a span; the short ones after it do not.
        assertEquals(7, index.chunkCount)
        assertEquals(2, index.spanCount)
        assertEquals(1, index.firstChunkOf(1))
        assertEquals(0, index.spanOf(0))
        assertEquals(1, index.spanOf(6))
    }

    @Test
    fun `only a few chunks stay decoded however much is read`() {
        val bytes = (1..400).joinToString("\n") { "line $it" }.toByteArray()
        val chunks = indexed(bytes, ChunkLayout(linesPerChunk = 2, checkpointBytes = 1, checkpointChunks = 3, cacheChunks = 5)).chunks
            as IndexedChunks

        for (i in chunks.indices) {
            chunks[i]
            assertTrue("held ${chunks.cachedCount}", chunks.cachedCount in 1..5)
        }
        assertEquals("line 399\nline 400", chunks.last())
    }

    @Test
    fun `the character budget bounds the cache too, but the latest chunk always stays`() {
        val bytes = (1..30).joinToString("\n") { "x".repeat(40) }.toByteArray()
        val chunks = indexed(bytes, ChunkLayout(linesPerChunk = 1, checkpointBytes = 1, cacheChunks = 100, cacheChars = 100)).chunks
            as IndexedChunks

        for (i in chunks.indices) chunks[i]

        assertTrue("held ${chunks.cachedCount}", chunks.cachedCount in 1..2)
    }

    @Test
    fun `scanning in order decodes each span once`() {
        val bytes = (1..300).joinToString("\n") { "line $it" }.toByteArray()
        val opens = AtomicInteger()
        val src = source(bytes, opens)
        val chunks = indexed(bytes, ChunkLayout(linesPerChunk = 2, checkpointBytes = 1_000_000, checkpointChunks = 10, cacheChunks = 64), src = src).chunks
        val afterIndexing = opens.get()

        for (i in chunks.indices) chunks[i]

        // 150 chunks in spans of 10, all fitting the cache: one open per span.
        assertEquals(15, opens.get() - afterIndexing)
    }

    @Test
    fun `lists of chunks are equal only to themselves, and say nothing of their content`() {
        val bytes = "a\nb\nc\nd".toByteArray()
        val opens = AtomicInteger()
        val src = source(bytes, opens)
        val one = indexed(bytes, layout(lines = 1), src = src).chunks
        val other = indexed(bytes, layout(lines = 1), src = src).chunks
        val opened = opens.get()

        assertEquals(one, one)
        assertNotEquals(one, other)
        assertNotEquals(one.hashCode(), other.hashCode())
        assertEquals("IndexedChunks(4 chunks)", one.toString())
        assertEquals(opened, opens.get())
    }

    @Test
    fun `a chunk is out of range outside the list`() {
        val chunks = indexed("a\nb".toByteArray(), layout(lines = 1)).chunks

        for (bad in listOf(-1, 2, Int.MAX_VALUE)) {
            try {
                chunks[bad]
                fail("chunk $bad")
            } catch (expected: IndexOutOfBoundsException) {
                assertTrue(expected.message!!.contains("$bad"))
            }
        }
    }

    @Test
    fun `a chunk that cannot be read is empty, not an exception, and is read again later`() {
        val bytes = (1..20).joinToString("\n") { "line $it" }.toByteArray()
        var failing = false
        val src = ByteSource { offset ->
            if (failing) throw IOException("gone")
            ByteArrayInputStream(bytes, offset.toInt(), bytes.size - offset.toInt())
        }
        val chunks = indexed(bytes, layout(lines = 2, bytes = 1, chunks = 1), src = src).chunks

        failing = true
        assertEquals("", chunks[3])
        failing = false
        assertEquals("line 7\nline 8", chunks[3])
    }

    @Test
    fun `a chunk over the limit cuts the read there, as the in-memory limit cut the file`() {
        // 24 two-byte letters fit; the limit of 52 splits a 25th, whose half decodes to the cut mark.
        val bytes = ("ab\n" + "é".repeat(100)).toByteArray()
        val cut = ChunkLayout(linesPerChunk = 40, maxChunkBytes = 52)

        val read = indexed(bytes, cut)
        val expected = readTextChunks(ByteArrayInputStream(bytes), 52, forced = Charsets.UTF_8)

        assertTrue(read.truncated)
        assertEquals(listOf("ab\n" + "é".repeat(24)), read.chunks.toList())
        assertEquals(expected.chunks, read.chunks.toList())
        assertEquals(expected.lineCount, read.lineCount)
    }

    @Test
    fun `earlier chunks survive a later chunk being cut`() {
        val bytes = ("one\ntwo\n" + "x".repeat(500)).toByteArray()

        val read = indexed(bytes, ChunkLayout(linesPerChunk = 1, checkpointBytes = 1, maxChunkBytes = 100))

        assertTrue(read.truncated)
        assertEquals(listOf("one", "two", "x".repeat(100)), read.chunks.toList())
    }

    @Test
    fun `a last chunk exactly at the limit is the end of the file, not a cut`() {
        val bytes = ("a\n" + "x".repeat(10)).toByteArray()

        val read = indexed(bytes, ChunkLayout(linesPerChunk = 1, checkpointBytes = 1, maxChunkBytes = 10))

        assertFalse(read.truncated)
        assertEquals(listOf("a", "x".repeat(10)), read.chunks.toList())
    }

    @Test
    fun `indexing stops when the work is cancelled`() {
        val bytes = ByteArray(100_000) { 'a'.code.toByte() }
        var checks = 0

        try {
            buildChunkIndex(ByteArrayInputStream(bytes), Charsets.UTF_8, 0, ChunkLayout()) {
                if (++checks == 3) throw CancellationException("stop")
            }
            fail("not cancelled")
        } catch (expected: CancellationException) {
            assertEquals("stop", expected.message)
        }
        assertEquals(3, checks)
    }

    @Test
    fun `a byte-order mark is skipped, and offsets are from the start of the file`() {
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "a\nb\nc\nd".toByteArray()
        val bom = TextEncoding(Charsets.UTF_8, 3, EncodingSource.BOM)
        val src = source(bytes)

        val read = indexChunks(src, src.open(3), bom, layout(lines = 2, bytes = 1))

        assertEquals(listOf("a\nb", "c\nd"), read.chunks.toList())
    }

    @Test
    fun `only utf-8 and single-byte charsets with ascii in place are split on bytes`() {
        for (name in listOf("UTF-8", "windows-1252", "ISO-8859-1", "ISO-8859-2", "KOI8-R", "US-ASCII")) {
            assertTrue(name, byteSplittable(charset(name)))
        }
        for (name in listOf("UTF-16LE", "UTF-16BE", "UTF-32LE", "Shift_JIS", "GB18030", "Big5", "EUC-KR", "IBM037")) {
            assertFalse(name, byteSplittable(charset(name)))
        }
    }

    // The sample a file is detected from is 64 KB, so these need files past it to take the indexed path.
    private fun bigText(lines: Int = 6_000, ending: String = "\n") =
        (1..lines).joinToString(ending) { "line $it of the big file é 日本語" } + ending

    private fun open(bytes: ByteArray, src: ByteSource = source(bytes), size: Long = 0, forced: java.nio.charset.Charset? = null) =
        runBlocking { ChunkProvider.open(src, sizeHint = size, forced = forced) }

    @Test
    fun `a big file is indexed and reads like the in-memory reader`() {
        val bytes = bigText().toByteArray()
        assertTrue(bytes.size > TextEncodings.SAMPLE_BYTES * 3)

        val read = open(bytes)

        assertTrue(read.chunks is IndexedChunks)
        assertSameAs(readTextChunks(ByteArrayInputStream(bytes), Int.MAX_VALUE, guesser = null), read, "big")
        assertEquals(EncodingSource.UTF8, read.encoding.source)
    }

    @Test
    fun `a big file with windows line ends and a byte-order mark reads the same`() {
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + bigText(ending = "\r\n").toByteArray()

        val read = open(bytes)

        assertTrue(read.chunks is IndexedChunks)
        assertEquals(3, read.encoding.bomLength)
        assertSameAs(readTextChunks(ByteArrayInputStream(bytes), Int.MAX_VALUE, guesser = null), read, "bom")
    }

    @Test
    fun `a big file in a single-byte charset is indexed too`() {
        val bytes = bigText().toByteArray(charset("windows-1252"))

        val read = open(bytes, forced = charset("windows-1252"))

        assertTrue(read.chunks is IndexedChunks)
        assertSameAs(readTextChunks(ByteArrayInputStream(bytes), Int.MAX_VALUE, forced = charset("windows-1252")), read, "1252")
    }

    @Test
    fun `a small file, or one of known small size, is read into memory`() {
        val small = "a\nb\n".toByteArray()
        val big = bigText().toByteArray()

        assertFalse(open(small).chunks is IndexedChunks)
        assertFalse(open(big, size = big.size.toLong()).chunks is IndexedChunks)
        assertTrue(open(big, size = ChunkProvider.SMALL_FILE_BYTES + 1).chunks is IndexedChunks)
    }

    @Test
    fun `a charset that can hide a terminator in a character is read into memory`() {
        val utf16 = bigText().toByteArray(Charsets.UTF_16LE)
        val shiftJis = bigText().toByteArray(charset("Shift_JIS"))

        val a = open(utf16, forced = Charsets.UTF_16LE)
        val b = open(shiftJis, forced = charset("Shift_JIS"))

        assertFalse(a.chunks is IndexedChunks)
        assertFalse(b.chunks is IndexedChunks)
        assertSameAs(readTextChunks(ByteArrayInputStream(utf16), Int.MAX_VALUE, forced = Charsets.UTF_16LE), a, "utf16")
        assertSameAs(readTextChunks(ByteArrayInputStream(shiftJis), Int.MAX_VALUE, forced = charset("Shift_JIS")), b, "sjis")
    }

    @Test
    fun `a provider that will not open the file twice is read into memory from the first stream`() {
        val bytes = bigText().toByteArray()
        val opens = AtomicInteger()
        val once = ByteSource { offset ->
            if (opens.incrementAndGet() > 1) throw IOException("one open only")
            ByteArrayInputStream(bytes, offset.toInt(), bytes.size - offset.toInt())
        }

        val read = open(bytes, src = once)

        assertFalse(read.chunks is IndexedChunks)
        assertSameAs(readTextChunks(ByteArrayInputStream(bytes), Int.MAX_VALUE, guesser = null), read, "once")
        assertEquals(2, opens.get())
    }

    @Test
    fun `a source that cannot be opened at all throws to the caller`() {
        val none = ByteSource { throw IOException("no such file") }

        try {
            open(ByteArray(0), src = none)
            fail("opened")
        } catch (expected: IOException) {
            assertEquals("no such file", expected.message)
        }
    }

    private class NoSkip(input: InputStream) : FilterInputStream(input) {
        override fun skip(n: Long): Long = 0
    }

    @Test
    fun `a stream is moved to an offset by a seek on a file and by reading on anything else`() {
        val bytes = ByteArray(1000) { (it % 251).toByte() }
        val file = File.createTempFile("skip", ".bin").apply { writeBytes(bytes); deleteOnExit() }

        for (offset in listOf(0L, 1L, 499L, 999L, 1000L)) {
            val streams = listOf(
                FileInputStream(file),
                ByteArrayInputStream(bytes),
                NoSkip(ByteArrayInputStream(bytes)),
                OneByteAtATime(ByteArrayInputStream(bytes)),
            )
            for (stream in streams) {
                stream.use {
                    it.skipTo(offset)
                    val rest = it.readBytes()
                    assertEquals("${it.javaClass.simpleName} at $offset", bytes.size - offset.toInt(), rest.size)
                    if (rest.isNotEmpty()) assertEquals(bytes[offset.toInt()], rest[0])
                }
            }
        }
    }

    @Test
    fun `a stream shorter than the offset says so`() {
        for (stream in listOf(ByteArrayInputStream(ByteArray(10)), NoSkip(ByteArrayInputStream(ByteArray(10))))) {
            try {
                stream.skipTo(11)
                fail("skipped past the end")
            } catch (expected: EOFException) {
                assertTrue(expected.message!!.contains("11"))
            }
        }
    }

    private fun String.escaped() = replace("\r", "\\r").replace("\n", "\\n").replace("\t", "\\t")
}
