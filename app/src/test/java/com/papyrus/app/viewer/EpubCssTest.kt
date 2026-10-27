package com.papyrus.app.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The CSS subset on its own: cascade, selectors, at-rules and malformed input. */
class EpubCssTest {

    private fun sheet(css: String) = CssSheet(parseCss(css))

    private fun decl(css: String, tag: String = "p", id: String? = null, vararg classes: String) =
        sheet(css).declFor(tag, id, classes.toSet())

    @Test
    fun `an id beats a class beats a type`() {
        val css = "#a { font-weight: normal } .c { font-weight: bold } p { font-weight: normal }"
        assertEquals(false, decl(css, "p", "a", "c")!!.bold)
        assertEquals(true, decl(css, "p", null, "c")!!.bold)
        assertEquals(false, decl(css, "p")!!.bold)
    }

    @Test
    fun `the later rule wins a tie`() {
        assertEquals(true, decl(".a { font-weight: normal } .b { font-weight: bold }", "p", null, "a", "b")!!.bold)
        assertEquals(false, decl(".b { font-weight: bold } .a { font-weight: normal }", "p", null, "a", "b")!!.bold)
    }

    @Test
    fun `an id beats any number of classes`() {
        val css = ".a.b.c { font-style: italic } #x { font-style: normal }"
        assertEquals(false, decl(css, "p", "x", "a", "b", "c")!!.italic)
    }

    @Test
    fun `each property is decided on its own`() {
        val d = decl("p { font-weight: bold; text-align: center } .x { font-style: italic }", "p", null, "x")!!
        assertEquals(true, d.bold)
        assertEquals(true, d.italic)
        assertEquals(BlockAlign.CENTER, d.align)
    }

    @Test
    fun `a compound selector matches only that combination`() {
        val css = "p.center { text-align: center }"
        assertEquals(BlockAlign.CENTER, decl(css, "p", null, "center")!!.align)
        assertNull(decl(css, "div", null, "center"))
        assertNull(decl(css, "p"))
        assertNull(decl(css, "p", null, "other"))
    }

    @Test
    fun `a star matches everything and a type ignores case`() {
        assertEquals(true, decl("* { font-weight: bold }", "span")!!.bold)
        assertEquals(true, decl("*.k { font-weight: bold }", "span", null, "k")!!.bold)
        assertEquals(true, decl("P { font-weight: bold }", "p")!!.bold)
    }

    @Test
    fun `a selector list applies each valid item and drops the others`() {
        val css = ".center, #ch1 > p, a:hover, [lang] , h1 em { text-align: center }"
        assertEquals(BlockAlign.CENTER, decl(css, "p", null, "center")!!.align)
        assertNull(decl(css, "p", "ch1"))
        assertNull(decl(css, "a"))
        assertNull(decl(css, "em"))
    }

    @Test
    fun `a selector with a combinator pseudo class or attribute test is dropped without harm`() {
        for (selector in listOf("div p", "div>p", "div + p", "a ~ b", "p:first-child", "p::before", "a[href]", "p.a:not(.b)")) {
            assertTrue(selector, parseCss("$selector { font-weight: bold }").isEmpty())
        }
        assertEquals(1, parseCss("div p { font-weight: bold } p { font-weight: bold }").size)
    }

    @Test
    fun `at-rules are skipped without swallowing the next rule`() {
        val css = """
            @import url("other.css");
            @charset "utf-8";
            @media screen { p { font-weight: normal } .x { color: red } }
            @font-face { font-family: F; src: url(f.ttf) }
            @page { margin: 1em }
            p { font-weight: bold }
        """.trimIndent()
        val rules = parseCss(css)
        assertEquals(1, rules.size)
        assertEquals(true, rules.single().decl.bold)
    }

    @Test
    fun `font weight is a tri state`() {
        for ((value, bold) in listOf("bold" to true, "700" to true, "600" to true, "900" to true, "normal" to false, "400" to false, "500" to false)) {
            assertEquals(value, bold, decl("p { font-weight: $value }")!!.bold)
        }
        assertNull(decl("p { font-weight: bolder }"))
        assertNull(decl("p { font-weight: inherit }"))
    }

    @Test
    fun `font style and text align`() {
        assertEquals(true, decl("p { font-style: italic }")!!.italic)
        assertEquals(true, decl("p { font-style: oblique }")!!.italic)
        assertEquals(false, decl("p { font-style: normal }")!!.italic)
        assertEquals(BlockAlign.CENTER, decl("p { text-align: center }")!!.align)
        assertEquals(BlockAlign.END, decl("p { text-align: right }")!!.align)
        assertEquals(BlockAlign.START, decl("p { text-align: left }")!!.align)
        assertEquals(BlockAlign.START, decl("p { text-align: start }")!!.align)
        assertEquals(BlockAlign.START, decl("p { text-align: justify }")!!.align)
        assertNull(decl("p { text-align: weird }"))
    }

    @Test
    fun `colours fonts sizes and backgrounds are not taken`() {
        assertNull(decl("p { color: red; font-family: serif; font-size: 2em; background: #000; margin: 1em }"))
    }

    @Test
    fun `a malformed declaration is ignored and its neighbours are not`() {
        val d = decl("p { font-weight bold; : x; font-style: italic; ;; text-align }")!!
        assertEquals(true, d.italic)
        assertNull(d.bold)
        assertNull(d.align)
    }

    @Test
    fun `important is ordinary priority`() {
        assertEquals(true, decl("p { font-weight: bold !important }")!!.bold)
        assertEquals(false, decl("p { font-weight: bold !important } p { font-weight: normal }")!!.bold)
    }

    @Test
    fun `broken sheets do not throw`() {
        assertTrue(parseCss("p { font-weight: bold").isEmpty())
        assertTrue(parseCss("/* never ends p { font-weight: bold }").isEmpty())
        assertTrue(parseCss("p {{{ }}} ,, {").isEmpty())
        assertTrue(parseCss("@media screen { p { x: y }").isEmpty())
        assertTrue(parseCss("@import").isEmpty())
        assertEquals(1, parseCss("/* c */ p /* x */ { font-weight: bold /* y */ }").size)
        assertTrue(parseCss("").isEmpty())
    }

    @Test
    fun `rules past the cap are ignored`() {
        val css = (1..(MAX_CSS_RULES + 200)).joinToString("\n") { ".c$it { font-weight: bold }" }
        assertEquals(MAX_CSS_RULES, parseCss(css).size)
        assertNull(sheet(css).declFor("p", null, setOf("c${MAX_CSS_RULES + 100}")))
        assertEquals(true, sheet(css).declFor("p", null, setOf("c1"))!!.bold)
    }

    @Test
    fun `a selector with too many parts is dropped`() {
        assertTrue(parseCss(".a.b.c.d.e { font-weight: bold }").isEmpty())
        assertEquals(1, parseCss("p.a.b.c { font-weight: bold }").size)
        assertTrue(parseCss("#a#b { font-weight: bold }").isEmpty())
    }

    @Test
    fun `style elements are found in any case and nowhere else`() {
        val xhtml = """<html><head><STYLE type="text/css">p { font-weight: bold }</STYLE></head>
            <body><p style="font-style: italic">x</p><styles>no</styles><style/></body></html>""".toByteArray()
        val text = stylesIn(xhtml)
        assertTrue(text.contains("font-weight: bold"))
        assertTrue(!text.contains("italic"))
        assertTrue(!text.contains("no"))
    }

    @Test
    fun `a style that is commented out is not read and one after the body text is`() {
        val xhtml = "<html><head><!-- <style>p { font-weight: bold }</style> --></head><body><p>x</p><style>.late { font-style: italic }</style></body></html>"
        val text = stylesIn(xhtml.toByteArray())
        assertTrue(!text.contains("bold"))
        assertTrue(text.contains(".late"))
    }

    @Test
    fun `style scanning survives the odd and the truncated`() {
        assertEquals("", stylesIn(ByteArray(0)))
        assertEquals("", stylesIn("<style".toByteArray()))
        assertEquals("", stylesIn("<!-- never".toByteArray()))
        assertTrue(stylesIn("<style>p { font-weight: bold }".toByteArray()).contains("bold"))
        assertTrue(stylesIn("<style><![CDATA[ p { font-weight: bold } ]]></style>".toByteArray()).let { parseCss(it).size == 1 })
        assertTrue(stylesIn("<style>.caf\u00E9 { font-weight: bold }</style>".toByteArray()).contains("caf\u00E9"))
    }

    @Test
    fun `the chapter's own style beats the book's on a tie`() {
        val book = parseCss("p { font-weight: normal }")
        val chapter = "<html><head><style>p { font-weight: bold }</style></head></html>".toByteArray()
        assertEquals(true, sheetFor(book, chapter).declFor("p", null, emptySet())!!.bold)
        assertEquals(false, sheetFor(book, "<html/>".toByteArray()).declFor("p", null, emptySet())!!.bold)
    }

    @Test
    fun `a layer wins only where it speaks`() {
        val merged = CssDecl(bold = true).mergeOver(CssDecl(bold = false, italic = true, align = BlockAlign.CENTER))
        assertEquals(true, merged.bold)
        assertEquals(true, merged.italic)
        assertEquals(BlockAlign.CENTER, merged.align)
        assertEquals(CharFormat(bold = false, italic = true), CharFormat(bold = true).withCss(CssDecl(bold = false, italic = true)))
        assertEquals(CharFormat(bold = true), CharFormat(bold = true).withCss(CssDecl.EMPTY))
    }
}
