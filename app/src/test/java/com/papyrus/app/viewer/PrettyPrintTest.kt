package com.papyrus.app.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PrettyPrintTest {

    private fun json(text: String) = PrettyPrint.format(PrettyKind.JSON, text)

    private fun xml(text: String) = PrettyPrint.format(PrettyKind.XML, text)

    private fun html(text: String) = PrettyPrint.format(PrettyKind.HTML, text)

    @Test
    fun `the file name picks the scanner, by extension and case-insensitively`() {
        assertEquals(PrettyKind.JSON, PrettyPrint.kindFor("a.json"))
        assertEquals(PrettyKind.JSON, PrettyPrint.kindFor("A.JSON"))
        assertEquals(PrettyKind.JSON, PrettyPrint.kindFor("x.jsonc"))
        assertEquals(PrettyKind.JSON, PrettyPrint.kindFor("x.json5"))
        assertEquals(PrettyKind.XML, PrettyPrint.kindFor("x.xml"))
        assertEquals(PrettyKind.XML, PrettyPrint.kindFor("x.svg"))
        assertEquals(PrettyKind.XML, PrettyPrint.kindFor("x.xhtml"))
        assertEquals(PrettyKind.HTML, PrettyPrint.kindFor("x.html"))
        assertEquals(PrettyKind.HTML, PrettyPrint.kindFor("x.htm"))
    }

    @Test
    fun `names outside the approved scope never take the string path`() {
        assertNull(PrettyPrint.kindFor("x.txt"))
        assertNull(PrettyPrint.kindFor("x.kt"))
        assertNull(PrettyPrint.kindFor("x.yaml"))
        assertNull(PrettyPrint.kindFor("x.vue"))
        assertNull(PrettyPrint.kindFor("x.svelte"))
        assertNull(PrettyPrint.kindFor("data"))
        assertNull(PrettyPrint.kindFor(null))
    }

    @Test
    fun `minified json is indented`() {
        assertEquals(
            "{\n  \"a\": 1,\n  \"b\": [\n    true,\n    null,\n    {\n      \"c\": \"d\"\n    }\n  ],\n" +
                "  \"e\": {\n    \"f\": 2\n  }\n}",
            json("""{"a":1,"b":[true,null,{"c":"d"}],"e":{"f":2}}"""),
        )
    }

    @Test
    fun `formatting formatted json is byte-identical`() {
        val once = json("""{"a":1,"b":[true,null,{"c":"d"}],"e":{"f":2}}""")

        assertEquals(once, json(once!!))
    }

    @Test
    fun `pre-existing whitespace between tokens collapses`() {
        assertEquals(
            "{\n  \"a\": 1,\n  \"b\": []\n}",
            json("{ \"a\"  :  1,\n\n \"b\" : [ ] }"),
        )
    }

    @Test
    fun `an empty object or array stays on one line`() {
        assertEquals("{}\n{}", json("{} {}"))
        assertEquals("[\n  []\n]", json("[[]]"))
    }

    @Test
    fun `braces, brackets, commas and colons inside strings survive untouched`() {
        val source = "{\"s\":\"a{b}c,d:e\",\"esc\":\"x\\\"y\",\"uni\":\"\\u00e9\"}"

        assertEquals(
            "{\n  \"s\": \"a{b}c,d:e\",\n  \"esc\": \"x\\\"y\",\n  \"uni\": \"\\u00e9\"\n}",
            json(source),
        )
    }

    @Test
    fun `number lexemes are copied byte-exact`() {
        val source = "[1E+3,-0.0,12345678901234567890,0.1000000000000000055511151231257827]"

        assertEquals(
            "[\n  1E+3,\n  -0.0,\n  12345678901234567890,\n  0.1000000000000000055511151231257827\n]",
            json(source),
        )
    }

    @Test
    fun `jsonc comments are preserved and the comma after a line comment stays structure`() {
        assertEquals(
            "{\n  // leading\n  \"a\": 1,\n  /* block */\n  \"b\": 2\n  // trailing\n  ,\n  \"c\": 3\n}",
            json("{\n// leading\n\"a\":1, /* block */\n\"b\":2 // trailing\n,\"c\":3\n}"),
        )
    }

    @Test
    fun `whitespace-only json passes through unchanged, not as a failure`() {
        val source = "  \n\t "

        assertEquals(source, json(source))
    }

    @Test
    fun `an unterminated string fails`() {
        assertNull(json("{\"a\":\"abc"))
    }

    @Test
    fun `an unterminated block comment fails`() {
        assertNull(json("{\"a\":1 /* c"))
    }

    @Test
    fun `a mismatched or unmatched bracket fails`() {
        assertNull(json("{\"a\":1]"))
        assertNull(json("[1,2}"))
    }

    @Test
    fun `a truncated json file fails instead of showing half a structure`() {
        assertNull(json("{\"a\":"))
    }

    @Test
    fun `nested xml elements are indented and self-closing tags keep their slash`() {
        assertEquals(
            "<a>\n  <b>\n    <c>x</c>\n  </b>\n  <d y=\"2\"/>\n</a>",
            xml("<a><b><c>x</c></b><d y=\"2\"/></a>"),
        )
    }

    @Test
    fun `formatting formatted xml is byte-identical`() {
        val once = xml("<a><b><c>x</c></b><d y=\"2\"/></a>")

        assertEquals(once, xml(once!!))
    }

    @Test
    fun `attributes, namespaces and a greater-than inside quotes are byte-exact`() {
        assertEquals(
            "<r xmlns:ns='http://n'>\n  <ns:el ns:a='1>0' b=\"2\"/>\n</r>",
            xml("<r xmlns:ns='http://n'><ns:el ns:a='1>0' b=\"2\"/></r>"),
        )
    }

    @Test
    fun `comment cdata and processing-instruction bodies are untouched`() {
        assertEquals(
            "<?xml version=\"1.0\"?>\n<a>\n  <!--x<y>-->\n  <![CDATA[<b> & ]]>text</a>",
            xml("<?xml version=\"1.0\"?>\n<a><!--x<y>--><![CDATA[<b> & ]]>text</a>"),
        )
    }

    @Test
    fun `mixed content stays inline so its words keep their spaces`() {
        val source = "<p>word <em>word</em> word</p>"

        assertEquals(source, xml(source))
    }

    @Test
    fun `mismatched nesting fails`() {
        assertNull(xml("<a><b></a></b>"))
        assertNull(xml("<a><b></b>"))
        assertNull(xml("</a>"))
    }

    @Test
    fun `an unterminated tag, comment or element at eof fails`() {
        assertNull(xml("<div"))
        assertNull(xml("<div><!-- x"))
        assertNull(xml("<a>"))
    }

    @Test
    fun `html void elements and implied closes format without failing`() {
        assertEquals("<li>a<li>b", html("<li>a<li>b"))
        assertEquals("<p>x<br>y</p>", html("<p>x<br>y</p>"))
        assertEquals("<ul>\n  <li>a<li>b<br>\n    </ul>", html("<ul><li>a<li>b<br></ul>"))
    }

    @Test
    fun `script and style bodies are code, not markup`() {
        assertEquals(
            "<div>\n  <script>if (a<b) { x(\"</div>\"); }</script>\n</div>",
            html("<div><script>if (a<b) { x(\"</div>\"); }</script></div>"),
        )
        assertEquals("<style>a > b { color: red; }</style>", html("<style>a > b { color: red; }</style>"))
    }

    @Test
    fun `an unterminated tag fails for html too`() {
        assertNull(html("<div"))
        assertNull(html("<div><script>if (a<b)"))
    }

    @Test
    fun `the default indent is two spaces`() {
        assertEquals(2, PrettyPrint.DEFAULT_INDENT)
        assertEquals(PrettyPrint.format(PrettyKind.JSON, "{\"a\":[1]}"), PrettyPrint.format(PrettyKind.JSON, "{\"a\":[1]}", 2))
    }

    @Test
    fun `json indents by the width asked for`() {
        val source = """{"a":[1,{"b":2}],"c":{}}"""

        assertEquals(
            "{\n    \"a\": [\n        1,\n        {\n            \"b\": 2\n        }\n    ],\n    \"c\": {}\n}",
            PrettyPrint.format(PrettyKind.JSON, source, 4),
        )
        assertEquals("{\n" + " ".repeat(8) + "\"a\": 1\n}", PrettyPrint.format(PrettyKind.JSON, """{"a":1}""", 8))
        assertEquals("{\n   \"a\": 1\n}", PrettyPrint.format(PrettyKind.JSON, """{"a":1}""", 3))
    }

    @Test
    fun `xml and html indent by the width asked for`() {
        assertEquals("<a>\n    <b>\n        <c/>\n    </b>\n</a>", PrettyPrint.format(PrettyKind.XML, "<a><b><c/></b></a>", 4))
        assertEquals(
            "<div>\n   <p>x</p>\n   <ul>\n      <li>y</li>\n   </ul>\n</div>",
            PrettyPrint.format(PrettyKind.HTML, "<div><p>x</p><ul><li>y</li></ul></div>", 3),
        )
    }

    @Test
    fun `only the leading whitespace changes with the width`() {
        val source = """{"s":"a  b","n":[1.50,2E+3],"t":{"k":"<x>"}}"""
        val two = PrettyPrint.format(PrettyKind.JSON, source, 2)!!
        val eight = PrettyPrint.format(PrettyKind.JSON, source, 8)!!

        assertEquals(two.lines().map { it.trimStart() }, eight.lines().map { it.trimStart() })
    }

    @Test
    fun `formatting formatted text at the same width changes nothing, at any width`() {
        val json = """{"a":1,"b":[true,null,{"c":"d"}]}"""
        val html = "<div><p>a <b>b</b> c</p><ul><li>x</li></ul></div>"
        for (width in PrettyPrint.INDENT_CHOICES) {
            val formattedJson = PrettyPrint.format(PrettyKind.JSON, json, width)!!
            assertEquals("json at $width", formattedJson, PrettyPrint.format(PrettyKind.JSON, formattedJson, width))
            val formattedHtml = PrettyPrint.format(PrettyKind.HTML, html, width)!!
            assertEquals("html at $width", formattedHtml, PrettyPrint.format(PrettyKind.HTML, formattedHtml, width))
        }
    }

    @Test
    fun `text formatted at one width reflows to another`() {
        val narrow = PrettyPrint.format(PrettyKind.JSON, """{"a":{"b":1}}""", 2)!!

        assertEquals(
            PrettyPrint.format(PrettyKind.JSON, """{"a":{"b":1}}""", 4),
            PrettyPrint.format(PrettyKind.JSON, narrow, 4),
        )
    }

    @Test
    fun `a width Settings does not offer falls back to the default`() {
        val expected = PrettyPrint.format(PrettyKind.JSON, """{"a":1}""")

        assertEquals(expected, PrettyPrint.format(PrettyKind.JSON, """{"a":1}""", 0))
        assertEquals(expected, PrettyPrint.format(PrettyKind.JSON, """{"a":1}""", -3))
        assertEquals(expected, PrettyPrint.format(PrettyKind.JSON, """{"a":1}""", 7))
        assertEquals(expected, PrettyPrint.format(PrettyKind.JSON, """{"a":1}""", Int.MAX_VALUE))
    }

    @Test
    fun `a stored width is kept when it is a choice and repaired when it is not`() {
        PrettyPrint.INDENT_CHOICES.forEach { assertEquals(it, PrettyPrint.indentOrDefault(it)) }
        assertEquals(PrettyPrint.DEFAULT_INDENT, PrettyPrint.indentOrDefault(0))
        assertEquals(PrettyPrint.DEFAULT_INDENT, PrettyPrint.indentOrDefault(5))
        assertEquals(PrettyPrint.DEFAULT_INDENT, PrettyPrint.indentOrDefault(-1))
        assertTrue(PrettyPrint.DEFAULT_INDENT in PrettyPrint.INDENT_CHOICES)
    }

    @Test
    fun `failures stay failures at every width`() {
        for (width in PrettyPrint.INDENT_CHOICES) {
            assertNull(PrettyPrint.format(PrettyKind.JSON, "{\"a\":", width))
            assertNull(PrettyPrint.format(PrettyKind.XML, "<a><b></a>", width))
            assertNull(PrettyPrint.format(PrettyKind.HTML, "<div", width))
        }
    }
}
