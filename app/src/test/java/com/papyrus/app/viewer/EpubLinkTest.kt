package com.papyrus.app.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EpubLinkTest {

    private fun link(href: String?, base: String = "OEBPS/text/", chapter: String = "OEBPS/text/one.xhtml") =
        bookLinkOf(base, href, chapter)

    @Test
    fun `a fragment alone names a place in the current chapter`() {
        assertEquals(BookLink("OEBPS/text/one.xhtml", "footnote-4"), link("#footnote-4"))
    }

    @Test
    fun `a path and a fragment are resolved against the chapter's directory`() {
        assertEquals(BookLink("OEBPS/text/chapter2.xhtml", "top"), link("chapter2.xhtml#top"))
        assertEquals(BookLink("OEBPS/other.xhtml", "x"), link("../other.xhtml#x"))
    }

    @Test
    fun `a path alone names the chapter`() {
        assertEquals(BookLink("OEBPS/text/chapter2.xhtml", null), link("chapter2.xhtml"))
    }

    @Test
    fun `a fragment is percent decoded and a query is dropped from the path`() {
        assertEquals(BookLink("OEBPS/text/one.xhtml", "/slash"), link("#%2Fslash"))
        assertEquals(BookLink("OEBPS/text/c.xhtml", "frag"), link("c.xhtml?x=1#frag"))
        assertEquals(BookLink("OEBPS/text/my file.xhtml", "caf\u00E9"), link("my%20file.xhtml#caf%C3%A9"))
    }

    @Test
    fun `surrounding whitespace is ignored`() {
        assertEquals(BookLink("OEBPS/text/c.xhtml", "a"), link("  c.xhtml#a \n"))
    }

    @Test
    fun `a link that climbs out of the book or names a scheme goes nowhere`() {
        // Two levels up from OEBPS/text/ is the archive root, which is inside the book; three is not.
        assertEquals(BookLink("escape.xhtml", null), link("../../escape.xhtml"))
        assertNull(link("../../../escape.xhtml"))
        assertNull(link("../../../escape.xhtml#x"))
        assertNull(link("javascript:alert(1)"))
        assertNull(link("data:text/html,x"))
        assertNull(link("file:///etc/passwd"))
        // A web address is safeLink's to accept, never this function's.
        assertNull(link("http://example.org"))
        assertNull(link("https://example.org/a.xhtml#x"))
        assertNull(link("mailto:a@b.c"))
    }

    @Test
    fun `an empty href or an empty fragment goes nowhere`() {
        assertNull(link(""))
        assertNull(link("   "))
        assertNull(link("#"))
        assertNull(link("?only=query"))
        assertNull(link(null))
    }

    @Test
    fun `splitting keeps the halves apart`() {
        val parts = splitHref("a/b.xhtml?q#frag%20x")
        assertEquals("a/b.xhtml", parts.path)
        assertEquals("frag x", parts.fragment)
        assertNull(splitHref("a.xhtml#").fragment)
        assertNull(splitHref("a.xhtml").fragment)
        assertEquals("", splitHref("#x").path)
        // Only the first # splits: the rest belongs to the fragment.
        assertEquals("a#b", splitHref("p.xhtml#a#b").fragment)
    }
}
