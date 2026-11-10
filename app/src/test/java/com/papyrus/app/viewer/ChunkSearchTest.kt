package com.papyrus.app.viewer

import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChunkSearchTest {

    private fun List<ChunkMatch>.chunks() = map { it.chunk }

    private fun List<ChunkMatch>.globals() = map { it.globalOffset }

    @Test
    fun `a query inside one chunk is found there, with its place in the chunk and the document`() {
        val chunks = listOf("alpha beta", "gamma beta delta", "beta")

        val found = searchChunks(chunks, "beta")

        assertEquals(listOf(0, 1, 2), found.chunks())
        assertEquals(listOf(6, 6, 0), found.map { it.offset })
        assertEquals(listOf(6L, 17L, 28L), found.globals())
    }

    @Test
    fun `every match in a chunk is its own, and a match is never counted twice over itself`() {
        assertEquals(listOf(0, 0), searchChunks(listOf("aaaa"), "aa").chunks())
        assertEquals(listOf(0, 0, 0, 1), searchChunks(listOf("xx x", "x"), "x").chunks())
        assertEquals(listOf(0, 0, 1), searchChunks(listOf("Foo foo", "FOO"), "foo").chunks())
    }

    @Test
    fun `case is ignored the way the highlight ignores it`() {
        assertEquals(listOf(0, 1), searchChunks(listOf("Straße STRASSE", "STRAßE"), "straße").chunks())
        assertEquals(2, searchChunks(listOf("ÉCOLE", "école"), "École").size)
    }

    @Test
    fun `a blank or empty query, or no room, finds nothing`() {
        assertTrue(searchChunks(listOf("a b", "c"), "").isEmpty())
        assertTrue(searchChunks(listOf("a b", "c"), "   ").isEmpty())
        assertTrue(searchChunks(listOf("a b", "c"), " ", limit = 5).isEmpty())
        assertTrue(searchChunks(listOf("a"), "a", limit = 0).isEmpty())
        assertTrue(searchChunks(emptyList(), "a").isEmpty())
    }

    @Test
    fun `a query with a line break is found across the end of a chunk`() {
        val chunks = listOf("first line", "second line", "third line")

        val found = searchChunks(chunks, "line\nsecond")

        assertEquals(listOf(0), found.chunks())
        assertEquals(listOf(6), found.map { it.offset })
        assertEquals(6L, found.single().globalOffset)
    }

    @Test
    fun `a match can run over more than one boundary, through chunks shorter than the query`() {
        val chunks = listOf("ab", "c", "d", "ef")

        val found = searchChunks(chunks, "b\nc\nd\ne")

        assertEquals(1, found.size)
        assertEquals(0, found.single().chunk)
        assertEquals(1, found.single().offset)
        assertEquals(1L, found.single().globalOffset)
    }

    @Test
    fun `a match that begins at the line break after a chunk belongs to that chunk`() {
        val found = searchChunks(listOf("end", "start"), "\nstart")

        assertEquals(listOf(0), found.chunks())
        assertEquals(listOf(3), found.map { it.offset })
        assertEquals(3L, found.single().globalOffset)
    }

    @Test
    fun `a match over a boundary is not found twice, nor are matches in the carried text`() {
        val chunks = listOf("xx\nxx", "xx\nxx", "yy")

        val found = searchChunks(chunks, "xx\nxx")

        // Within chunk 0, over the 0/1 boundary there is no "xx\nxx" (that is "xx\nxx\nxx"), within chunk 1.
        assertEquals(listOf(0, 1), found.chunks())
        assertEquals(listOf(0L, 6L), found.globals())
    }

    @Test
    fun `a query without a line break is not carried over a boundary`() {
        // "b c" is split by the boundary and chunks end at a line break, so it is not one line of text.
        assertTrue(searchChunks(listOf("a b", "c d"), "b c").isEmpty())
        assertEquals(1, searchChunks(listOf("a b", "c d"), "b\nc").size)
    }

    @Test
    fun `the limit caps the list, and a stop request ends the search between chunks`() {
        val chunks = List(10) { "hit hit" }

        assertEquals(5, searchChunks(chunks, "hit", limit = 5).size)
        assertEquals(listOf(0, 0, 1, 1, 2), searchChunks(chunks, "hit", limit = 5).chunks())
        var polled = 0
        val found = searchChunks(chunks, "hit") { ++polled > 3 }
        assertEquals(6, found.size)
        assertEquals(4, polled)
    }

    @Test
    fun `the limit holds across a carried match too`() {
        val chunks = List(6) { "a\nb" }

        assertEquals(2, searchChunks(chunks, "a\nb", limit = 2).size)
    }

    @Test
    fun `searching the chunks finds what searching the whole document finds`() {
        val pieces = listOf("a", "b", "ab", "A", "\n", " ", "x", "é")
        val queries = listOf("a", "ab", "b\na", "a\n", "\nb", "ab\nab", "A\nA\nA", "é\né", "x \nx", "\n\n")
        val random = Random(7)
        repeat(500) { round ->
            val chunks = List(1 + random.nextInt(6)) { buildString { repeat(random.nextInt(8)) { append(pieces[random.nextInt(pieces.size)]) } } }
            val document = chunks.joinToString("\n")
            for (query in queries) {
                if (query.isBlank()) continue
                val expected = ArrayList<Long>()
                var from = 0
                while (true) {
                    val at = document.indexOf(query, from, ignoreCase = true)
                    if (at < 0) break
                    expected += at.toLong()
                    from = at + query.length
                }
                val found = searchChunks(chunks, query)

                assertEquals("round $round query ${query.replace("\n", "\\n")} in ${chunks}", expected, found.globals())
                for (match in found) {
                    val chunkStart = chunks.take(match.chunk).sumOf { it.length + 1L }
                    assertEquals(chunkStart + match.offset, match.globalOffset)
                    assertTrue(match.offset <= chunks[match.chunk].length)
                }
            }
        }
    }
}
