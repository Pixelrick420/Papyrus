package io.github.pixelrick420.papyrus.tts

import io.github.pixelrick420.papyrus.data.LocationKind
import io.github.pixelrick420.papyrus.data.ReadingLocation
import java.util.concurrent.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PdfTtsUnitsTest {

    private val pages = listOf("First page.\nTwo lines.", "", "Third page.\n\nAnother paragraph.")

    private fun units(read: MutableList<Int> = ArrayList()): PdfTtsUnits =
        PdfTtsUnits(pages.size) { index -> read += index; pages[index] }

    @Test
    fun `no page is read until the producer publishes one`() = runBlocking {
        val read = ArrayList<Int>()
        val lazy = units(read)

        assertTrue(lazy.isEmpty())
        assertTrue(read.isEmpty())

        assertTrue(lazy.publishNext())
        assertEquals(listOf(0), read)
        assertEquals(TtsDocumentSource.unitsFromPdfPages(listOf(pages[0])), lazy.toList())
    }

    @Test
    fun `publishing through a page keeps its own units available for the start index`() = runBlocking {
        val read = ArrayList<Int>()
        val lazy = units(read)

        lazy.publishThrough(2)

        assertEquals(listOf(0, 1, 2), read)
        assertEquals(2, lazy.publishedPages())
        assertEquals(TtsDocumentSource.unitsFromPdfPages(pages), lazy.toList())
        assertEquals(0, TtsDocumentSource.startIndexFor(lazy, ReadingLocation(LocationKind.PAGE, 0)))
        assertEquals(2, TtsDocumentSource.startIndexFor(lazy, ReadingLocation(LocationKind.PAGE, 2)))
        assertFalse(lazy.publishNext())
        assertEquals(listOf(0, 1, 2), read)
    }

    @Test
    fun `the whole list equals the eager one and needs one read per page`() = runBlocking {
        val read = ArrayList<Int>()
        val lazy = units(read)

        while (lazy.publishNext()) Unit

        assertEquals(listOf(0, 1, 2), read)
        assertEquals(TtsDocumentSource.unitsFromPdfPages(pages), lazy.toList())
    }

    @Test
    fun `a cancelled extraction is not mistaken for a finished page`() = runBlocking {
        val lazy = PdfTtsUnits(2) { if (it == 1) throw CancellationException("stop") else pages[it] }
        assertTrue(lazy.publishNext())

        try {
            lazy.publishNext()
            fail("cancellation should reach the caller")
        } catch (_: CancellationException) {
            assertEquals(1, lazy.publishedPages())
        }
    }
}
