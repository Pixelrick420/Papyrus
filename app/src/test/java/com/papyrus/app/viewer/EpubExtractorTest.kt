package com.papyrus.app.viewer

import com.papyrus.app.viewer.EpubFixtures.Chapter
import com.papyrus.app.viewer.EpubFixtures.chapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class EpubExtractorTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun extractor(mediaDir: File? = null, limits: EpubLimits = EpubLimits()) =
        EpubExtractor(mediaDir = mediaDir, newParserFactory = OfficeFixtures::parser, limits = limits)

    private fun read(bytes: ByteArray, mediaDir: File? = null, limits: EpubLimits = EpubLimits()) =
        extractor(mediaDir, limits).extract(OfficeFixtures.streamFactory(bytes))

    private fun texts(book: EpubBook) = book.blocks.map {
        when (it) {
            is OfficeBlock.Heading -> it.text
            is OfficeBlock.Paragraph -> it.text
            is OfficeBlock.ListItem -> it.text
            else -> it.toString()
        }
    }

    // --- reading order ---------------------------------------------------------------------------

    @Test
    fun `the spine decides the order, not the manifest or the archive`() {
        val bytes = EpubFixtures.epub(
            chapters = listOf(
                chapter("<p>alpha</p>", "a.xhtml"),
                chapter("<p>bravo</p>", "b.xhtml"),
                chapter("<p>charlie</p>", "c.xhtml"),
            ),
            spineOrder = listOf(2, 0, 1),
        )
        val book = read(bytes)
        assertEquals(listOf("charlie", "alpha", "bravo"), texts(book))
        assertFalse(book.incomplete)
    }

    @Test
    fun `auxiliary content is kept after the linear chapters`() {
        val bytes = EpubFixtures.epub(
            chapters = listOf(
                Chapter("notes.xhtml", EpubFixtures.xhtml("<p>notes</p>"), linear = false),
                chapter("<p>one</p>", "one.xhtml"),
                chapter("<p>two</p>", "two.xhtml"),
            ),
        )
        // The footnote page is first in the spine but not part of the main flow; its words are not lost either.
        assertEquals(listOf("one", "two", "notes"), texts(read(bytes)))
    }

    @Test
    fun `the mimetype entry may be anywhere`() {
        val bytes = EpubFixtures.epub(listOf(chapter("<p>hello</p>")), mimetypeLast = true)
        assertEquals(listOf("hello"), texts(read(bytes)))
    }

    @Test
    fun `hrefs are decoded relative to the package and folded`() {
        val bytes = EpubFixtures.epub(
            chapters = listOf(Chapter("text/chapter%201.xhtml", EpubFixtures.xhtml("<p>spaced</p>"))),
        ).let { original ->
            // The archive holds the decoded name, as real ones do.
            val entries = LinkedHashMap<String, ByteArray>()
            java.util.zip.ZipInputStream(original.inputStream()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val name = if (entry.name == "OEBPS/text/chapter%201.xhtml") "OEBPS/text/chapter 1.xhtml" else entry.name
                    entries[name] = zip.readBytes()
                }
            }
            OfficeFixtures.zipOf(entries)
        }
        assertEquals(listOf("spaced"), texts(read(bytes)))
    }

    @Test
    fun `a chapter found only by case is still found`() {
        val bytes = EpubFixtures.epub(listOf(Chapter("Text/One.XHTML", EpubFixtures.xhtml("<p>cased</p>")))).let { original ->
            val entries = LinkedHashMap<String, ByteArray>()
            java.util.zip.ZipInputStream(original.inputStream()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    entries[if (entry.name.endsWith("One.XHTML")) "OEBPS/text/one.xhtml" else entry.name] = zip.readBytes()
                }
            }
            OfficeFixtures.zipOf(entries)
        }
        assertEquals(listOf("cased"), texts(read(bytes)))
    }

    // --- markup to blocks ------------------------------------------------------------------------

    @Test
    fun `headings paragraphs and whitespace`() {
        val body = """
            <h1>Title</h1>
            <p>First   paragraph
               over lines.</p>
            <div>A div<br/>with a break</div>
            <h3>Deep</h3>
        """.trimIndent()
        val book = read(EpubFixtures.epub(listOf(chapter(body))))
        assertEquals(
            listOf(
                OfficeBlock.Heading("Title", 1),
                OfficeBlock.Paragraph("First paragraph over lines."),
                OfficeBlock.Paragraph("A div\nwith a break"),
                OfficeBlock.Heading("Deep", 3),
            ),
            book.blocks,
        )
    }

    @Test
    fun `inline formatting becomes spans`() {
        val body = """<p>plain <b>bold <i>both</i></b> <u>under</u> <s>struck</s> x<sup>2</sup> H<sub>2</sub>O <code>mono</code> <mark>hi</mark></p>"""
        val block = read(EpubFixtures.epub(listOf(chapter(body)))).blocks.single() as OfficeBlock.Paragraph
        assertEquals("plain bold both under struck x2 H2O mono hi", block.text)
        fun format(word: String): CharFormat? {
            val at = block.text.indexOf(word)
            return block.spans.firstOrNull { at >= it.start && at < it.end }?.format
        }
        assertEquals(CharFormat(bold = true), format("bold "))
        assertEquals(CharFormat(bold = true, italic = true), format("both"))
        assertEquals(CharFormat(underline = true), format("under"))
        assertEquals(CharFormat(strike = true), format("struck"))
        assertEquals(CharFormat(script = ScriptShift.SUPER), format("2 H"))
        assertEquals(CharFormat(script = ScriptShift.SUB), format("2O"))
        assertEquals(CharFormat(mono = true), format("mono"))
        assertNotNull(format("hi")!!.background)
        assertNull(format("plain"))
    }

    @Test
    fun `only safe links survive and internal ones stay plain`() {
        val body = """<p><a href="https://example.org/x">web</a> <a href="javascript:alert(1)">js</a> <a href="chapter2.xhtml#top">inside</a> <a href="mailto:a@b.c">mail</a></p>"""
        val block = read(EpubFixtures.epub(listOf(chapter(body)))).blocks.single() as OfficeBlock.Paragraph
        val links = block.spans.mapNotNull { it.format.link }
        assertEquals(listOf("https://example.org/x", "mailto:a@b.c"), links)
    }

    @Test
    fun `preformatted text keeps its lines and is monospace`() {
        val body = "<pre>line one\n  indented\nline three</pre>"
        val block = read(EpubFixtures.epub(listOf(chapter(body)))).blocks.single() as OfficeBlock.Paragraph
        assertEquals("line one\n  indented\nline three", block.text)
        assertEquals(listOf(TextSpan(0, block.text.length, CharFormat(mono = true))), block.spans)
    }

    @Test
    fun `block quotes indent by their depth`() {
        val body = "<p>out</p><blockquote><p>in</p><blockquote><p>deeper</p></blockquote></blockquote>"
        val blocks = read(EpubFixtures.epub(listOf(chapter(body)))).blocks.map { (it as OfficeBlock.Paragraph).indent }
        assertEquals(listOf(0, 1, 2), blocks)
    }

    @Test
    fun `a rule is a divider`() {
        val blocks = read(EpubFixtures.epub(listOf(chapter("<p>a</p><hr/><p>b</p>")))).blocks
        assertEquals(listOf<OfficeBlock>(OfficeBlock.Paragraph("a"), OfficeBlock.Divider, OfficeBlock.Paragraph("b")), blocks)
    }

    @Test
    fun `nested and ordered lists`() {
        val body = """
            <ul><li>apple</li><li>fruit
              <ol start="3"><li>three</li><li>four</li></ol>
            </li><li>pear</li></ul>
            <ol type="a"><li>x</li><li><p>y</p><p>more of y</p></li></ol>
        """.trimIndent()
        val items = read(EpubFixtures.epub(listOf(chapter(body)))).blocks.map { it as OfficeBlock.ListItem }
        assertEquals(
            listOf(
                Triple("apple", "•", 0),
                Triple("fruit", "•", 0),
                Triple("three", "3.", 1),
                Triple("four", "4.", 1),
                Triple("pear", "•", 0),
                Triple("x", "a.", 0),
                Triple("y", "b.", 0),
                Triple("more of y", "", 0),
            ),
            items.map { Triple(it.text, it.marker, it.level) },
        )
    }

    @Test
    fun `tables flatten with spans`() {
        val body = """
            <table>
              <tr><th colspan="2">Head</th><th>C</th></tr>
              <tr><td rowspan="2">a</td><td>b</td><td>c</td></tr>
              <tr><td>d</td><td>e</td></tr>
            </table>
            <p>after</p>
        """.trimIndent()
        val blocks = read(EpubFixtures.epub(listOf(chapter(body)))).blocks
        val table = blocks[0] as OfficeBlock.Table
        assertEquals(3, table.rows.size)
        assertEquals(listOf(Triple("Head", 0, 2), Triple("C", 2, 1)), table.rows[0].map { Triple(it.text, it.column, it.colspan) })
        assertTrue(table.rows[0].all { cell -> cell.spans.any { it.format.bold } })
        assertEquals(listOf(Triple("a", 0, 2), Triple("b", 1, 1), Triple("c", 2, 1)), table.rows[1].map { Triple(it.text, it.column, it.rowspan) })
        // The cell above covers column 0 of the third row, so its cells start at column 1.
        assertEquals(listOf("d" to 1, "e" to 2), table.rows[2].map { it.text to it.column })
        assertEquals(OfficeBlock.Paragraph("after"), blocks[1])
    }

    @Test
    fun `cells keep their paragraphs as lines and nested tables become words`() {
        val body = """<table><tr><td><p>one</p><p>two</p></td><td>x<table><tr><td>in1</td><td>in2</td></tr></table>y</td></tr></table>"""
        val table = read(EpubFixtures.epub(listOf(chapter(body)))).blocks.single() as OfficeBlock.Table
        assertEquals(listOf("one\ntwo", "x in1 in2 y"), table.rows.single().map { it.text })
    }

    @Test
    fun `scripts styles frames hidden things and svg text are not reading matter`() {
        val body = """
            <p>visible</p>
            <script>var x = "no";</script><style>p { color: red }</style>
            <iframe src="http://example.org/"><p>frame</p></iframe>
            <object><p>obj</p></object><embed src="x"/>
            <p hidden="">hidden</p><p style="display: none">gone</p>
            <svg xmlns="http://www.w3.org/2000/svg"><text>svg words</text></svg>
            <p>end</p>
        """.trimIndent()
        val book = read(EpubFixtures.epub(listOf(chapter(body, "c.xhtml"))))
        assertEquals(listOf("visible", "end"), texts(book))
    }

    // --- entities and malformed chapters ------------------------------------------------------------

    @Test
    fun `html entities that xml does not define are read`() {
        val body = "<p>a&nbsp;b &mdash; caf&eacute; &hellip; &copy; &amp; &#233; &#x41;</p>"
        val block = read(EpubFixtures.epub(listOf(chapter(body)))).blocks.single() as OfficeBlock.Paragraph
        assertEquals("a\u00A0b \u2014 caf\u00E9 \u2026 \u00A9 & \u00E9 A", block.text)
    }

    @Test
    fun `a doctype that names a dtd is never fetched`() {
        // The fixture's chapters all carry an XHTML 1.1 doctype; it reads without any network.
        assertEquals(listOf("ok"), texts(read(EpubFixtures.epub(listOf(chapter("<p>ok</p>"))))))
    }

    @Test
    fun `one malformed chapter is skipped and the book goes on`() {
        val bytes = EpubFixtures.epub(
            listOf(
                chapter("<p>before</p>", "a.xhtml"),
                chapter("<p>broken <b>text</p></b>", "b.xhtml"),
                chapter("<p>unterminated", "c.xhtml"),
                chapter("<p>after</p>", "d.xhtml"),
            ),
        )
        val book = read(bytes)
        assertEquals(listOf("before", "after"), texts(book))
        assertTrue(book.incomplete)
    }

    @Test
    fun `a chapter that fails part way leaves no half of itself behind`() {
        val bytes = EpubFixtures.epub(listOf(chapter("<p>one</p><p>two</p><p>three</p></div>")))
        val book = read(bytes)
        assertEquals(emptyList<OfficeBlock>(), book.blocks)
        assertTrue(book.incomplete)
    }

    @Test
    fun `a missing chapter is reported not fatal`() {
        val good = EpubFixtures.epub(listOf(chapter("<p>a</p>", "a.xhtml"), chapter("<p>b</p>", "b.xhtml")))
        val entries = LinkedHashMap<String, ByteArray>()
        java.util.zip.ZipInputStream(good.inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.name != "OEBPS/b.xhtml") entries[entry.name] = zip.readBytes()
            }
        }
        val book = read(OfficeFixtures.zipOf(entries))
        assertEquals(listOf("a"), texts(book))
        assertTrue(book.incomplete)
    }

    @Test
    fun `extreme nesting skips the chapter instead of overflowing anything`() {
        val deep = "<div>".repeat(5_000) + "x" + "</div>".repeat(5_000)
        val book = read(EpubFixtures.epub(listOf(chapter(deep, "deep.xhtml"), chapter("<p>fine</p>", "ok.xhtml"))))
        assertEquals(listOf("fine"), texts(book))
        assertTrue(book.incomplete)
    }

    // --- images ----------------------------------------------------------------------------------

    @Test
    fun `images are placed where they appear`() {
        val dir = temp.newFolder("media")
        val body = """<p>before</p><p><img src="images/a.png" alt="A picture" width="120"/></p><p>after</p>"""
        val bytes = EpubFixtures.epub(
            listOf(chapter(body)),
            media = mapOf("images/a.png" to OfficeFixtures.PNG_1X1),
        )
        val book = read(bytes, dir)
        assertEquals(3, book.blocks.size)
        assertEquals(OfficeBlock.Paragraph("before"), book.blocks[0])
        val image = book.blocks[1] as OfficeBlock.Image
        assertEquals("image/png", image.mimeType)
        assertEquals(120f, image.widthDp)
        assertEquals("A picture", image.altText)
        assertTrue(image.file.exists())
        assertTrue(image.file.canonicalPath.startsWith(dir.canonicalPath))
        assertEquals(OfficeBlock.Paragraph("after"), book.blocks[2])
        assertFalse(book.incomplete)
    }

    @Test
    fun `an svg wrapped image and a cover page picture are images too`() {
        val dir = temp.newFolder("media")
        val svgPage = """<div><svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" viewBox="0 0 1 1"><image xlink:href="images/a.png" width="1" height="1"/></svg></div>"""
        val bytes = EpubFixtures.epub(listOf(chapter(svgPage)), media = mapOf("images/a.png" to OfficeFixtures.PNG_1X1))
        val blocks = read(bytes, dir).blocks
        assertEquals(1, blocks.size)
        assertTrue(blocks.single() is OfficeBlock.Image)
    }

    @Test
    fun `an image that is not in the archive or not local is dropped and reported`() {
        val dir = temp.newFolder("media")
        val body = """<p>t</p><img src="missing.png"/><img src="http://example.org/x.png"/><img src="data:image/png;base64,AAAA"/>"""
        val book = read(EpubFixtures.epub(listOf(chapter(body))), dir)
        assertEquals(listOf("t"), texts(book))
        // Only the one that named a book path counts as lost; the others were never the book's.
        assertTrue(book.incomplete)
    }

    @Test
    fun `without a media directory the text is read and images are simply not wanted`() {
        val bytes = EpubFixtures.epub(listOf(chapter("""<p>t</p><img src="a.png"/>""")), media = mapOf("a.png" to OfficeFixtures.PNG_1X1))
        val book = read(bytes, mediaDir = null)
        assertEquals(listOf("t"), texts(book))
        assertFalse(book.incomplete)
    }

    @Test
    fun `an image path cannot leave the book`() {
        val dir = temp.newFolder("media")
        val body = """<p>t</p><img src="../../../etc/passwd"/><img src="/abs.png"/>"""
        val book = read(EpubFixtures.epub(listOf(chapter(body)), extra = mapOf("abs.png" to OfficeFixtures.PNG_1X1)), dir)
        // `/abs.png` is the archive root, which is inside the book and so allowed; `..` above the root is not.
        assertEquals(2, book.blocks.size)
        assertTrue(book.blocks[1] is OfficeBlock.Image)
    }

    // --- outline ---------------------------------------------------------------------------------

    private val threeChapters = listOf(
        chapter("<h1>One</h1><p>a</p><p>b</p>", "one.xhtml"),
        chapter("<h1>Two</h1><p>c</p>", "two.xhtml"),
        chapter("<h1>Three</h1><p>d</p>", "three.xhtml"),
    )

    @Test
    fun `an epub 3 navigation document gives chapter entries that jump to the first block`() {
        val nav = EpubFixtures.navDoc(
            """<li><a href="one.xhtml">Part I</a><ol><li><a href="two.xhtml#s">Chapter 2</a></li></ol></li><li><a href="three.xhtml">Part II</a></li>""",
        )
        val book = read(EpubFixtures.epub(threeChapters, nav = nav))
        assertEquals(
            listOf(OutlineEntry(0, 0, "Part I"), OutlineEntry(3, 1, "Chapter 2"), OutlineEntry(5, 0, "Part II")),
            book.outline,
        )
    }

    @Test
    fun `a landmarks nav before the toc is not mistaken for it`() {
        val nav = EpubFixtures.xhtml(
            """<nav epub:type="landmarks"><ol><li><a href="one.xhtml">Cover</a></li></ol></nav>
               <nav epub:type="toc"><ol><li><a href="two.xhtml">Real</a></li></ol></nav>""",
        )
        assertEquals(listOf(OutlineEntry(3, 0, "Real")), read(EpubFixtures.epub(threeChapters, nav = nav)).outline)
    }

    @Test
    fun `an epub 2 ncx works and nests by navPoint`() {
        val ncx = EpubFixtures.ncxDoc(
            EpubFixtures.ncxPoint("Start", "one.xhtml", EpubFixtures.ncxPoint("Nested", "two.xhtml")) +
                EpubFixtures.ncxPoint("End", "three.xhtml#x"),
        )
        val book = read(EpubFixtures.epub(threeChapters, ncx = ncx))
        assertEquals(listOf(OutlineEntry(0, 0, "Start"), OutlineEntry(3, 1, "Nested"), OutlineEntry(5, 0, "End")), book.outline)
    }

    @Test
    fun `navigation prefers the nav document and uses depth relative to its shallowest`() {
        val nav = EpubFixtures.navDoc("""<li><a href="two.xhtml">Only</a></li>""")
        val ncx = EpubFixtures.ncxDoc(EpubFixtures.ncxPoint("Ignored", "one.xhtml"))
        assertEquals(listOf(OutlineEntry(3, 0, "Only")), read(EpubFixtures.epub(threeChapters, nav = nav, ncx = ncx)).outline)
    }

    @Test
    fun `an entry for a chapter that is not there is dropped and a label with no link is kept`() {
        val nav = EpubFixtures.navDoc(
            """<li><span>Group</span><ol><li><a href="two.xhtml">Two</a></li><li><a href="gone.xhtml">Gone</a></li></ol></li>""",
        )
        assertEquals(
            listOf(OutlineEntry(NO_OUTLINE_TARGET, 0, "Group"), OutlineEntry(3, 1, "Two")),
            read(EpubFixtures.epub(threeChapters, nav = nav)).outline,
        )
    }

    @Test
    fun `no usable table of contents means the headings stand in`() {
        assertNull(read(EpubFixtures.epub(threeChapters)).outline)
        val nav = EpubFixtures.navDoc("""<li><a href="gone.xhtml">Gone</a></li>""")
        assertNull(read(EpubFixtures.epub(threeChapters, nav = nav)).outline)
    }

    @Test
    fun `an image before a chapter moves the entry along with it`() {
        val dir = temp.newFolder("media")
        val chapters = listOf(
            chapter("""<h1>One</h1><img src="a.png"/><p>a</p>""", "one.xhtml"),
            chapter("<h1>Two</h1><p>c</p>", "two.xhtml"),
        )
        val nav = EpubFixtures.navDoc("""<li><a href="two.xhtml">Two</a></li>""")
        val book = read(EpubFixtures.epub(chapters, nav = nav, media = mapOf("a.png" to OfficeFixtures.PNG_1X1)), dir)
        val index = book.outline!!.single().index
        assertEquals(OfficeBlock.Heading("Two", 1), book.blocks[index])
    }

    // --- cover -----------------------------------------------------------------------------------

    @Test
    fun `a cover named by the cover-image property is found`() {
        val bytes = EpubFixtures.epub(
            listOf(chapter("<p>x</p>")),
            media = mapOf("images/cover.png" to OfficeFixtures.PNG_1X1, "images/other.png" to byteArrayOf(1)),
            coverImageHref = "images/cover.png",
        )
        assertTrue(OfficeFixtures.PNG_1X1.contentEquals(extractor().readCover(OfficeFixtures.streamFactory(bytes))))
    }

    @Test
    fun `an epub 2 cover meta is found`() {
        val bytes = EpubFixtures.epub(
            listOf(chapter("<p>x</p>")),
            media = mapOf("cover.png" to OfficeFixtures.PNG_1X1),
            coverMetaId = "m-cover_png",
        )
        assertTrue(OfficeFixtures.PNG_1X1.contentEquals(extractor().readCover(OfficeFixtures.streamFactory(bytes))))
    }

    @Test
    fun `no cover and an svg cover both give none`() {
        assertNull(extractor().readCover(OfficeFixtures.streamFactory(EpubFixtures.epub(listOf(chapter("<p>x</p>"))))))
        val svg = EpubFixtures.epub(
            listOf(chapter("<p>x</p>")),
            media = mapOf("cover.svg" to "<svg/>".toByteArray()),
            coverImageHref = "cover.svg",
        )
        assertNull(extractor().readCover(OfficeFixtures.streamFactory(svg)))
    }

    @Test
    fun `reading the cover of something that is not an epub is just none`() {
        assertNull(extractor().readCover(OfficeFixtures.streamFactory(byteArrayOf(1, 2, 3))))
    }

    // --- protection ------------------------------------------------------------------------------

    private fun withEncryption(xml: String?, rights: Boolean = false): ByteArray {
        val extra = LinkedHashMap<String, ByteArray>()
        if (xml != null) extra["META-INF/encryption.xml"] = xml.toByteArray()
        if (rights) extra["META-INF/rights.xml"] = "<rights/>".toByteArray()
        return EpubFixtures.epub(listOf(chapter("<p>readable</p>")), extra = extra)
    }

    private fun assertProtected(bytes: ByteArray) {
        try {
            read(bytes)
            fail("expected a protected book")
        } catch (_: EpubProtectedException) {
            // expected
        }
    }

    @Test
    fun `a book whose only encryption is font obfuscation is not drm`() {
        assertEquals(listOf("readable"), texts(read(withEncryption(EpubFixtures.encryption(EpubFixtures.IDPF_FONT)))))
        assertEquals(listOf("readable"), texts(read(withEncryption(EpubFixtures.encryption(EpubFixtures.ADOBE_FONT)))))
        assertEquals(
            listOf("readable"),
            texts(read(withEncryption(EpubFixtures.encryption(EpubFixtures.IDPF_FONT, EpubFixtures.ADOBE_FONT)))),
        )
    }

    @Test
    fun `any other algorithm is drm`() {
        assertProtected(withEncryption(EpubFixtures.encryption(EpubFixtures.AES)))
        assertProtected(withEncryption(EpubFixtures.encryption(EpubFixtures.IDPF_FONT, EpubFixtures.AES)))
    }

    @Test
    fun `a rights file is drm`() {
        assertProtected(withEncryption(null, rights = true))
    }

    @Test
    fun `an encryption file that cannot be read fails closed`() {
        assertProtected(withEncryption("<encryption><oops>"))
    }

    // --- hostile archives ------------------------------------------------------------------------

    private fun expectFormatError(bytes: ByteArray, limits: EpubLimits = EpubLimits()) {
        try {
            read(bytes, limits = limits)
            fail("expected EpubFormatException")
        } catch (_: EpubFormatException) {
            // expected
        }
    }

    @Test
    fun `an entry that climbs out of the archive is refused`() {
        for (name in listOf("../evil.xhtml", "OEBPS/../../evil.xhtml", "/etc/passwd", "\\windows\\x", "C:\\x", "a\\..\\b")) {
            expectFormatError(EpubFixtures.epub(listOf(chapter("<p>x</p>")), extra = mapOf(name to byteArrayOf(1))))
        }
    }

    @Test
    fun `names that only look like traversal are fine`() {
        val bytes = EpubFixtures.epub(listOf(chapter("<p>ok</p>")), extra = mapOf("OEBPS/a..b.txt" to byteArrayOf(1), "OEBPS/..hidden" to byteArrayOf(1)))
        assertEquals(listOf("ok"), texts(read(bytes)))
    }

    @Test
    fun `too many entries is refused`() {
        val extra = (1..20).associate { "OEBPS/f$it.bin" to byteArrayOf(1) }
        expectFormatError(EpubFixtures.epub(listOf(chapter("<p>x</p>")), extra = extra), EpubLimits(maxEntries = 10))
    }

    @Test
    fun `an entry that inflates past its cap is refused whatever it declares`() {
        val big = ByteArray(300_000)
        expectFormatError(EpubFixtures.epub(listOf(chapter("<p>x</p>")), extra = mapOf("OEBPS/big.bin" to big)), EpubLimits(maxEntryBytes = 100_000))
    }

    @Test
    fun `all entries together are capped too`() {
        val extra = (1..5).associate { "OEBPS/f$it.bin" to ByteArray(60_000) { i -> (i * 31 + it).toByte() } }
        expectFormatError(
            EpubFixtures.epub(listOf(chapter("<p>x</p>")), extra = extra),
            EpubLimits(maxEntryBytes = 100_000, maxTotalBytes = 200_000, maxRatio = 10_000),
        )
    }

    @Test
    fun `an implausible compression ratio is a bomb`() {
        val zeros = ByteArray(2_000_000)
        expectFormatError(
            EpubFixtures.epub(listOf(chapter("<p>x</p>")), extra = mapOf("OEBPS/bomb.bin" to zeros)),
            EpubLimits(maxRatio = 50, ratioFloorBytes = 10_000),
        )
    }

    @Test
    fun `a book that compresses well but below the floor is not a bomb`() {
        val body = "<p>" + "lorem ipsum dolor sit amet ".repeat(2_000) + "</p>"
        val book = read(EpubFixtures.epub(listOf(chapter(body))), limits = EpubLimits(maxRatio = 50, ratioFloorBytes = 1_000_000))
        assertEquals(1, book.blocks.size)
    }

    @Test
    fun `not an epub at all`() {
        expectFormatError(byteArrayOf(1, 2, 3, 4))
        expectFormatError(OfficeFixtures.zipOf(mapOf("hello.txt" to "hi".toByteArray())))
        expectFormatError(OfficeFixtures.docx(OfficeFixtures.docxBody("<w:p/>")))
    }

    @Test
    fun `a container or package that is not valid xml is a format error`() {
        expectFormatError(EpubFixtures.epub(listOf(chapter("<p>x</p>")), container = "<container><rootfiles>"))
        expectFormatError(EpubFixtures.epub(listOf(chapter("<p>x</p>")), opfOverride = "<package><manifest>"))
        expectFormatError(
            EpubFixtures.epub(listOf(chapter("<p>x</p>")), opfOverride = """<package xmlns="http://www.idpf.org/2007/opf"><manifest/><spine/></package>"""),
        )
    }

    // --- limits and the incomplete notice -------------------------------------------------------------

    @Test
    fun `reaching the block limit keeps what fits and says the book is incomplete`() {
        val body = (1..50).joinToString("") { "<p>para $it</p>" }
        val book = read(EpubFixtures.epub(listOf(chapter(body), chapter("<p>never</p>", "two.xhtml"))), limits = EpubLimits(maxBlocks = 10))
        assertEquals(10, book.blocks.size)
        assertEquals("para 10", (book.blocks.last() as OfficeBlock.Paragraph).text)
        assertTrue(book.incomplete)
    }

    @Test
    fun `reaching the table row limit says so`() {
        val rows = (1..30).joinToString("") { "<tr><td>$it</td></tr>" }
        val book = read(EpubFixtures.epub(listOf(chapter("<table>$rows</table>"))), limits = EpubLimits(maxTableRows = 5))
        assertEquals(5, (book.blocks.single() as OfficeBlock.Table).rows.size)
        assertTrue(book.incomplete)
    }

    @Test
    fun `text beyond the memory budget is skipped and reported`() {
        val chapters = (1..4).map { chapter("<p>${"w".repeat(300)}</p>", "c$it.xhtml") }
        val book = read(EpubFixtures.epub(chapters), limits = EpubLimits(maxTextBytes = 3_000))
        assertTrue(book.incomplete)
        assertTrue(book.blocks.size < 4)
    }

    @Test
    fun `a complete book is not reported incomplete`() {
        assertFalse(read(EpubFixtures.epub(threeChapters)).incomplete)
    }

    // --- preview ---------------------------------------------------------------------------------

    @Test
    fun `a preview reads only the first chapters`() {
        val bytes = EpubFixtures.epub(
            listOf(
                chapter("<p>first</p>", "a.xhtml"),
                chapter("<p>second</p>", "b.xhtml"),
                chapter("<p>third</p>", "c.xhtml"),
            ),
        )
        val book = extractor().extract(OfficeFixtures.streamFactory(bytes), previewChapters = 1)
        assertEquals(listOf("first"), texts(book))
        assertNull(book.outline)
    }

    // --- helpers ---------------------------------------------------------------------------------

    @Test
    fun `references fold and refuse to leave the book`() {
        assertEquals("OEBPS/text/a.xhtml", resolveEpubPath("OEBPS/", "text/a.xhtml"))
        assertEquals("OEBPS/a.xhtml", resolveEpubPath("OEBPS/text/", "../a.xhtml#frag"))
        assertEquals("OEBPS/a.xhtml", resolveEpubPath("OEBPS/", "./a.xhtml?x=1"))
        assertEquals("images/x.png", resolveEpubPath("OEBPS/", "/images/x.png"))
        assertEquals("OEBPS/my file.xhtml", resolveEpubPath("OEBPS/", "my%20file.xhtml"))
        assertEquals("OEBPS/caf\u00E9.xhtml", resolveEpubPath("OEBPS/", "caf%C3%A9.xhtml"))
        assertEquals("OEBPS/100%.xhtml", resolveEpubPath("OEBPS/", "100%.xhtml"))
        assertNull(resolveEpubPath("OEBPS/", "../../etc/passwd"))
        assertNull(resolveEpubPath("", "../x"))
        assertNull(resolveEpubPath("OEBPS/", "http://example.org/a.xhtml"))
        assertNull(resolveEpubPath("OEBPS/", "data:image/png;base64,AAAA"))
        assertNull(resolveEpubPath("OEBPS/", "#only-a-fragment"))
        assertNull(resolveEpubPath("OEBPS/", ""))
    }

    @Test
    fun `an image insertion reports where each went in`() {
        val result = OfficeMedia.Result(listOf(2, 2, 5), 0)
        assertEquals(0, result.shifted(0))
        assertEquals(2, result.shifted(2))
        assertEquals(5, result.shifted(3))
        assertEquals(9, result.shifted(6))
    }

    @Test
    fun `an explicit outline cleans titles drops empty ones and makes depth relative`() {
        val entries = listOf(
            OutlineEntry(0, 2, "  Part\n One "),
            OutlineEntry(1, 3, "   "),
            OutlineEntry(2, 3, "Sub"),
            OutlineEntry(NO_OUTLINE_TARGET, 2, "Group"),
        )
        assertEquals(
            listOf(OutlineEntry(0, 0, "Part One"), OutlineEntry(2, 1, "Sub"), OutlineEntry(NO_OUTLINE_TARGET, 0, "Group")),
            explicitOutline(entries),
        )
        assertEquals(emptyList<OutlineEntry>(), explicitOutline(emptyList()))
    }
}
