package io.github.pixelrick420.papyrus.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

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
    fun `a www link with no scheme is completed to https`() {
        assertEquals("https://www.example.com/a", resolvedLink("www.example.com/a"))
        assertEquals("https://www.example.com", resolvedLink(" www.example.com "))
        assertEquals("https://www.example.com/a", resolvedLink("www.example.com/a", Completion.HostLike))
    }

    @Test
    fun `text links do not complete bare hosts or file names`() {
        listOf(
            "notes.md", "CONTRIBUTING.md", "report.pdf", "install.sh", "a.b.c", "docs/readme.md",
            "example.com", "example.com/page",
        ).forEach { assertNull(resolvedLink(it)) }
    }

    @Test
    fun `pdf uri actions complete host-like links but not file names`() {
        assertEquals("https://example.com", resolvedLink("example.com", Completion.HostLike))
        assertEquals("https://example.com/page", resolvedLink(" example.com/page ", Completion.HostLike))
        assertNull(resolvedLink("notes.md", Completion.HostLike))
        assertNull(resolvedLink("install.sh", Completion.HostLike))
        assertNull(resolvedLink("a.b.c", Completion.HostLike))
        assertNull(resolvedLink("docs/readme.md", Completion.HostLike))
    }

    @Test
    fun `an anchor a relative path an empty string or nothing at all opens nothing`() {
        listOf("#bookmark", "/relative/path", "", "   ").forEach {
            assertNull(resolvedLink(it))
            assertNull(resolvedLink(it, Completion.HostLike))
        }
        assertNull(resolvedLink(null))
        assertNull(resolvedLink(null, Completion.HostLike))
    }

    @Test
    fun `host and port is mistaken for a scheme and refused`() {
        // The known limitation: only the working form opens, the other is reported as nothing to open.
        assertNull(resolvedLink("example.com:8080/x"))
        assertNull(resolvedLink("example.com:8080/x", Completion.HostLike))
        assertEquals("https://example.com:8080/x", resolvedLink("https://example.com:8080/x"))
    }
}
