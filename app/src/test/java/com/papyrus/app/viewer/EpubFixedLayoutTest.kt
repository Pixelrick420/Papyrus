package com.papyrus.app.viewer

import com.papyrus.app.viewer.EpubFixtures.chapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Fixed-layout books: which spine items are pages, where each starts, and the shape of a page-sized picture. */
class EpubFixedLayoutTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun read(bytes: ByteArray, withMedia: Boolean = false): EpubBook {
        val dir = if (withMedia) temp.newFolder("media") else null
        return EpubExtractor(
            mediaDir = dir,
            newParserFactory = OfficeFixtures::parser,
            rasteriser = EpubFixtures.FakeRasteriser(),
        ).extract(OfficeFixtures.streamFactory(bytes))
    }

    /** A book of [count] chapters c0.xhtml...; [meta] goes in the metadata, [properties] by chapter number on its itemref. */
    private fun book(
        chapters: List<EpubFixtures.Chapter>,
        meta: String = "",
        properties: Map<Int, String> = emptyMap(),
        media: Map<String, ByteArray> = emptyMap(),
    ): ByteArray {
        val manifest = chapters.indices.joinToString("") { """<item id="ch$it" href="${chapters[it].href}" media-type="application/xhtml+xml"/>""" }
        val spine = chapters.indices.joinToString("") { """<itemref idref="ch$it"${properties[it]?.let { p -> " properties=\"$p\"" }.orEmpty()}/>""" }
        val opf = """<?xml version="1.0" encoding="utf-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>T</dc:title>$meta</metadata>
  <manifest>$manifest</manifest>
  <spine>$spine</spine>
</package>"""
        return EpubFixtures.epub(chapters, media = media, opfOverride = opf)
    }

    private val prePaginated = """<meta property="rendition:layout">pre-paginated</meta>"""

    private fun pages(n: Int) = (0 until n).map { chapter("<p>page $it</p>", "p$it.xhtml") }

    @Test
    fun `a book level meta makes every item a page`() {
        val book = read(book(pages(3), meta = prePaginated))
        assertTrue(book.fixedLayout)
        assertEquals(setOf(0, 1, 2), book.pageStarts)
    }

    @Test
    fun `an itemref alone makes its item a page in an otherwise reflowable book`() {
        val book = read(book(pages(3), properties = mapOf(1 to "rendition:layout-pre-paginated")))
        assertTrue(book.fixedLayout)
        assertEquals(setOf(1), book.pageStarts)
    }

    @Test
    fun `an item that says reflowable keeps its own page break out of a fixed book`() {
        val book = read(book(pages(3), meta = prePaginated, properties = mapOf(1 to "rendition:layout-reflowable")))
        assertTrue(book.fixedLayout)
        assertEquals(setOf(0, 2), book.pageStarts)
    }

    @Test
    fun `a reflowable book has no page starts`() {
        for (meta in listOf("", """<meta property="rendition:layout">reflowable</meta>""", """<meta property="rendition:layout"/>""")) {
            val book = read(book(pages(3), meta = meta))
            assertFalse(book.fixedLayout)
            assertTrue(book.pageStarts.isEmpty())
        }
    }

    @Test
    fun `an auxiliary item alone does not make the book fixed`() {
        val chapters = pages(2)
        val opf = """<?xml version="1.0" encoding="utf-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>T</dc:title></metadata>
  <manifest><item id="ch0" href="p0.xhtml" media-type="application/xhtml+xml"/><item id="ch1" href="p1.xhtml" media-type="application/xhtml+xml"/></manifest>
  <spine><itemref idref="ch0"/><itemref idref="ch1" linear="no" properties="rendition:layout-pre-paginated"/></spine>
</package>"""
        assertFalse(read(EpubFixtures.epub(chapters, opfOverride = opf)).fixedLayout)
    }

    @Test
    fun `page starts are the first block of each page and move with the images before them`() {
        val chapters = listOf(
            chapter("""<p>a</p><img src="a.png"/><p>b</p>""", "p0.xhtml"),
            chapter("<p>c</p><p>d</p>", "p1.xhtml"),
            chapter("<p>e</p>", "p2.xhtml"),
        )
        val book = read(book(chapters, meta = prePaginated, media = mapOf("a.png" to OfficeFixtures.PNG_1X1)), withMedia = true)
        assertEquals(setOf(0, 3, 5), book.pageStarts)
        assertEquals("c", (book.blocks[3] as OfficeBlock.Paragraph).text)
        assertEquals("e", (book.blocks[5] as OfficeBlock.Paragraph).text)
    }

    @Test
    fun `a page start agrees with where the chapter's anchor is`() {
        val book = read(book(pages(3), meta = prePaginated))
        assertEquals(book.pageStarts, listOf("p0", "p1", "p2").map { book.anchors.getValue(BookLink("OEBPS/$it.xhtml")) }.toSet())
    }

    private fun scan(width: Int, height: Int, head: String = """<meta name="viewport" content="width=$width, height=$height"/>""") =
        EpubFixtures.Chapter("s.xhtml", EpubFixtures.xhtml("""<img src="s.png"/>""", head = "<title>t</title>$head"))

    private fun imageBlock(book: EpubBook) = book.blocks.single() as OfficeBlock.Image

    private fun scanBook(chapter: EpubFixtures.Chapter, meta: String = prePaginated) =
        read(book(listOf(chapter), meta = meta, media = mapOf("s.png" to OfficeFixtures.PNG_1X1)), withMedia = true)

    @Test
    fun `a one picture page takes its shape from the viewport`() {
        assertEquals(0.75f, imageBlock(scanBook(scan(1200, 1600))).aspectRatio!!, 0.0001f)
        assertEquals(2f, imageBlock(scanBook(scan(2000, 1000))).aspectRatio!!, 0.0001f)
    }

    @Test
    fun `the shape is clamped`() {
        assertEquals(0.1f, imageBlock(scanBook(scan(1, 1000))).aspectRatio!!, 0.0001f)
        assertEquals(10f, imageBlock(scanBook(scan(1000, 1))).aspectRatio!!, 0.0001f)
    }

    @Test
    fun `a page of words and a picture or of two pictures has no shape`() {
        val mixed = EpubFixtures.Chapter("s.xhtml", EpubFixtures.xhtml("""<p>words</p><img src="s.png"/>""", head = """<meta name="viewport" content="width=10, height=20"/>"""))
        assertNull((scanBook(mixed).blocks.filterIsInstance<OfficeBlock.Image>().single()).aspectRatio)
        val two = EpubFixtures.Chapter("s.xhtml", EpubFixtures.xhtml("""<img src="s.png"/><img src="s.png"/>""", head = """<meta name="viewport" content="width=10, height=20"/>"""))
        assertTrue(scanBook(two).blocks.filterIsInstance<OfficeBlock.Image>().all { it.aspectRatio == null })
    }

    @Test
    fun `a reflowable book's picture has no shape even with a viewport`() {
        assertNull(imageBlock(scanBook(scan(1200, 1600), meta = "")).aspectRatio)
    }

    @Test
    fun `a missing or broken viewport gives no shape and no error`() {
        for (head in listOf("", """<meta name="viewport" content="width=abc, height=def"/>""", """<meta name="viewport"/>""", """<meta name="viewport" content="width=0, height=10"/>""")) {
            assertNull(imageBlock(scanBook(scan(1, 1, head))).aspectRatio)
        }
    }

    @Test
    fun `pageAspect reads the forms a book writes`() {
        fun aspect(head: String) = pageAspect("<html><head>$head</head><body/></html>".toByteArray())
        assertEquals(0.75f, aspect("""<meta name="viewport" content="width=1200, height=1600"/>""")!!, 0.0001f)
        // Attribute order, single quotes, case, spacing.
        assertEquals(0.75f, aspect("""<META content='width = 600 ,height=800' NAME='Viewport'>""")!!, 0.0001f)
        assertEquals(0.75f, aspect("""<meta content="height=1600;width=1200" name="viewport"/>""")!!, 0.0001f)
        assertEquals(0.75f, aspect("""<meta name="viewport" content="1200x1600"/>""")!!, 0.0001f)
        assertEquals(1.5f, aspect("""<meta name="viewport" content="width=300.5, height=200.25"/>""")!!, 0.01f)
        assertNull(aspect("""<meta name="viewport" content="width=1200"/>"""))
        assertNull(aspect("""<meta name="viewport" content="width=device-width, initial-scale=1"/>"""))
        assertNull(aspect("""<meta name="other" content="width=1, height=2"/>"""))
        assertNull(aspect(""))
    }

    @Test
    fun `pageAspect never throws on odd input`() {
        assertNull(pageAspect(ByteArray(0)))
        assertNull(pageAspect("<meta name=\"viewport\" content=\"width=1, height=".toByteArray()))
        assertNull(pageAspect(ByteArray(200_000) { 0x3C }))
        assertNull(pageAspect(byteArrayOf(-1, -2, 0, 60, 109)))
    }
}
