package com.papyrus.app.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutolinksTest {

    /** `text -> destination` per link, so an expectation reads like the sentence it describes. */
    private fun found(text: String): List<String> =
        Autolinks.find(text).map { "${text.substring(it.start, it.end)} -> ${it.destination}" }

    @Test
    fun `a URL stops before the punctuation that ends the sentence`() {
        assertEquals(listOf("https://example.com/a -> https://example.com/a"), found("See https://example.com/a."))
        assertEquals(listOf("http://example.com -> http://example.com"), found("Is it http://example.com?"))
        assertEquals(listOf("https://example.com -> https://example.com"), found("(https://example.com)"))
        assertEquals(listOf("https://example.com -> https://example.com"), found("\"https://example.com\","))
    }

    @Test
    fun `a bracket the address opened stays and one it did not is dropped`() {
        val wiki = "https://en.wikipedia.org/wiki/Kotlin_(language)"
        assertEquals(listOf("$wiki -> $wiki"), found(wiki))
        assertEquals(listOf("$wiki -> $wiki"), found("(see $wiki)"))
    }

    @Test
    fun `a www host is completed to https and needs a dot after it`() {
        assertEquals(listOf("www.example.com/path?x=1 -> https://www.example.com/path?x=1"), found("Try www.example.com/path?x=1, then"))
        assertTrue(found("www.localhost and www.").isEmpty())
    }

    @Test
    fun `file names that end in a country domain are not links`() {
        assertTrue(found("Open README.md, run setup.sh, edit main.py and lib.rs").isEmpty())
        assertTrue(found("foo.bar.baz").isEmpty())
    }

    @Test
    fun `e-mail addresses become mailto links`() {
        assertEquals(listOf("dev@example.org -> mailto:dev@example.org"), found("Write to dev@example.org."))
        assertEquals(listOf("a.b+c@sub.example.co.uk -> mailto:a.b+c@sub.example.co.uk"), found("a.b+c@sub.example.co.uk"))
        assertTrue(found("an @mention and a@b").isEmpty())
    }

    @Test
    fun `an address with a user name is one URL and not a URL and an e-mail`() {
        assertEquals(listOf("https://user@host.com/x -> https://user@host.com/x"), found("https://user@host.com/x"))
    }

    @Test
    fun `the scheme may be in any case but must stand on its own`() {
        assertEquals(listOf("HTTPS://EXAMPLE.COM -> HTTPS://EXAMPLE.COM"), found("HTTPS://EXAMPLE.COM"))
        assertTrue(found("xhttps://example.com").isEmpty())
        assertTrue(found("awww.example.com").isEmpty())
        assertTrue(found("https:// alone and https://").isEmpty())
    }

    @Test
    fun `several in one run come out in order`() {
        assertEquals(
            listOf("https://a.com -> https://a.com", "me@b.org -> mailto:me@b.org", "www.c.net -> https://www.c.net"),
            found("https://a.com, me@b.org and www.c.net"),
        )
    }

    @Test
    fun `text with nothing to link is settled without a match`() {
        assertTrue(Autolinks.find("").isEmpty())
        assertTrue(Autolinks.find("plain prose, 5 + 3 = 8").isEmpty())
    }
}
