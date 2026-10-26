package com.papyrus.app.viewer

import com.papyrus.app.viewer.EpubFixtures.chapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Right-to-left books: the book's own direction from the spine, and each block's from the markup around it. */
class EpubRtlTest {

    private fun read(bytes: ByteArray) =
        EpubExtractor(mediaDir = null, newParserFactory = OfficeFixtures::parser).extract(OfficeFixtures.streamFactory(bytes))

    private fun withProgression(value: String?): ByteArray {
        val progression = if (value == null) "" else """ page-progression-direction="$value""""
        val opf = """<?xml version="1.0" encoding="utf-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>Test</dc:title></metadata>
  <manifest><item id="ch0" href="c.xhtml" media-type="application/xhtml+xml"/></manifest>
  <spine$progression><itemref idref="ch0"/></spine>
</package>"""
        return EpubFixtures.epub(listOf(chapter("<p>text</p>")), opfOverride = opf)
    }

    private fun directions(body: String) =
        read(EpubFixtures.epub(listOf(chapter(body)))).blocks.map { it.direction }

    @Test
    fun `the spine says which way the book is read`() {
        assertEquals(BlockDirection.RTL, read(withProgression("rtl")).direction)
        assertEquals(BlockDirection.RTL, read(withProgression(" RTL ")).direction)
        assertEquals(BlockDirection.LTR, read(withProgression("ltr")).direction)
        assertEquals(BlockDirection.LTR, read(withProgression(null)).direction)
        assertEquals(BlockDirection.LTR, read(withProgression("auto")).direction)
        assertEquals(BlockDirection.LTR, read(withProgression("default")).direction)
    }

    @Test
    fun `a block that says nothing has no direction of its own`() {
        assertEquals(listOf(null, null, null), directions("<h1>a</h1><p>b</p><ul><li>c</li></ul>"))
    }

    @Test
    fun `dir on a div covers the blocks inside it and only them`() {
        val body = """<p>before</p><div dir="rtl"><p>one</p><h2>two</h2><ul><li>three</li></ul>loose</div><p>after</p>"""
        assertEquals(
            listOf(null, BlockDirection.RTL, BlockDirection.RTL, BlockDirection.RTL, BlockDirection.RTL, null),
            directions(body),
        )
    }

    @Test
    fun `dir on a paragraph is that paragraph's`() {
        assertEquals(listOf(BlockDirection.RTL, null, BlockDirection.LTR), directions("""<p dir="rtl">a</p><p>b</p><p dir="ltr">c</p>"""))
    }

    @Test
    fun `a nested ltr is restored to the outer rtl when it ends`() {
        val body = """<div dir="rtl"><p>a</p><div dir="ltr"><p>b</p></div><p>c</p></div>"""
        assertEquals(listOf(BlockDirection.RTL, BlockDirection.LTR, BlockDirection.RTL), directions(body))
    }

    @Test
    fun `auto leaves the inherited direction alone`() {
        val body = """<div dir="rtl"><p dir="auto">a</p></div><p dir="auto">b</p>"""
        assertEquals(listOf(BlockDirection.RTL, null), directions(body))
    }

    @Test
    fun `dir on html reaches every paragraph`() {
        val xhtml = EpubFixtures.xhtml("<p>a</p><p>b</p>").replace("<html ", """<html dir="rtl" """)
        val bytes = EpubFixtures.epub(listOf(EpubFixtures.Chapter("c.xhtml", xhtml)))
        assertEquals(listOf(BlockDirection.RTL, BlockDirection.RTL), read(bytes).blocks.map { it.direction })
    }

    @Test
    fun `dir on body reaches every paragraph`() {
        val xhtml = EpubFixtures.xhtml("<p>a</p>").replace("<body>", """<body dir="rtl">""")
        val bytes = EpubFixtures.epub(listOf(EpubFixtures.Chapter("c.xhtml", xhtml)))
        assertEquals(listOf(BlockDirection.RTL), read(bytes).blocks.map { it.direction })
    }

    @Test
    fun `a direction does not leak into the next chapter`() {
        val chapters = listOf(
            chapter("""<div dir="rtl"><p>one</p></div>""", "a.xhtml"),
            chapter("<p>two</p>", "b.xhtml"),
        )
        assertEquals(listOf(BlockDirection.RTL, null), read(EpubFixtures.epub(chapters)).blocks.map { it.direction })
    }

    @Test
    fun `an inline element with dir is a paragraph's direction only while it is the open one`() {
        // The paragraph is emitted when it ends, by which time the inline element is closed.
        assertEquals(listOf<BlockDirection?>(null), directions("""<p>a <bdo dir="rtl">b</bdo> c</p>"""))
    }

    @Test
    fun `a table has no direction and its words are not affected`() {
        val book = read(EpubFixtures.epub(listOf(chapter("""<div dir="rtl"><table><tr><td>x</td></tr></table><p>y</p></div>"""))))
        assertNull(book.blocks[0].direction)
        assertEquals(BlockDirection.RTL, book.blocks[1].direction)
    }

    @Test
    fun `the reading order is the same with and without rtl`() {
        fun texts(bytes: ByteArray) = read(bytes).blocks.map { (it as OfficeBlock.Paragraph).text }
        val chapters = listOf(chapter("<p>one</p>", "a.xhtml"), chapter("<p>two</p>", "b.xhtml"), chapter("<p>three</p>", "c.xhtml"))
        val plain = EpubFixtures.epub(chapters, spineOrder = listOf(2, 0, 1))
        val rtl = EpubFixtures.epub(
            chapters,
            spineOrder = listOf(2, 0, 1),
            opfOverride = String(
                java.util.zip.ZipInputStream(plain.inputStream()).use { zip ->
                    generateSequence { zip.nextEntry }.first { it.name == "OEBPS/content.opf" }.let { zip.readBytes() }
                },
            ).replace("<spine", """<spine page-progression-direction="rtl""""),
        )
        assertEquals(BlockDirection.RTL, read(rtl).direction)
        assertEquals(listOf("three", "one", "two"), texts(plain))
        assertEquals(texts(plain), texts(rtl))
    }

    @Test
    fun `one rtl paragraph and one ltr in the same chapter keep their own`() {
        val body = "<p dir=\"rtl\">\u05E9\u05DC\u05D5\u05DD</p><p dir=\"ltr\">hello</p>"
        val blocks = read(EpubFixtures.epub(listOf(chapter(body)))).blocks.map { it as OfficeBlock.Paragraph }
        assertEquals(listOf(BlockDirection.RTL, BlockDirection.LTR), blocks.map { it.direction })
        assertEquals(listOf("\u05E9\u05DC\u05D5\u05DD", "hello"), blocks.map { it.text })
    }
}
