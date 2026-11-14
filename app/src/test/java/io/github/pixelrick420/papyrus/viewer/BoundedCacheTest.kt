package io.github.pixelrick420.papyrus.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class BoundedCacheTest {

    private fun cache(maxBytes: Long = 30L) = BoundedCache<String, String>(maxBytes) { 10L }

    @Test
    fun `a value is read back as it was stored`() {
        val cache = cache()
        val stored = "page text"

        cache.put("a", stored)

        assertSame(stored, cache.get("a"))
        assertNull(cache.get("missing"))
    }

    @Test
    fun `the least recently used value goes first when the budget is passed`() {
        val cache = cache()
        cache.put("a", "1")
        cache.put("b", "2")
        cache.put("c", "3")
        // Read, so b is now the oldest untouched.
        cache.get("a")

        cache.put("d", "4")

        assertNull(cache.get("b"))
        assertEquals("1", cache.get("a"))
        assertEquals("3", cache.get("c"))
        assertEquals("4", cache.get("d"))
    }

    @Test
    fun `a value larger than the whole budget is not kept and pushes nothing out`() {
        val cache = BoundedCache<String, String>(30L) { it.length.toLong() }
        cache.put("small", "x".repeat(10))

        cache.put("huge", "y".repeat(31))

        assertNull(cache.get("huge"))
        assertEquals("x".repeat(10), cache.get("small"))
    }

    @Test
    fun `storing a key again replaces it without counting it twice`() {
        val cache = cache(20L)
        cache.put("a", "first")
        cache.put("a", "second")

        cache.put("b", "other")

        assertEquals("second", cache.get("a"))
        assertEquals("other", cache.get("b"))
    }

    @Test
    fun `a replacement that no longer fits drops the old value too`() {
        val cache = BoundedCache<String, String>(30L) { it.length.toLong() }
        cache.put("a", "x".repeat(10))

        cache.put("a", "y".repeat(40))

        assertNull(cache.get("a"))
    }

    @Test
    fun `clear empties it and frees the budget`() {
        val cache = cache()
        cache.put("a", "1")
        cache.put("b", "2")
        cache.put("c", "3")

        cache.clear()
        cache.put("d", "4")
        cache.put("e", "5")
        cache.put("f", "6")

        assertNull(cache.get("a"))
        assertEquals("4", cache.get("d"))
        assertEquals("5", cache.get("e"))
        assertEquals("6", cache.get("f"))
    }

    @Test
    fun `a page costs more with its glyph boxes than without`() {
        val text = "some words on a page"
        val plain = PdfPageText(text, FloatArray(0))
        val boxed = PdfPageText(text, FloatArray(text.length * 4))

        assertTrue(plain.approxBytes >= text.length * 2L)
        assertEquals(text.length * 16L, boxed.approxBytes - plain.approxBytes)
    }
}
