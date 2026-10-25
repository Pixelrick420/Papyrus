package com.papyrus.app.data

import com.papyrus.app.viewer.NO_OUTLINE_TARGET
import com.papyrus.app.viewer.OutlineEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfOutlineCacheTest {

    private val entries = listOf(
        OutlineEntry(0, 0, "Chapter one"),
        OutlineEntry(NO_OUTLINE_TARGET, 1, "Part: an aside, with 1,234 words"),
        OutlineEntry(12, 1, "spaced title"),
        OutlineEntry(40, 2, ""),
    )

    @Test
    fun `entries survive being stored and read back`() {
        val decoded = decodeOutline(encodeOutline(entries))

        // The empty title is dropped by the decoder, so it never becomes a blank row.
        assertEquals(entries.filter { it.title.isNotBlank() }, decoded)
    }

    @Test
    fun `a title holding delimiters is read whole`() {
        val tricky = listOf(OutlineEntry(3, 0, "a:b 12c d:4;5"))

        assertEquals(tricky, decodeOutline(encodeOutline(tricky)))
    }

    @Test
    fun `an entry with no destination survives`() {
        val decoded = decodeOutline(encodeOutline(listOf(OutlineEntry(NO_OUTLINE_TARGET, 0, "Part"))))

        assertEquals(NO_OUTLINE_TARGET, decoded.single().index)
    }

    @Test
    fun `no entries is the empty string and back`() {
        assertEquals("", encodeOutline(emptyList()))
        assertEquals(emptyList<OutlineEntry>(), decodeOutline(""))
    }

    @Test
    fun `a damaged entry ends the read and keeps the ones before it`() {
        val packed = encodeOutline(entries.take(2))
        // A cut-off length: the decoder must not run past the text it has.
        val damaged = packed + "9 0 99:half"

        assertEquals(entries.take(2), decodeOutline(damaged))
    }

    @Test
    fun `garbage is not decoded`() {
        assertTrue(decodeOutline("nonsense").isEmpty())
    }

    @Test
    fun `a damaged depth is clamped instead of indenting off the sheet`() {
        val packed = encodeOutline(listOf(OutlineEntry(1, 0, "deep"))).replace("1 0 ", "1 900 ")

        assertEquals(32, decodeOutline(packed).single().depth)
    }

    @Test
    fun `a title longer than a heading is cut to the heading limit`() {
        val long = OutlineEntry(1, 0, "x".repeat(500))

        assertEquals(200, decodeOutline(encodeOutline(listOf(long))).single().title.length)
    }
}
