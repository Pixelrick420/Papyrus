package com.papyrus.app.viewer

import com.papyrus.app.viewer.DocRichFixtures.DEFAULT_STYLES
import com.papyrus.app.viewer.DocRichFixtures.NORMAL
import com.papyrus.app.viewer.DocRichFixtures.Para
import com.papyrus.app.viewer.DocRichFixtures.Style
import org.junit.Assert.assertEquals
import org.junit.Test

class DocHeadingTest {

    private fun extract(bytes: ByteArray) = DocTextExtractor.extract(DocFixtures.streamFactory(bytes))

    @Test
    fun builtInHeadingStylesMapToLevels() {
        val blocks = extract(
            DocRichFixtures.doc(
                listOf(Para("Title", istd = 1), Para("Body"), Para("Section", istd = 2), Para("Sub", istd = 3)),
            ),
        )
        assertEquals(
            listOf(
                OfficeBlock.Heading("Title", 1),
                OfficeBlock.Paragraph("Body"),
                OfficeBlock.Heading("Section", 2),
                OfficeBlock.Heading("Sub", 3),
            ),
            blocks,
        )
    }

    @Test
    fun builtInHeadingIsRecognisedBySti_notName() {
        // A document written in another language, or one that did not store the built-in names.
        val styles = listOf(NORMAL, Style("Überschrift 1", sti = 1, base = 0), Style("", sti = 2, base = 0))
        val blocks = extract(DocRichFixtures.doc(listOf(Para("Eins", istd = 1), Para("Zwei", istd = 2)), styles))
        assertEquals(listOf(OfficeBlock.Heading("Eins", 1), OfficeBlock.Heading("Zwei", 2)), blocks)
    }

    @Test
    fun userStyleNamedHeadingIsAHeading() {
        val styles = listOf(NORMAL, Style("heading 3", sti = USER_STYLE, base = 0), Style("Heading 10", sti = USER_STYLE, base = 0))
        val blocks = extract(DocRichFixtures.doc(listOf(Para("Three", istd = 1), Para("Ten", istd = 2)), styles))
        assertEquals(listOf(OfficeBlock.Heading("Three", 3), OfficeBlock.Paragraph("Ten")), blocks)
    }

    @Test
    fun styleBasedOnAHeadingIsAHeadingToo() {
        val styles = DEFAULT_STYLES + Style("Chapter", sti = USER_STYLE, base = 2) + Style("Chapter Sub", sti = USER_STYLE, base = 4)
        val blocks = extract(DocRichFixtures.doc(listOf(Para("Ch", istd = 4), Para("Deeper", istd = 5)), styles))
        assertEquals(listOf(OfficeBlock.Heading("Ch", 2), OfficeBlock.Heading("Deeper", 2)), blocks)
    }

    @Test
    fun headingLevelsPastSixAreCappedAtSix() {
        val styles = listOf(NORMAL) + (1..9).map { Style("Heading $it", sti = it, base = 0) }
        val blocks = extract(DocRichFixtures.doc(listOf(Para("Seven", istd = 7), Para("Nine", istd = 9)), styles))
        assertEquals(listOf(OfficeBlock.Heading("Seven", 6), OfficeBlock.Heading("Nine", 6)), blocks)
    }

    @Test
    fun aParagraphWithAnUnknownStyleIsBodyText() {
        val blocks = extract(DocRichFixtures.doc(listOf(Para("Lost", istd = 99), Para("Gap", istd = 2)), listOf(NORMAL, null, null)))
        assertEquals(listOf(OfficeBlock.Paragraph("Lost"), OfficeBlock.Paragraph("Gap")), blocks)
    }

    @Test
    fun alignmentComesFromTheParagraphOrItsStyle() {
        val styles = DEFAULT_STYLES + Style("Centered", sti = USER_STYLE, base = 0, papx = DocRichFixtures.sprm(DocRichFixtures.PJC, 1))
        val blocks = extract(
            DocRichFixtures.doc(
                listOf(
                    Para("From style", istd = 4),
                    Para("Direct right", papx = DocRichFixtures.sprm(DocRichFixtures.PJC, 2)),
                    Para("Overridden", istd = 4, papx = DocRichFixtures.sprm(DocRichFixtures.PJC, 0)),
                    Para("Justified", papx = DocRichFixtures.sprm(DocRichFixtures.PJC, 3)),
                    Para("Heading", istd = 1, papx = DocRichFixtures.sprm(DocRichFixtures.PJC, 1)),
                ),
                styles,
            ),
        )
        assertEquals(
            listOf(
                OfficeBlock.Paragraph("From style", align = BlockAlign.CENTER),
                OfficeBlock.Paragraph("Direct right", align = BlockAlign.END),
                OfficeBlock.Paragraph("Overridden"),
                OfficeBlock.Paragraph("Justified"),
                OfficeBlock.Heading("Heading", 1, align = BlockAlign.CENTER),
            ),
            blocks,
        )
    }

    @Test
    fun leftIndentBecomesAnIndentStep() {
        val blocks = extract(
            DocRichFixtures.doc(
                listOf(
                    Para("Flush"),
                    Para("One step", papx = DocRichFixtures.sprmWord(DocRichFixtures.PDXALEFT, 720)),
                    Para("Hanging", papx = DocRichFixtures.sprmWord(DocRichFixtures.PDXALEFT, 0xFF38)),
                ),
            ),
        )
        assertEquals(
            listOf(OfficeBlock.Paragraph("Flush"), OfficeBlock.Paragraph("One step", indent = 2), OfficeBlock.Paragraph("Hanging")),
            blocks,
        )
    }

    @Test
    fun theSameDocumentReadsTheSameCompressedOrUtf16() {
        val paras = listOf(Para("Alpha", istd = 1), Para("Beta"), Para("Gamma", istd = 2))
        val expected = listOf(OfficeBlock.Heading("Alpha", 1), OfficeBlock.Paragraph("Beta"), OfficeBlock.Heading("Gamma", 2))
        assertEquals(expected, extract(DocRichFixtures.doc(paras, compressed = true)))
        assertEquals(expected, extract(DocRichFixtures.doc(paras, compressed = false)))
    }

    @Test
    fun aParagraphCanStraddleTwoPieces() {
        val paras = listOf(Para("Head", istd = 1), Para("Straddling paragraph"), Para("Tail", istd = 2))
        // The cut falls inside "Straddling paragraph"; the second piece is UTF-16 text.
        val blocks = extract(DocRichFixtures.doc(paras, splitAt = 15))
        assertEquals(
            listOf(OfficeBlock.Heading("Head", 1), OfficeBlock.Paragraph("Straddling paragraph"), OfficeBlock.Heading("Tail", 2)),
            blocks,
        )
    }

    @Test
    fun manyParagraphsSpanSeveralPapxPages() {
        val paras = (0 until 200).map { Para("Line $it", istd = if (it % 10 == 0) 1 else 0) }
        val blocks = extract(DocRichFixtures.doc(paras))
        assertEquals(200, blocks.size)
        blocks.forEachIndexed { i, block ->
            val expected = if (i % 10 == 0) OfficeBlock.Heading("Line $i", 1) else OfficeBlock.Paragraph("Line $i")
            assertEquals(expected, block)
        }
    }

    @Test
    fun aDocumentWithoutAStyleSheetStillReadsItsParagraphs() {
        val blocks = extract(DocRichFixtures.doc(listOf(Para("Title", istd = 1), Para("Body")), styles = emptyList()))
        assertEquals(listOf(OfficeBlock.Paragraph("Title"), OfficeBlock.Paragraph("Body")), blocks)
    }

    @Test
    fun aCycleInTheBasedOnChainDoesNotHang() {
        val styles = listOf(NORMAL, Style("A", sti = USER_STYLE, base = 2), Style("B", sti = USER_STYLE, base = 1))
        val blocks = extract(DocRichFixtures.doc(listOf(Para("Looped", istd = 1)), styles))
        assertEquals(listOf(OfficeBlock.Paragraph("Looped")), blocks)
    }

    @Test
    fun anUnreadableStyleSheetCostsTheHeadingsAndNothingElse() {
        val good = DocRichFixtures.doc(listOf(Para("Title", istd = 1), Para("Next", istd = 2)))
        assertEquals(listOf(OfficeBlock.Heading("Title", 1), OfficeBlock.Heading("Next", 2)), extract(good))
        // An Stshf shorter than its own header.
        val broken = withPairLength(good, pair = 1, lcb = 3)
        assertEquals(listOf(OfficeBlock.Paragraph("Title"), OfficeBlock.Paragraph("Next")), extract(broken))
    }

    @Test
    fun aStyleSheetCutMidStyleKeepsTheStylesBeforeTheCut() {
        val good = DocRichFixtures.doc(listOf(Para("One", istd = 1), Para("Two", istd = 2), Para("Three", istd = 3)))
        val table = readStream(good, "0Table")
        val word = readStream(good, "WordDocument")
        val stsh = u32(word, 0x9A + 8).toInt()
        // Stshi is 2 + 18 bytes; then cbStd and an STD for Normal, Heading 1 and Heading 2.
        var cut = stsh + 20
        repeat(2) { cut += 2 + u16(table, cut) }
        cut += 2 + u16(table, cut) / 2
        val broken = withPairLength(good, pair = 1, lcb = cut - stsh)
        assertEquals(
            listOf(OfficeBlock.Heading("One", 1), OfficeBlock.Paragraph("Two"), OfficeBlock.Paragraph("Three")),
            extract(broken),
        )
    }

    @Test
    fun pageNumbersPastTheStreamAreIgnored() {
        val good = DocRichFixtures.doc(listOf(Para("Title", istd = 1), Para("Body")))
        assertEquals(listOf(OfficeBlock.Heading("Title", 1), OfficeBlock.Paragraph("Body")), extract(good))
        // Repoint the only page of the PlcBtePapx far beyond WordDocument.
        val broken = patchTable(good) { table, fc -> table[fc + 8] = 0x7F }
        assertEquals(listOf(OfficeBlock.Paragraph("Title"), OfficeBlock.Paragraph("Body")), extract(broken))
    }

    @Test
    fun aPapxPageClaimingTooManyRunsIsIgnored() {
        val good = DocRichFixtures.doc(listOf(Para("Title", istd = 1), Para("Body")))
        assertEquals(listOf(OfficeBlock.Heading("Title", 1), OfficeBlock.Paragraph("Body")), extract(good))
        // The page is the last 512 bytes of WordDocument; its `crun` byte is the last of them.
        val broken = patchWord(good) { word -> word[word.size - 1] = 0xFF.toByte() }
        assertEquals(listOf(OfficeBlock.Paragraph("Title"), OfficeBlock.Paragraph("Body")), extract(broken))
    }

    @Test
    fun runBoundariesThatGoBackwardsAreSkipped() {
        val good = DocRichFixtures.doc(listOf(Para("Title", istd = 1), Para("Body")))
        // Swap the page's first two boundaries so the first run ends before it starts.
        val broken = patchWord(good) { word ->
            val page = word.size - 512
            val first = word.copyOfRange(page, page + 4)
            word.copyOfRange(page + 4, page + 8).copyInto(word, page)
            first.copyInto(word, page + 4)
        }
        assertEquals(2, extract(broken).size)
    }

    // The helpers below rewrite one structure of an otherwise valid document.

    private fun withPairLength(doc: ByteArray, pair: Int, lcb: Int): ByteArray =
        patchWord(doc) { word ->
            val at = 0x9A + pair * 8 + 4
            for (b in 0 until 4) word[at + b] = ((lcb shr (b * 8)) and 0xFF).toByte()
        }

    private fun patchTable(doc: ByteArray, edit: (ByteArray, Int) -> Unit): ByteArray =
        patchStream(doc, "0Table") { table ->
            val word = readStream(doc, "WordDocument")
            val fc = u32(word, 0x9A + 13 * 8).toInt()
            edit(table, fc)
        }

    private fun patchWord(doc: ByteArray, edit: (ByteArray) -> Unit): ByteArray = patchStream(doc, "WordDocument", edit)

    /** Re-wraps [doc] with one stream changed: the compound-file layer is not what these tests are about. */
    private fun patchStream(doc: ByteArray, name: String, edit: (ByteArray) -> Unit): ByteArray {
        val streams = LinkedHashMap<String, ByteArray>()
        for (stream in listOf("WordDocument", "0Table")) streams[stream] = readStream(doc, stream)
        edit(streams.getValue(name))
        return DocFixtures.compound(streams)
    }

    private fun readStream(doc: ByteArray, name: String): ByteArray {
        val cfb = CompoundFile(DocFixtures.streamFactory(doc), doc.size.toLong())
        return cfb.read(name, 0, cfb.streamSize(name)!!.toInt())
    }

    private companion object {
        /** `sti` of a style the user made. */
        const val USER_STYLE = 0x0FFE
    }
}
