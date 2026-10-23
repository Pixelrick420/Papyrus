package com.papyrus.app.viewer

import com.papyrus.app.viewer.DocRichFixtures.CCV
import com.papyrus.app.viewer.DocRichFixtures.CFBOLD
import com.papyrus.app.viewer.DocRichFixtures.CFDSTRIKE
import com.papyrus.app.viewer.DocRichFixtures.CFITALIC
import com.papyrus.app.viewer.DocRichFixtures.CFSTRIKE
import com.papyrus.app.viewer.DocRichFixtures.CHIGHLIGHT
import com.papyrus.app.viewer.DocRichFixtures.CHPSPOS
import com.papyrus.app.viewer.DocRichFixtures.CICO
import com.papyrus.app.viewer.DocRichFixtures.CISS
import com.papyrus.app.viewer.DocRichFixtures.CISTD
import com.papyrus.app.viewer.DocRichFixtures.CKUL
import com.papyrus.app.viewer.DocRichFixtures.CPLAIN
import com.papyrus.app.viewer.DocRichFixtures.CRGFTC0
import com.papyrus.app.viewer.DocRichFixtures.CRGFTC2
import com.papyrus.app.viewer.DocRichFixtures.CSHD
import com.papyrus.app.viewer.DocRichFixtures.CSHD80
import com.papyrus.app.viewer.DocRichFixtures.DEFAULT_STYLES
import com.papyrus.app.viewer.DocRichFixtures.Para
import com.papyrus.app.viewer.DocRichFixtures.Run
import com.papyrus.app.viewer.DocRichFixtures.Style
import com.papyrus.app.viewer.DocRichFixtures.grpprl
import com.papyrus.app.viewer.DocRichFixtures.sprm
import com.papyrus.app.viewer.DocRichFixtures.sprmColor
import com.papyrus.app.viewer.DocRichFixtures.sprmVar
import com.papyrus.app.viewer.DocRichFixtures.sprmWord
import org.junit.Assert.assertEquals
import org.junit.Test

class DocCharFormatTest {

    private fun extract(bytes: ByteArray) = DocTextExtractor.extract(DocFixtures.streamFactory(bytes))

    private val bold = CharFormat(bold = true)
    private val italic = CharFormat(italic = true)

    private fun on(opcode: Int) = sprm(opcode, 1)

    /** The only block of a one-paragraph document, as a paragraph. */
    private fun only(vararg runs: Run, istd: Int = 0, styles: List<Style?> = DEFAULT_STYLES, fonts: List<String> = emptyList()) =
        extract(DocRichFixtures.doc(listOf(Para(runs.toList(), istd)), styles, fonts = fonts)).single()

    private fun spansOf(block: OfficeBlock): List<TextSpan> = when (block) {
        is OfficeBlock.Paragraph -> block.spans
        is OfficeBlock.Heading -> block.spans
        is OfficeBlock.ListItem -> block.spans
        else -> error("not a text block: $block")
    }

    @Test
    fun eachKindOfRunBecomesASpanOverExactlyItsText() {
        val block = only(
            Run("plain "),
            Run("bold", on(CFBOLD)),
            Run(" "),
            Run("italic", on(CFITALIC)),
            Run(" "),
            Run("under", sprm(CKUL, 1)),
            Run(" "),
            Run("strike", on(CFSTRIKE)),
            Run(" "),
            Run("double", on(CFDSTRIKE)),
        )
        assertEquals(
            OfficeBlock.Paragraph(
                "plain bold italic under strike double",
                listOf(
                    TextSpan(6, 10, bold),
                    TextSpan(11, 17, italic),
                    TextSpan(18, 23, CharFormat(underline = true)),
                    TextSpan(24, 30, CharFormat(strike = true)),
                    TextSpan(31, 37, CharFormat(strike = true)),
                ),
            ),
            block,
        )
    }

    @Test
    fun neighbouringRunsWithOneFormatAreOneSpan() {
        val block = only(Run("a", on(CFBOLD)), Run("b", on(CFBOLD)), Run(" c"))
        assertEquals(listOf(TextSpan(0, 2, bold)), spansOf(block))
    }

    @Test
    fun combinedFormatsShareASpan() {
        val block = only(Run("both", grpprl(on(CFBOLD), on(CFITALIC), sprm(CKUL, 2))))
        assertEquals(listOf(TextSpan(0, 4, CharFormat(bold = true, italic = true, underline = true))), spansOf(block))
    }

    @Test
    fun anExplicitOffIsNotFormatting() {
        val block = only(Run("off", grpprl(sprm(CFBOLD, 0), sprm(CKUL, 0))))
        assertEquals(emptyList<TextSpan>(), spansOf(block))
    }

    @Test
    fun superscriptAndSubscriptComeFromEitherSprm() {
        val block = only(
            Run("a"), Run("1", sprm(CISS, 1)), Run("b"), Run("2", sprm(CISS, 2)),
            Run("c"), Run("3", sprmWord(CHPSPOS, 8)), Run("d"), Run("4", sprmWord(CHPSPOS, 0xFFFE)), Run("e", sprmWord(CHPSPOS, 0)),
        )
        assertEquals(
            listOf(
                TextSpan(1, 2, CharFormat(script = ScriptShift.SUPER)),
                TextSpan(3, 4, CharFormat(script = ScriptShift.SUB)),
                TextSpan(5, 6, CharFormat(script = ScriptShift.SUPER)),
                TextSpan(7, 8, CharFormat(script = ScriptShift.SUB)),
            ),
            spansOf(block),
        )
    }

    @Test
    fun aMonospacedFaceIsMonoWhichFontSprmNamesIt() {
        val fonts = listOf("Times New Roman", "Courier New", "Calibri")
        val block = only(
            Run("serif", sprmWord(CRGFTC0, 0)),
            Run("mono", sprmWord(CRGFTC0, 1)),
            Run("other", sprmWord(CRGFTC2, 1)),
            Run("ascii wins", grpprl(sprmWord(CRGFTC0, 2), sprmWord(CRGFTC2, 1))),
            Run("unknown", sprmWord(CRGFTC0, 40)),
            fonts = fonts,
        )
        // "mono" and "other" are both monospaced, so they read as one span.
        assertEquals(listOf(TextSpan(5, 14, CharFormat(mono = true))), spansOf(block))
    }

    @Test
    fun colourIsReadFromAnIndexOrAnRgbValue() {
        val block = only(
            Run("red", sprm(CICO, 6)),
            Run("rgb", sprmColor(CCV, 0x336699)),
            Run("both", grpprl(sprmColor(CCV, 0x123456), sprm(CICO, 6))),
            Run("auto", sprm(CICO, 0)),
            Run("past", sprm(CICO, 40)),
        )
        assertEquals(
            listOf(
                TextSpan(0, 3, CharFormat(color = 0xFF0000)),
                TextSpan(3, 6, CharFormat(color = 0x336699)),
                TextSpan(6, 10, CharFormat(color = 0x123456)),
            ),
            spansOf(block),
        )
    }

    @Test
    fun highlightAndShadingGiveABackground() {
        val block = only(
            Run("hi", sprm(CHIGHLIGHT, 7)),
            Run("clear", sprmWord(CSHD80, 4 shl 5)),
            Run("solid", sprmWord(CSHD80, (1 shl 10) or 6)),
            Run("shd", sprmVar(CSHD, shd(fore = 0, back = 0x00FF00, pattern = 0))),
            Run("half", sprmVar(CSHD, shd(fore = 0, back = 0xFFFFFF, pattern = 8))),
            Run("both", grpprl(sprm(CHIGHLIGHT, 7), sprmWord(CSHD80, 6 shl 5))),
            Run("none", sprm(CHIGHLIGHT, 0)),
        )
        assertEquals(
            listOf(
                TextSpan(0, 2, CharFormat(background = 0xFFFF00)),
                TextSpan(2, 7, CharFormat(background = 0x00FF00)),
                TextSpan(7, 12, CharFormat(background = 0xFF0000)),
                TextSpan(12, 15, CharFormat(background = 0x00FF00)),
                TextSpan(15, 19, CharFormat(background = 0x7F7F7F)),
                TextSpan(19, 23, CharFormat(background = 0xFFFF00)),
            ),
            spansOf(block),
        )
    }

    @Test
    fun aParagraphStyleGivesItsTextItsFormatting() {
        val block = only(Run("Heading text"), istd = 1, styles = styled(HEADING_BOLD))
        assertEquals(OfficeBlock.Heading("Heading text", 1, listOf(TextSpan(0, 12, bold))), block)
    }

    @Test
    fun aStyleWithBothParagraphAndCharacterSprmsGivesBoth() {
        // Both UPXs are odd in length (a sprm is three bytes), so each is padded before the next begins.
        val centered = Style("Centered Bold", sti = USER, base = 0, papx = sprm(DocRichFixtures.PJC, 1), chpx = on(CFBOLD))
        val block = only(Run("both"), istd = 1, styles = listOf(DocRichFixtures.NORMAL, centered))
        assertEquals(OfficeBlock.Paragraph("both", listOf(TextSpan(0, 4, bold)), align = BlockAlign.CENTER), block)
    }

    @Test
    fun aRunCanUndoWhatTheStyleGives() {
        val block = only(Run("Plain ", sprm(CFBOLD, 0)), Run("bold again"), istd = 1, styles = styled(HEADING_BOLD))
        assertEquals(listOf(TextSpan(6, 16, bold)), spansOf(block))
    }

    @Test
    fun aToggleOperandIsRelativeToTheStyle() {
        val styles = listOf(DocRichFixtures.NORMAL, Style("Bold", sti = USER, base = 0, chpx = on(CFBOLD)))
        val block = only(
            Run("flip", sprm(CFBOLD, 0x81)),
            Run("same", sprm(CFBOLD, 0x80)),
            istd = 1,
            styles = styles,
        )
        assertEquals(listOf(TextSpan(4, 8, bold)), spansOf(block))
        // Against an unformatted paragraph the same operand turns it on.
        val plain = only(Run("flip", sprm(CFBOLD, 0x81)), Run("same", sprm(CFBOLD, 0x80)))
        assertEquals(listOf(TextSpan(0, 4, bold)), spansOf(plain))
    }

    @Test
    fun aStyleInheritsItsBaseStylesFormatting() {
        val styles = DEFAULT_STYLES + Style("Quote", sti = USER, base = 0, chpx = on(CFITALIC)) +
            Style("Red Quote", sti = USER, base = 4, chpx = sprm(CICO, 6))
        val block = only(Run("quoted"), istd = 5, styles = styles)
        assertEquals(listOf(TextSpan(0, 6, CharFormat(italic = true, color = 0xFF0000))), spansOf(block))
    }

    @Test
    fun aCharacterStyleFormatsTheRunsThatNameIt() {
        val styles = DEFAULT_STYLES +
            Style("Strong", sti = USER, sgc = 2, base = 0, chpx = on(CFBOLD)) +
            Style("Strong Emphasis", sti = USER, sgc = 2, base = 4, chpx = on(CFITALIC))
        val block = only(
            Run("plain "),
            Run("strong", sprmWord(CISTD, 4)),
            Run(" "),
            Run("both", sprmWord(CISTD, 5)),
            Run(" "),
            Run("override", grpprl(sprmWord(CISTD, 4), sprm(CFBOLD, 0))),
            Run(" "),
            Run("reset", grpprl(sprmWord(CISTD, 4), sprm(CPLAIN, 1))),
            Run(" "),
            Run("nonesuch", sprmWord(CISTD, 99)),
            styles = styles,
        )
        assertEquals(
            listOf(TextSpan(6, 12, bold), TextSpan(13, 17, CharFormat(bold = true, italic = true))),
            spansOf(block),
        )
    }

    @Test
    fun aCharacterStyleLaysOverTheParagraphStyle() {
        val styles = listOf(
            DocRichFixtures.NORMAL,
            Style("Heading 1", sti = 1, base = 0, chpx = on(CFBOLD)),
            Style("Emphasis", sti = USER, sgc = 2, base = 0, chpx = on(CFITALIC)),
        )
        val block = only(Run("Title "), Run("emphasised", sprmWord(CISTD, 2)), istd = 1, styles = styles)
        assertEquals(
            listOf(TextSpan(0, 6, bold), TextSpan(6, 16, CharFormat(bold = true, italic = true))),
            spansOf(block),
        )
    }

    @Test
    fun spansStayWithTheirTextInHeadingsListsAndNumbers() {
        val numbered = DocRichFixtures.numbered(10)
        val instances = listOf(DocRichFixtures.ListInstance(10))
        val blocks = extract(
            DocRichFixtures.doc(
                listOf(
                    Para(listOf(Run("Intro", on(CFBOLD))), istd = 1, papx = grpprl(sprmWord(DocRichFixtures.PILFO, 1))),
                    Para(listOf(Run("an "), Run("item", on(CFITALIC))), papx = grpprl(sprmWord(DocRichFixtures.PILFO, 1))),
                ),
                lists = listOf(numbered),
                instances = instances,
            ),
        )
        assertEquals(
            listOf(
                // The heading's "1. " is in its text, so its span moves past it.
                OfficeBlock.Heading("1. Intro", 1, listOf(TextSpan(3, 8, bold))),
                OfficeBlock.ListItem("an item", "2.", 0, listOf(TextSpan(3, 7, italic))),
            ),
            blocks,
        )
    }

    @Test
    fun spansInATableCellAreTheCellsOwn() {
        val blocks = extract(
            DocRichFixtures.doc(
                listOf(
                    Para(listOf(Run("x "), Run("bold", on(CFBOLD))), mark = '\u0007'),
                    Para(listOf(Run("it", on(CFITALIC))), mark = '\u0007'),
                    Para("", mark = '\u0007'),
                ),
            ),
        )
        assertEquals(
            listOf(
                OfficeBlock.Table(
                    listOf(
                        listOf(
                            OfficeCell("x bold", column = 0, spans = listOf(TextSpan(2, 6, bold))),
                            OfficeCell("it", column = 1, spans = listOf(TextSpan(0, 2, italic))),
                        ),
                    ),
                ),
            ),
            blocks,
        )
    }

    @Test
    fun aRunCanSpanTwoPiecesAndEitherEncoding() {
        val paras = listOf(Para(listOf(Run("start "), Run("bold across the cut", on(CFBOLD)), Run(" end"))))
        val expected = OfficeBlock.Paragraph("start bold across the cut end", listOf(TextSpan(6, 25, bold)))
        assertEquals(listOf(expected), extract(DocRichFixtures.doc(paras)))
        assertEquals(listOf(expected), extract(DocRichFixtures.doc(paras, compressed = false)))
        assertEquals(listOf(expected), extract(DocRichFixtures.doc(paras, splitAt = 14)))
    }

    @Test
    fun characterFormattingIsReadEvenWithoutParagraphProperties() {
        val block = extract(
            DocRichFixtures.doc(listOf(Para(listOf(Run("plain "), Run("bold", on(CFBOLD))), istd = 1)), omitPapx = true),
        ).single()
        assertEquals(OfficeBlock.Paragraph("plain bold", listOf(TextSpan(6, 10, bold))), block)
    }

    @Test
    fun aCharacterPageClaimingTooManyRunsLeavesThePlainText() {
        val good = DocRichFixtures.doc(listOf(Para(listOf(Run("a "), Run("bold", on(CFBOLD))))))
        assertEquals(listOf(TextSpan(2, 6, bold)), spansOf(extract(good).single()))
        // The CHPX page is the last of WordDocument, so its `crun` is the stream's last byte.
        val broken = patchWord(good) { it[it.size - 1] = 0xFF.toByte() }
        assertEquals(OfficeBlock.Paragraph("a bold"), extract(broken).single())
    }

    @Test
    fun aCharacterPropertyPastTheEndOfItsPageIsIgnored() {
        val good = DocRichFixtures.doc(listOf(Para(listOf(Run("a "), Run("bold", on(CFBOLD))))))
        // The sprms sit at the top of the page, below the `crun` byte: make the length byte there huge.
        val broken = patchWord(good) { word ->
            val page = word.size - 512
            val at = (word[page + 4 * 4 + 1].toInt() and 0xFF)
            word[page + at * 2] = 0xF0.toByte()
            check(at > 0)
        }
        assertEquals(OfficeBlock.Paragraph("a bold"), extract(broken).single())
    }

    @Test
    fun aTruncatedFontTableLeavesNothingMonospaced() {
        val good = DocRichFixtures.doc(listOf(Para(listOf(Run("code", sprmWord(CRGFTC0, 1))))), fonts = listOf("Arial", "Courier New"))
        assertEquals(listOf(TextSpan(0, 4, CharFormat(mono = true))), spansOf(extract(good).single()))
        val broken = patchPairLength(good, pair = 15, lcb = 50)
        assertEquals(OfficeBlock.Paragraph("code"), extract(broken).single())
    }

    // --- helpers ---------------------------------------------------------------------------------

    /** Heading 1 whose style makes its text bold, in place of the plain one. */
    private val HEADING_BOLD = Style("Heading 1", sti = 1, base = 0, chpx = on(CFBOLD))

    private fun styled(heading: Style) = listOf(DocRichFixtures.NORMAL, heading)

    /** A 10-byte SHD operand: foreground and background as COLORREFs, then the pattern. */
    private fun shd(fore: Int, back: Int, pattern: Int): ByteArray =
        colorRef(fore) + colorRef(back) + byteArrayOf((pattern and 0xFF).toByte(), (pattern shr 8).toByte())

    private fun colorRef(rgb: Int) = byteArrayOf((rgb shr 16).toByte(), (rgb shr 8).toByte(), rgb.toByte(), 0)

    private fun patchWord(doc: ByteArray, edit: (ByteArray) -> Unit): ByteArray {
        val cfb = CompoundFile(DocFixtures.streamFactory(doc), doc.size.toLong())
        val word = cfb.read("WordDocument", 0, cfb.streamSize("WordDocument")!!.toInt())
        val table = cfb.read("0Table", 0, cfb.streamSize("0Table")!!.toInt())
        edit(word)
        return DocFixtures.compound(linkedMapOf("WordDocument" to word, "0Table" to table))
    }

    private fun patchPairLength(doc: ByteArray, pair: Int, lcb: Int): ByteArray = patchWord(doc) { word ->
        val at = 0x9A + pair * 8 + 4
        for (b in 0 until 4) word[at + b] = ((lcb shr (b * 8)) and 0xFF).toByte()
    }

    private companion object {
        const val USER = 0x0FFE
    }
}
