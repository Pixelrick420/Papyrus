package com.papyrus.app.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The policy every surface shares for what a document-supplied link may become when opened.
 * Pure string handling, so a JVM test is the whole story — no Uri, no Context, no Android.
 */
class LinkTargetsTest {

    @Test
    fun `the four schemes that cannot run anything pass through trimmed`() {
        assertEquals("https://example.com/a", resolvedLink("https://example.com/a"))
        assertEquals("http://example.com/a", resolvedLink(" http://example.com/a "))
        assertEquals("HTTPS://EXAMPLE.COM/A", resolvedLink("HTTPS://EXAMPLE.COM/A"))
        assertEquals("mailto:a@example.com", resolvedLink("mailto:a@example.com"))
        assertEquals("tel:+911234", resolvedLink("tel:+911234"))
    }

    @Test
    fun `a scheme that can run something is refused rather than completed to https`() {
        assertNull(resolvedLink("javascript:alert(1)"))
        assertNull(resolvedLink("intent://scan/#Intent;end"))
        assertNull(resolvedLink("file:///etc/passwd"))
        assertNull(resolvedLink("content://x/y"))
    }

    @Test
    fun `a host-like link with no scheme is completed to https`() {
        assertEquals("https://www.example.com/a", resolvedLink("www.example.com/a"))
        assertEquals("https://example.com", resolvedLink("example.com"))
        assertEquals("https://example.com", resolvedLink(" example.com "))
    }

    @Test
    fun `an anchor a relative path an empty string or nothing at all opens nothing`() {
        assertNull(resolvedLink("#bookmark"))
        assertNull(resolvedLink("/relative/path"))
        assertNull(resolvedLink(""))
        assertNull(resolvedLink("   "))
        assertNull(resolvedLink(null))
    }

    @Test
    fun `host and port is mistaken for a scheme and refused`() {
        // The known limitation: only the working form opens, the other is reported as nothing to open.
        assertNull(resolvedLink("example.com:8080/x"))
        assertEquals("https://example.com:8080/x", resolvedLink("https://example.com:8080/x"))
    }
}
