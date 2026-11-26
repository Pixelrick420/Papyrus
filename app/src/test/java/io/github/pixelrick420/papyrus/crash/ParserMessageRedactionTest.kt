package io.github.pixelrick420.papyrus.crash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ParserMessageRedactionTest {

    private val frames = "\tat io.github.pixelrick420.papyrus.viewer.Reader.open(Reader.kt:42)\n\tat java.lang.Thread.run(Thread.java:1012)"

    @Test
    fun `an xml pull parser message keeps its position and drops the quoted text`() {
        val trace = "org.xmlpull.v1.XmlPullParserException: Unexpected token (position:TEXT Quarterly revenue was 4.2M@3:17 in " +
            "java.io.InputStreamReader@1a2b3c)\n\tat com.android.org.kxml2.io.KXmlParser.exception(KXmlParser.java:289)\n$frames"

        val redacted = redactParserMessages(trace)

        assertEquals(
            "org.xmlpull.v1.XmlPullParserException: Unexpected token (position:TEXT @3:17)\n" +
                "\tat com.android.org.kxml2.io.KXmlParser.exception(KXmlParser.java:289)\n$frames",
            redacted,
        )
    }

    @Test
    fun `a pdf parser message throws away what it quotes even under a plain IOException`() {
        val trace = "java.io.IOException: Error: Expected a long type at offset 42, instead got 'Salary: 90000'\n" +
            "\tat com.tom_roush.pdfbox.pdfparser.BaseParser.readLong(BaseParser.java:1011)\n$frames"

        val redacted = redactParserMessages(trace)

        assertEquals(
            "java.io.IOException: Error: Expected a long type at offset 42, instead got [quoted]\n" +
                "\tat com.tom_roush.pdfbox.pdfparser.BaseParser.readLong(BaseParser.java:1011)\n$frames",
            redacted,
        )
    }

    @Test
    fun `a parser exception wrapped by another is redacted inside the wrapper's message too`() {
        val trace = "java.lang.IllegalStateException: Can't read it: org.xml.sax.SAXParseException: " +
            "The element type \"secret plans\" must be terminated\n$frames"

        val redacted = redactParserMessages(trace)

        assertFalse(redacted, "secret" in redacted)
        assertTrue(redacted, redacted.startsWith("java.lang.IllegalStateException: Can't read it: org.xml.sax.SAXParseException: "))
        assertTrue(redacted, redacted.endsWith("\n$frames"))
    }

    @Test
    fun `only the first line of a parser message is kept, and the frames after it survive`() {
        val trace = "org.xml.sax.SAXParseException; lineNumber: 4; columnNumber: 9; Content:\nconfidential memo line\n" +
            "more text 'private'\n$frames"

        assertEquals("org.xml.sax.SAXParseException; lineNumber: 4; columnNumber: 9; Content: […]\n$frames", redactParserMessages(trace))
    }

    @Test
    fun `a cause chain is handled exception by exception`() {
        val trace = "java.lang.RuntimeException: could not open 'Plan.xml'\n$frames\n" +
            "Caused by: org.xmlpull.v1.XmlPullParserException: bad token 'hunter2' (position:START_TAG <pw>@1:5 in java.io.StringReader@7)\n" +
            "\tat org.kxml2.io.KXmlParser.next(KXmlParser.java:1)\n\t... 3 more"

        val redacted = redactParserMessages(trace)

        assertTrue(redacted, redacted.startsWith("java.lang.RuntimeException: could not open 'Plan.xml'\n$frames\n"))
        assertTrue(redacted, redacted.contains("\nCaused by: org.xmlpull.v1.XmlPullParserException: bad token [quoted] (position:START_TAG @1:5)\n"))
        assertTrue(redacted, redacted.endsWith("\n\t... 3 more"))
    }

    @Test
    fun `an exception from anywhere else is left byte for byte`() {
        val trace = "java.io.IOException: Couldn't read 'My Contract.txt'\n$frames\nCaused by: java.lang.IllegalStateException: no 'x'\n\t... 2 more"

        assertEquals(trace, redactParserMessages(trace))
    }

    @Test
    fun `an apostrophe inside a word is not a quote, an unclosed quote still goes`() {
        val trace = "org.xml.sax.SAXParseException: can't parse 'abc' and 'unclosed\n$frames"

        assertEquals("org.xml.sax.SAXParseException: can't parse [quoted] and [quoted]\n$frames", redactParserMessages(trace))
    }

    @Test
    fun `a long message is cut, with no quote left inside it`() {
        val trace = "org.xmlpull.v1.XmlPullParserException: ${"word ".repeat(100)}\n$frames"

        val line = redactParserMessages(trace).lines().first()

        assertEquals("org.xmlpull.v1.XmlPullParserException: ".length + 120 + 1, line.length)
        assertTrue(line, line.endsWith("…"))
    }

    @Test
    fun `a parser exception without a message and a trace with none are untouched`() {
        val bare = "org.xmlpull.v1.XmlPullParserException\n$frames"

        assertEquals(bare, redactParserMessages(bare))
        assertEquals("", redactParserMessages(""))
        assertEquals(frames, redactParserMessages(frames))
    }
}
