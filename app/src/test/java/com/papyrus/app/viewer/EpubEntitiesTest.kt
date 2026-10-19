package com.papyrus.app.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.xmlpull.v1.XmlPullParser

class EpubEntitiesTest {

    private fun cp(name: String) = EpubEntities.ALL.getValue(name).codePointAt(0)

    @Test
    fun `latin one entities sit at their code points`() {
        assertEquals(0xA0, cp("nbsp"))
        assertEquals(0xA9, cp("copy"))
        assertEquals(0xBF, cp("iquest"))
        assertEquals(0xC0, cp("Agrave"))
        assertEquals(0xDF, cp("szlig"))
        assertEquals(0xF7, cp("divide"))
        assertEquals(0xFF, cp("yuml"))
    }

    @Test
    fun `greek letters skip the hole after rho and keep the final sigma apart`() {
        assertEquals(0x391, cp("Alpha"))
        assertEquals(0x3A1, cp("Rho"))
        assertEquals(0x3A3, cp("Sigma"))
        assertEquals(0x3A9, cp("Omega"))
        assertEquals(0x3B1, cp("alpha"))
        assertEquals(0x3C2, cp("sigmaf"))
        assertEquals(0x3C3, cp("sigma"))
        assertEquals(0x3C9, cp("omega"))
    }

    @Test
    fun `typographic punctuation and symbols`() {
        assertEquals(0x2014, cp("mdash"))
        assertEquals(0x2013, cp("ndash"))
        assertEquals(0x2019, cp("rsquo"))
        assertEquals(0x201C, cp("ldquo"))
        assertEquals(0x2026, cp("hellip"))
        assertEquals(0x20AC, cp("euro"))
        assertEquals(0x2122, cp("trade"))
    }

    @Test
    fun `there are the 252 of html 4 less the four xml also defines`() {
        // HTML 4 names 252, among them amp, lt, gt and quot; apos is XML's alone, so none of the five is ours.
        assertEquals(252 - 4, EpubEntities.ALL.size)
        for (own in listOf("amp", "lt", "gt", "quot", "apos")) assertFalse(own, own in EpubEntities.ALL)
    }

    @Test
    fun `every entity is one character`() {
        for ((name, text) in EpubEntities.ALL) assertEquals(name, 1, text.codePointCount(0, text.length))
    }

    @Test
    fun `a parser that is taught them reads them in text`() {
        val parser = OfficeFixtures.parser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput("<p>a&nbsp;b &mdash; c &eacute; &amp; d</p>".byteInputStream(), null)
        EpubEntities.defineAll(parser)
        var text = ""
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.TEXT) text += parser.text
        }
        assertEquals("a\u00A0b \u2014 c \u00E9 & d", text)
        assertTrue(EpubEntities.ALL.isNotEmpty())
    }
}
