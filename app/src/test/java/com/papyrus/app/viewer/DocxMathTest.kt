package com.papyrus.app.viewer

import com.papyrus.app.data.DocumentFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Equations used to vanish from a .docx without a trace; they now read as typed text, so these pin
 * the shape of that text for the constructs Word actually produces.
 */
class DocxMathTest {

    private fun extract(body: String) = OfficeTextExtractor(
        mediaDir = null,
        newParserFactory = OfficeFixtures::parser,
    ).extract(OfficeFixtures.streamFactory(OfficeFixtures.docx(OfficeFixtures.docxBody(body))), DocumentFormat.DOCX)

    private fun r(text: String) = """<m:r><w:rPr><w:rFonts w:ascii="Cambria Math" w:hAnsi="Cambria Math"/></w:rPr><m:t>$text</m:t></m:r>"""

    private fun ctrl(pr: String) = """<m:$pr><m:ctrlPr><w:rPr><w:i/></w:rPr></m:ctrlPr></m:$pr>"""

    private fun frac(num: String, den: String) = "<m:f>${ctrl("fPr")}<m:num>$num</m:num><m:den>$den</m:den></m:f>"

    private fun sup(base: String, exp: String) = "<m:sSup>${ctrl("sSupPr")}<m:e>$base</m:e><m:sup>$exp</m:sup></m:sSup>"

    private fun sub(base: String, low: String) = "<m:sSub>${ctrl("sSubPr")}<m:e>$base</m:e><m:sub>$low</m:sub></m:sSub>"

    /** The text of a paragraph that holds only [math], as a display equation does. */
    private fun display(math: String): String {
        val body = """<w:p><m:oMathPara><m:oMath xmlns:m="http://schemas.openxmlformats.org/officeDocument/2006/math">$math</m:oMath></m:oMathPara></w:p>"""
        return (extract(body).single() as OfficeBlock.Paragraph).text
    }

    @Test
    fun `a fraction of plain terms is typed with a slash and brackets only where needed`() {
        assertEquals("(a+b)/c", display(frac(r("a+b"), r("c"))))
        assertEquals("x/y", display(frac(r("x"), r("y"))))
        assertEquals("(a+b)/(c+d)", display(frac(r("a+b"), r("c+d"))))
    }

    @Test
    fun `the quadratic formula reads as it would be typed`() {
        val radicand = """<m:rad><m:radPr><m:degHide m:val="1"/><m:ctrlPr/></m:radPr><m:deg/><m:e>${sup(r("b"), r("2"))}${r("\u22124ac")}</m:e></m:rad>"""

        val text = display(r("x=") + frac(r("\u2212b\u00B1") + radicand, r("2a")))

        assertEquals("x=(\u2212b\u00B1\u221A(b\u00B2\u22124ac))/2a", text)
    }

    @Test
    fun `exponents and indices use the real raised and lowered characters when they exist`() {
        assertEquals("x\u00B2", display(sup(r("x"), r("2"))))
        assertEquals("x\u207B\u00B9", display(sup(r("x"), r("\u22121"))))
        assertEquals("x_n", display(sub(r("x"), r("n"))))
        assertEquals("x\u2081", display(sub(r("x"), r("1"))))
        assertEquals("a_ij", display(sub(r("a"), r("ij"))))
    }

    @Test
    fun `a script with no raised form falls back to caret and brackets, never half converted`() {
        // "n+1" has a raised "n", but "x+y" has no raised letters; one stray glyph would misread.
        assertEquals("e^(x+y)", display(sup(r("e"), r("x+y"))))
        assertEquals("a_(n+1)", display(sub(r("a"), r("n+1"))))
    }

    @Test
    fun `a sub and superscript on one base come out together`() {
        val both = "<m:sSubSup>${ctrl("sSubSupPr")}<m:e>${r("x")}</m:e><m:sub>${r("i")}</m:sub><m:sup>${r("2")}</m:sup></m:sSubSup>"

        assertEquals("x_i\u00B2", display(both))
    }

    @Test
    fun `a sum carries its limits and its operand`() {
        val sum = """<m:nary><m:naryPr><m:chr m:val="${"\u2211"}"/><m:limLoc m:val="undOvr"/><m:ctrlPr/></m:naryPr>
            <m:sub>${r("i=1")}</m:sub><m:sup>${r("n")}</m:sup><m:e>${sup(r("i"), r("2"))}</m:e></m:nary>"""

        assertEquals("\u2211_(i=1)\u207F i\u00B2", display(sum))
    }

    @Test
    fun `an integral is the default sign and limits go in raised and lowered form`() {
        val integral = """<m:nary><m:naryPr><m:ctrlPr/></m:naryPr><m:sub>${r("0")}</m:sub><m:sup>${r("1")}</m:sup><m:e>${r("x dx")}</m:e></m:nary>"""

        assertEquals("\u222B\u2080\u00B9 x dx", display(integral))
    }

    @Test
    fun `brackets use the characters the equation names and separate items`() {
        val parens = "<m:d>${ctrl("dPr")}<m:e>${r("x+y")}</m:e></m:d>"
        val square = """<m:d><m:dPr><m:begChr m:val="["/><m:endChr m:val="]"/><m:sepChr m:val=","/></m:dPr><m:e>${r("a")}</m:e><m:e>${r("b")}</m:e></m:d>"""
        val bare = """<m:d><m:dPr><m:begChr m:val=""/><m:endChr m:val=""/></m:dPr><m:e>${r("x")}</m:e></m:d>"""

        assertEquals("(x+y)", display(parens))
        assertEquals("[a,b]", display(square))
        assertEquals("x", display(bare))
    }

    @Test
    fun `a function name is spaced from its argument unless the argument has its own bracket`() {
        val plain = "<m:func>${ctrl("funcPr")}<m:fName>${r("sin")}</m:fName><m:e>${r("x")}</m:e></m:func>"
        val bracketed = "<m:func>${ctrl("funcPr")}<m:fName>${r("sin")}</m:fName><m:e><m:d><m:e>${r("x")}</m:e></m:d></m:e></m:func>"

        assertEquals("sin x", display(plain))
        assertEquals("sin(x)", display(bracketed))
    }

    @Test
    fun `a limit keeps its variable below`() {
        val limit = "<m:limLow>${ctrl("limLowPr")}<m:e>${r("lim")}</m:e><m:lim>${r("x\u21920")}</m:lim></m:limLow>"

        assertEquals("lim_(x\u21920)", display(limit))
    }

    @Test
    fun `roots show their degree`() {
        fun root(degree: String) = """<m:rad><m:radPr><m:ctrlPr/></m:radPr><m:deg>${r(degree)}</m:deg><m:e>${r("x")}</m:e></m:rad>"""

        assertEquals("\u221Bx", display(root("3")))
        assertEquals("\u221Cx", display(root("4")))
        assertEquals("\u2075\u221Ax", display(root("5")))
    }

    @Test
    fun `a matrix reads row by row`() {
        fun row(vararg cells: String) = "<m:mr>${cells.joinToString("") { "<m:e>${r(it)}</m:e>" }}</m:mr>"
        val matrix = "<m:m><m:mPr><m:ctrlPr/></m:mPr>${row("1", "0")}${row("0", "1")}</m:m>"

        assertEquals("[1 0; 0 1]", display(matrix))
    }

    @Test
    fun `an accent follows its base`() {
        val hat = """<m:acc><m:accPr><m:chr m:val="${"\u0302"}"/><m:ctrlPr/></m:accPr><m:e>${r("x")}</m:e></m:acc>"""

        assertEquals("x\u0302", display(hat))
    }

    @Test
    fun `a construct that is not known contributes its words`() {
        val bar = """<m:bar><m:barPr><m:pos m:val="top"/><m:ctrlPr/></m:barPr><m:e>${r("xy")}</m:e></m:bar>"""

        assertEquals("xy", display(bar))
    }

    @Test
    fun `an inline equation sits in its sentence and the sentence keeps its own formatting`() {
        val body = """<w:p><w:r><w:rPr><w:b/></w:rPr><w:t xml:space="preserve">Let </w:t></w:r>
            <m:oMath>${sup(r("x"), r("2"))}</m:oMath>
            <w:r><w:t xml:space="preserve"> be even.</w:t></w:r></w:p>"""

        val paragraph = extract(body).single() as OfficeBlock.Paragraph

        assertEquals("Let x\u00B2 be even.", paragraph.text)
        // Only "Let " is bold: the bold run before the equation must not colour it.
        assertEquals(listOf(TextSpan(0, 4, CharFormat(bold = true))), paragraph.spans)
    }

    @Test
    fun `text written as an ordinary run inside an equation is kept`() {
        val math = """<m:r><m:rPr><m:nor/></m:rPr><w:rPr/><w:t>where</w:t></m:r>${r(" x")}"""

        assertEquals("where x", display(math))
    }

    @Test
    fun `an equation in a table cell lands in the cell`() {
        val body = """<w:tbl><w:tr><w:tc><w:p><m:oMath>${frac(r("a"), r("b"))}</m:oMath></w:p></w:tc></w:tr></w:tbl>"""

        val table = extract(body).single() as OfficeBlock.Table

        assertEquals("a/b", table.rows[0][0].text)
    }

    @Test
    fun `an empty equation adds nothing`() {
        val body = """<w:p><w:r><w:t>Before</w:t></w:r><m:oMath></m:oMath></w:p>"""

        assertEquals(listOf<OfficeBlock>(OfficeBlock.Paragraph("Before")), extract(body))
    }

    @Test
    fun `tracked formatting changes inside an equation do not unbalance the reading`() {
        val math = """<m:r><w:rPr><w:rPrChange w:id="1"><w:rPr><w:b/></w:rPr></w:rPrChange></w:rPr><m:t>x</m:t></m:r>"""
        val body = """<w:p><m:oMath>$math</m:oMath><w:r><w:t> after</w:t></w:r></w:p>"""

        assertEquals("x after", (extract(body).single() as OfficeBlock.Paragraph).text)
    }

    @Test
    fun `absurdly nested equations are bounded rather than crashing`() {
        val depth = 5_000
        val nested = "<m:sSup><m:e>".repeat(depth) + r("x") + "</m:e><m:sup></m:sup></m:sSup>".repeat(depth)
        val body = """<w:p><m:oMath>$nested</m:oMath><w:r><w:t>end</w:t></w:r></w:p>"""

        val text = (extract(body).single() as OfficeBlock.Paragraph).text

        // The body after the equation still reads, and nothing exploded on the way.
        assertTrue(text.endsWith("end"))
    }

    @Test
    fun `the raised and lowered tables line up character for character`() {
        // A drifted table would turn one digit into another, silently.
        val digits = "0123456789"
        assertEquals("\u2070\u00B9\u00B2\u00B3\u2074\u2075\u2076\u2077\u2078\u2079", display(sup(r("x"), r(digits))).drop(1))
        assertEquals("\u2080\u2081\u2082\u2083\u2084\u2085\u2086\u2087\u2088\u2089", display(sub(r("x"), r(digits))).drop(1))
        assertEquals("x\u207A\u207B\u207C\u207D\u207E", display(sup(r("x"), r("+-=()"))))
        assertEquals("x\u208A\u208B\u208C\u208D\u208E", display(sub(r("x"), r("+-=()"))))
    }
}
