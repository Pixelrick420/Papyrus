package com.papyrus.app.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
