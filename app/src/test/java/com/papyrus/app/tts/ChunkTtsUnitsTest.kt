package com.papyrus.app.tts

import com.papyrus.app.data.LocationKind
import com.papyrus.app.data.ReadingLocation
import java.util.Random
import java.util.concurrent.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ChunkTtsUnitsTest {

    private class WatchedChunks(private val inner: List<String>) : AbstractList<String>() {
        val read = ArrayList<Int>()
        override val size: Int get() = inner.size
        override fun get(index: Int): String = inner[index].also { read += index }
    }

    private val chunks = listOf(
        "First paragraph\nover two lines.\n\nSecond paragraph.",
        "",
        "   \n\n  ",
        "Third.\n\nFourth.\n\nFifth.",
        "Sixth, with a long\ncontinued line",
    )

    @Test
    fun `the units are the ones made up front, each anchored at its chunk`() {
        val lazy = TtsDocumentSource.unitsFromChunks(chunks)
        val eager = TtsDocumentSource.unitsFromPlainText(chunks)

        assertEquals(eager, lazy.toList())
        assertEquals(eager.size, lazy.size)
        assertEquals(listOf(0, 0, 3, 3, 3, 4), lazy.map { it.anchor })
        assertEquals("First paragraph over two lines.", lazy[0].text)
        assertEquals(chunks[3].indexOf("Fourth"), lazy[3].start)
    }

    @Test
    fun `chunks with nothing to say hold no units and are skipped over`() {
        val sparse = listOf("", "  ", "\n\n", "one", "", "two", "\n")

        val lazy = ChunkTtsUnits.of(sparse)

        assertEquals(listOf("one", "two"), lazy.map { it.text })
        assertEquals(listOf(3, 5), lazy.map { it.anchor })
    }

    @Test
    fun `no chunks and no words are no units`() {
        assertTrue(ChunkTtsUnits.of(emptyList()).isEmpty())
        assertTrue(ChunkTtsUnits.of(listOf("", " \n ")).isEmpty())
        assertEquals(0, ChunkTtsUnits.of(listOf("")).size)
    }

    @Test
    fun `random chunks give the same units made either way, read in any order`() {
        val words = listOf("a", "bb", " ", "\n", "\n\n", "\r\n", "\t", "é")
        val random = Random(11)
        repeat(300) { round ->
            val set = List(random.nextInt(9)) { buildString { repeat(random.nextInt(14)) { append(words[random.nextInt(words.size)]) } } }
            val eager = TtsDocumentSource.unitsFromPlainText(set)
            val lazy = ChunkTtsUnits.of(set)

            assertEquals("round $round", eager.size, lazy.size)
            for (i in eager.indices.shuffled(random)) assertEquals("round $round unit $i", eager[i], lazy[i])
        }
    }

    @Test
    fun `a unit out of range is out of range`() {
        val lazy = ChunkTtsUnits.of(chunks)

        for (bad in listOf(-1, lazy.size, Int.MAX_VALUE)) {
            try {
                lazy[bad]
                fail("unit $bad")
            } catch (expected: IndexOutOfBoundsException) {
                assertTrue(expected.message!!.contains("$bad"))
            }
        }
    }

    @Test
    fun `counting reads every chunk once, and a unit reads only its chunk`() {
        val watched = WatchedChunks(chunks)
        val lazy = ChunkTtsUnits.of(watched)
        assertEquals(listOf(0, 1, 2, 3, 4), watched.read)

        watched.read.clear()
        lazy[2]
        lazy[3]
        lazy[4]
        lazy[5]
        // Units 2, 3 and 4 are in chunk 3, which is read once and held; unit 5 is in chunk 4.
        assertEquals(listOf(3, 4), watched.read)
    }

    @Test
    fun `playback starts at the first unit at or after a saved chunk, without reading the chunks before it`() {
        val watched = WatchedChunks(chunks)
        val lazy = ChunkTtsUnits.of(watched)
        watched.read.clear()

        fun start(kind: LocationKind, unit: Int) = TtsDocumentSource.startIndexFor(lazy, ReadingLocation(kind, unit))

        assertEquals(0, start(LocationKind.CHUNK, 0))
        assertEquals(2, start(LocationKind.CHUNK, 1))
        assertEquals(2, start(LocationKind.CHUNK, 3))
        assertEquals(5, start(LocationKind.CHUNK, 4))
        // Past the last chunk with a unit: the last unit, as for a list made up front.
        assertEquals(5, start(LocationKind.CHUNK, 99))
        assertEquals(0, start(LocationKind.CHUNK, -4))
        assertTrue("read ${watched.read}", watched.read.isEmpty())
    }

    @Test
    fun `where playback starts is the same as for the list made up front`() {
        val random = Random(5)
        repeat(200) { round ->
            val set = List(1 + random.nextInt(8)) { if (random.nextInt(3) == 0) "" else "p${random.nextInt(9)}\n\nq${random.nextInt(9)}" }
            val eager = TtsDocumentSource.unitsFromPlainText(set)
            val lazy = ChunkTtsUnits.of(set)
            for (unit in -1..set.size + 1) {
                val at = ReadingLocation(LocationKind.CHUNK, unit)
                assertEquals("round $round unit $unit", TtsDocumentSource.startIndexFor(eager, at), TtsDocumentSource.startIndexFor(lazy, at))
            }
        }
    }

    @Test
    fun `a list is equal only to itself, and says nothing of its text`() {
        val watched = WatchedChunks(chunks)
        val one = ChunkTtsUnits.of(watched)
        val other = ChunkTtsUnits.of(watched)
        watched.read.clear()

        assertEquals(one, one)
        assertNotEquals(one, other)
        assertEquals("ChunkTtsUnits(6 units)", one.toString())
        assertTrue(watched.read.isEmpty())
    }

    @Test
    fun `counting stops when the work is cancelled`() {
        var checks = 0

        try {
            ChunkTtsUnits.of(chunks) { if (++checks == 3) throw CancellationException("stop") }
            fail("not cancelled")
        } catch (expected: CancellationException) {
            assertEquals("stop", expected.message)
        }
        assertEquals(3, checks)
    }
}
