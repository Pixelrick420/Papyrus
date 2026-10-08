package com.papyrus.app.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException

/** Piece-table decoding, table/field text syntax, and every corruption the extractor must reject. */
class DocTextExtractorTest {

    private fun extract(bytes: ByteArray): List<OfficeBlock> =
        DocTextExtractor.extract(DocFixtures.streamFactory(bytes))

    private fun assertIoException(bytes: ByteArray): IOException =
        assertThrows(IOException::class.java) { extract(bytes) }

    private fun paragraphs(vararg texts: String) = texts.map { OfficeBlock.Paragraph(it) }

    // --- Piece decoding ---

    @Test
    fun `single compressed piece extracts its text`() {
        val bytes = DocFixtures.doc("Hello, world")

        assertEquals(paragraphs("Hello, world"), extract(bytes))
    }

    @Test
    fun `utf-16 piece keeps characters outside latin-1`() {
        val bytes = DocFixtures.doc(pieces = listOf(DocFixtures.Piece("Héllo \u2713 \uD835\uDC9C", compressed = false)))

        assertEquals(paragraphs("Héllo \u2713 \uD835\uDC9C"), extract(bytes))
    }

    @Test
    fun `mixed pieces concatenate in document order`() {
        val bytes = DocFixtures.doc(
            pieces = listOf(
                DocFixtures.Piece("Hello, "),
                DocFixtures.Piece("wörld", compressed = false),
                DocFixtures.Piece("!"),
            ),
        )

        assertEquals(paragraphs("Hello, wörld!"), extract(bytes))
    }

    @Test
    fun `ccpText clamps a piece mid-text`() {
        val bytes = DocFixtures.doc("Hello world", ccpText = 5)

        assertEquals(paragraphs("Hello"), extract(bytes))
    }

    @Test
    fun `ccpText stops before the piece past it`() {
        val bytes = DocFixtures.doc(
            pieces = listOf(DocFixtures.Piece("AAA"), DocFixtures.Piece("BBB")),
            ccpText = 3,
        )

        assertEquals(paragraphs("AAA"), extract(bytes))
    }

    @Test
    fun `zero ccpText yields no blocks`() {
        val bytes = DocFixtures.doc("ignored", ccpText = 0)

        assertEquals(emptyList<OfficeBlock>(), extract(bytes))
    }

    @Test
    fun `compressed special characters decode to their unicode mapping`() {
        val text = "\u201A\u2019\u201C\u201D\u2013\u2014\u2026\u2022\u00E9"
        val bytes = DocFixtures.doc(text)

        assertEquals(paragraphs(text), extract(bytes))
    }

    @Test
    fun `empty paragraphs are skipped but tabs survive`() {
        val bytes = DocFixtures.doc("a\r\r\rb\tc\r")

        assertEquals(paragraphs("a", "b\tc"), extract(bytes))
    }

    @Test
    fun `streams past the mini cutoff read through the regular fat path`() {
        val body = "x".repeat(5000) + " End"
        val bytes = DocFixtures.doc(body)

        assertEquals(paragraphs(body), extract(bytes))
    }

    // --- Tables and fields ---

    @Test
    fun `adjacent cell marks form one row`() {
        val bytes = DocFixtures.doc("a\u0007b\u0007\u0007")

        assertEquals(
            listOf(
                OfficeBlock.Table(
                    listOf(
                        listOf(OfficeCell("a", column = 0), OfficeCell("b", column = 1)),
                    ),
                ),
            ),
            extract(bytes),
        )
    }

    @Test
    fun `an empty cell mark splits rows`() {
        val bytes = DocFixtures.doc("a\u0007b\u0007\u0007c\u0007d\u0007\u0007")

        assertEquals(
            listOf(
                OfficeBlock.Table(
                    listOf(
                        listOf(OfficeCell("a", column = 0), OfficeCell("b", column = 1)),
                        listOf(OfficeCell("c", column = 0), OfficeCell("d", column = 1)),
                    ),
                ),
            ),
            extract(bytes),
        )
    }

    @Test
    fun `a paragraph after a table closes it first`() {
        val bytes = DocFixtures.doc("a\u0007b\u0007\u0007\rAfter")

        assertEquals(
            listOf(
                OfficeBlock.Table(
                    listOf(
                        listOf(OfficeCell("a", column = 0), OfficeCell("b", column = 1)),
                    ),
                ),
                OfficeBlock.Paragraph("After"),
            ),
            extract(bytes),
        )
    }

    @Test
    fun `a paragraph mark inside a row joins the cell with a newline`() {
        // Nested tables carry their depth in sprms the text stream never shows.
        val bytes = DocFixtures.doc("a\u0007x\rb\u0007\u0007")

        assertEquals(
            listOf(
                OfficeBlock.Table(
                    listOf(
                        listOf(OfficeCell("a", column = 0), OfficeCell("x\nb", column = 1)),
                    ),
                ),
            ),
            extract(bytes),
        )
    }

    @Test
    fun `field instruction text is dropped and its result kept`() {
        val bytes = DocFixtures.doc("\u0013HYPERLINK \"https://example.test\"\u0014Click here\u0015")

        assertEquals(paragraphs("Click here"), extract(bytes))
    }

    @Test
    fun `block count stops at the cap`() {
        val bytes = DocFixtures.doc("p\r".repeat(DocTextExtractor.MAX_BLOCKS + 1))

        assertEquals(DocTextExtractor.MAX_BLOCKS, extract(bytes).size)
    }

    // --- Container selection ---

    @Test
    fun `the flag-selected table stream is read`() {
        val bytes = DocFixtures.doc("via 1Table", flags = 0x0200, tableStream = "1Table")

        assertEquals(paragraphs("via 1Table"), extract(bytes))
    }

    @Test
    fun `the other table stream is tried when the flag names a missing one`() {
        val bytes = DocFixtures.doc("fallback", flags = 0x0200, tableStream = "0Table")

        assertEquals(paragraphs("fallback"), extract(bytes))
    }

    @Test
    fun `a prc grpprl before the piece table is skipped`() {
        val bytes = DocFixtures.doc("with prc", prcPrefix = true)

        assertEquals(paragraphs("with prc"), extract(bytes))
    }

    // --- Rejected input ---

    @Test
    fun `bytes that are not a compound file are rejected`() {
        assertIoException(ByteArray(600))
    }

    @Test
    fun `a truncated compound file is rejected`() {
        val bytes = DocFixtures.doc("Hello")

        assertIoException(bytes.copyOf(bytes.size / 2))
    }

    @Test
    fun `a fat chain cycle is rejected`() {
        val bytes = DocFixtures.doc("Hello")
        val fat = DocFixtures.firstFatSectorOffset(bytes)
        repeat(4) { bytes[fat + it] = 0 } // sector 0 points at itself

        assertIoException(bytes)
    }

    @Test
    fun `a missing word document stream is rejected`() {
        assertIoException(DocFixtures.doc(omitWordDocument = true))
    }

    @Test
    fun `a missing table stream is rejected`() {
        assertIoException(DocFixtures.doc(omitTable = true))
    }

    @Test
    fun `an encrypted document is rejected`() {
        assertIoException(DocFixtures.doc("secret", flags = 0x0100))
        assertIoException(DocFixtures.doc("secret", flags = 0x8000))
    }

    @Test
    fun `word 6 fib revisions are rejected`() {
        assertIoException(DocFixtures.doc("old", nFib = 0x00BF))
    }

    @Test
    fun `a wrong wident is rejected`() {
        assertIoException(DocFixtures.doc("x", wIdent = 0x1234))
    }

    @Test
    fun `a negative ccpText is rejected`() {
        assertIoException(DocFixtures.doc("x", ccpText = -1))
    }

    @Test
    fun `a zero lcbClx is rejected`() {
        assertIoException(DocFixtures.doc("x", lcbClx = 0))
    }

    @Test
    fun `a garbage clx is rejected`() {
        assertIoException(DocFixtures.doc(clxOverride = byteArrayOf(0x77, 0x77, 0x77, 0x77, 0x77)))
    }

    @Test
    fun `a clx shorter than its declared length is rejected`() {
        assertIoException(DocFixtures.doc("Hello", lcbClx = 3))
    }

    @Test
    fun `a piece length that is not a multiple of twelve is rejected`() {
        val clx = byteArrayOf(0x02) + DocFixtures.u32Bytes(15) + ByteArray(15)
        assertIoException(DocFixtures.doc(clxOverride = clx))
    }

    @Test
    fun `an fc past the table stream is rejected`() {
        assertIoException(DocFixtures.doc("Hello", fcClx = 10_000))
    }
}
