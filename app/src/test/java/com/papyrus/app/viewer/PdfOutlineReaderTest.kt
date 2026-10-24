package com.papyrus.app.viewer

import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSInteger
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionGoTo
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionURI
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDNamedDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageFitDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import com.tom_roush.pdfbox.pdmodel.PDDocumentNameDictionary
import com.tom_roush.pdfbox.pdmodel.PDDestinationNameTreeNode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The outline reader on documents built in memory, as [PdfLinkExtractorTest] does. Most of what is
 * tested here is damage: an outline is whatever its author wrote, and none of it may throw, hang or
 * lose the bookmarks around the bad one.
 */
class PdfOutlineReaderTest {

    private val opened = ArrayList<PDDocument>()

    @After
    fun closeDocuments() = opened.forEach { it.close() }

    private fun document(pages: Int) = PDDocument().apply { repeat(pages) { addPage(PDPage()) } }.also { opened.add(it) }

    private fun pageDestination(page: PDPage): PDPageFitDestination = PDPageFitDestination().apply { setPage(page) }

    private fun bookmark(title: String?, destination: PDDestination? = null, vararg children: PDOutlineItem): PDOutlineItem =
        PDOutlineItem().apply {
            if (title != null) setTitle(title)
            if (destination != null) setDestination(destination)
            children.forEach { addLast(it) }
        }

    private fun PDDocument.withOutline(vararg top: PDOutlineItem): PDDocument = apply {
        val outline = PDDocumentOutline()
        top.forEach { outline.addLast(it) }
        documentCatalog.documentOutline = outline
    }

    private fun PDDocument.to(index: Int) = pageDestination(getPage(index))

    private fun entry(index: Int, depth: Int, title: String) = OutlineEntry(index, depth, title)

    @Test
    fun `a nested outline is a depth-tagged list in reading order`() {
        val doc = document(6)
        doc.withOutline(
            bookmark("Chapter 1", doc.to(0), bookmark("1.1", doc.to(1)), bookmark("1.2", doc.to(2), bookmark("1.2.1", doc.to(3)))),
            bookmark("Chapter 2", doc.to(4)),
        )
        assertEquals(
            listOf(
                entry(0, 0, "Chapter 1"),
                entry(1, 1, "1.1"),
                entry(2, 1, "1.2"),
                entry(3, 2, "1.2.1"),
                entry(4, 0, "Chapter 2"),
            ),
            PdfOutlineReader.read(doc),
        )
    }

    @Test
    fun `a document without an outline reads as empty`() {
        assertEquals(emptyList<OutlineEntry>(), PdfOutlineReader.read(document(3)))
    }

    @Test
    fun `an outline with no bookmarks reads as empty`() {
        val doc = document(3).withOutline()
        assertEquals(emptyList<OutlineEntry>(), PdfOutlineReader.read(doc))
    }

    @Test
    fun `several bookmarks may share a page`() {
        val doc = document(2)
        doc.withOutline(bookmark("A", doc.to(1)), bookmark("B", doc.to(1)))
        assertEquals(listOf(entry(1, 0, "A"), entry(1, 0, "B")), PdfOutlineReader.read(doc))
    }

    @Test
    fun `titles are one line each`() {
        val doc = document(1)
        doc.withOutline(bookmark("  Part\n  One \t ", doc.to(0)))
        assertEquals(listOf(entry(0, 0, "Part One")), PdfOutlineReader.read(doc))
    }

    @Test
    fun `a GoTo action resolves like a destination`() {
        val doc = document(4)
        val item = bookmark("Goes via action").apply { action = PDActionGoTo().apply { destination = doc.to(2) } }
        doc.withOutline(item)
        assertEquals(listOf(entry(2, 0, "Goes via action")), PdfOutlineReader.read(doc))
    }

    @Test
    fun `the item's own destination wins over its action`() {
        val doc = document(4)
        val item = bookmark("Both", doc.to(1)).apply { action = PDActionGoTo().apply { destination = doc.to(3) } }
        doc.withOutline(item)
        assertEquals(listOf(entry(1, 0, "Both")), PdfOutlineReader.read(doc))
    }

    @Test
    fun `an action that is not a GoTo goes nowhere`() {
        val doc = document(2)
        val item = bookmark("Web").apply { action = PDActionURI().apply { uri = "https://example.org" } }
        doc.withOutline(item)
        assertEquals(emptyList<OutlineEntry>(), PdfOutlineReader.read(doc))
    }

    @Test
    fun `a named destination resolves through the name tree`() {
        val doc = document(5)
        val tree = PDDestinationNameTreeNode().apply { names = mapOf("chap3" to doc.to(3)) }
        doc.documentCatalog.names = PDDocumentNameDictionary(doc.documentCatalog).apply { dests = tree }
        doc.withOutline(bookmark("Chapter 3", PDNamedDestination("chap3")))
        assertEquals(listOf(entry(3, 0, "Chapter 3")), PdfOutlineReader.read(doc))
    }

    @Test
    fun `a named destination resolves through the catalog Dests dictionary`() {
        val doc = document(5)
        val dests = COSDictionary().apply { setItem(COSName.getPDFName("old"), doc.to(4).cosObject) }
        doc.documentCatalog.cosObject.setItem(COSName.DESTS, dests)
        doc.withOutline(bookmark("Old style", PDNamedDestination("old")))
        assertEquals(listOf(entry(4, 0, "Old style")), PdfOutlineReader.read(doc))
    }

    @Test
    fun `an unknown name is unresolved`() {
        val doc = document(3).withOutline(bookmark("Nowhere", PDNamedDestination("missing")))
        assertEquals(emptyList<OutlineEntry>(), PdfOutlineReader.read(doc))
    }

    @Test
    fun `a destination that carries a page number resolves, and one out of range does not`() {
        val doc = document(4)
        val byNumber = PDPageFitDestination().apply { pageNumber = 2 }
        val outOfRange = PDPageFitDestination().apply { pageNumber = 40 }
        doc.withOutline(bookmark("Number", byNumber), bookmark("Too far", outOfRange))
        assertEquals(listOf(entry(2, 0, "Number")), PdfOutlineReader.read(doc))
    }

    @Test
    fun `a page that is not in this document is unresolved`() {
        val doc = document(2)
        val elsewhere = pageDestination(PDPage()) // a page object that was never added to the tree
        doc.withOutline(bookmark("Stray", elsewhere), bookmark("Here", doc.to(1)))
        assertEquals(listOf(entry(1, 0, "Here")), PdfOutlineReader.read(doc))
    }

    @Test
    fun `an unresolvable leaf is dropped and its siblings are kept`() {
        val doc = document(3)
        doc.withOutline(bookmark("First", doc.to(0)), bookmark("Dead"), bookmark("Last", doc.to(2)))
        assertEquals(listOf(entry(0, 0, "First"), entry(2, 0, "Last")), PdfOutlineReader.read(doc))
    }

    @Test
    fun `a bookmark that only groups others is kept as an entry that cannot be chosen`() {
        val doc = document(4)
        doc.withOutline(bookmark("Part I", null, bookmark("Section", doc.to(1))))
        assertEquals(
            listOf(entry(NO_OUTLINE_TARGET, 0, "Part I"), entry(1, 1, "Section")),
            PdfOutlineReader.read(doc),
        )
    }

    @Test
    fun `a group whose children all go nowhere disappears`() {
        val doc = document(2)
        doc.withOutline(bookmark("Part", null, bookmark("Dead 1"), bookmark("Dead 2")), bookmark("Real", doc.to(0)))
        assertEquals(listOf(entry(0, 0, "Real")), PdfOutlineReader.read(doc))
    }

    @Test
    fun `a destination PdfBox cannot parse costs only its own bookmark`() {
        val doc = document(3)
        val broken = bookmark("Broken").apply { cosObject.setItem(COSName.DEST, COSInteger.get(7)) }
        doc.withOutline(bookmark("Before", doc.to(0)), broken, bookmark("After", doc.to(2)))
        assertEquals(listOf(entry(0, 0, "Before"), entry(2, 0, "After")), PdfOutlineReader.read(doc))
    }

    @Test
    fun `a blank title leaves the row out but the children keep their place`() {
        val doc = document(4)
        doc.withOutline(
            bookmark("Kept", doc.to(0)),
            bookmark("   ", doc.to(1), bookmark("Child A", doc.to(2)), bookmark("Child B", doc.to(3))),
        )
        assertEquals(
            listOf(entry(0, 0, "Kept"), entry(2, 0, "Child A"), entry(3, 0, "Child B")),
            PdfOutlineReader.read(doc),
        )
    }

    @Test
    fun `a bookmark with no title at all is treated as blank`() {
        val doc = document(2)
        doc.withOutline(bookmark(null, doc.to(0), bookmark("Child", doc.to(1))))
        assertEquals(listOf(entry(1, 0, "Child")), PdfOutlineReader.read(doc))
    }

    @Test
    fun `a sibling chain that loops back is read once`() {
        val doc = document(3)
        val a = bookmark("A", doc.to(0))
        val b = bookmark("B", doc.to(1))
        doc.withOutline(a, b)
        b.cosObject.setItem(COSName.NEXT, a.cosObject) // B's next is A again
        assertEquals(listOf(entry(0, 0, "A"), entry(1, 0, "B")), PdfOutlineReader.read(doc))
    }

    @Test
    fun `a bookmark that is its own next terminates`() {
        val doc = document(2)
        val a = bookmark("A", doc.to(0))
        doc.withOutline(a)
        a.cosObject.setItem(COSName.NEXT, a.cosObject)
        assertEquals(listOf(entry(0, 0, "A")), PdfOutlineReader.read(doc))
    }

    @Test
    fun `a child chain that points back at its parent terminates`() {
        val doc = document(3)
        val parent = bookmark("Parent", doc.to(0), bookmark("Child", doc.to(1)))
        doc.withOutline(parent)
        val child = parent.firstChild!!
        child.cosObject.setItem(COSName.FIRST, parent.cosObject) // the child's first child is its own parent
        assertEquals(listOf(entry(0, 0, "Parent"), entry(1, 1, "Child")), PdfOutlineReader.read(doc))
    }

    @Test
    fun `nesting is cut at the depth cap without throwing`() {
        val doc = document(1)
        var deepest = bookmark("level 99", doc.to(0))
        for (level in 98 downTo 0) deepest = bookmark("level $level", doc.to(0), deepest)
        doc.withOutline(deepest)
        val entries = PdfOutlineReader.read(doc)
        assertEquals(PdfOutlineReader.MAX_LEVELS, entries.size)
        assertEquals(PdfOutlineReader.MAX_LEVELS - 1, entries.maxOf { it.depth })
    }

    @Test
    fun `a huge outline stops at the entry cap and keeps what it read`() {
        val doc = document(1)
        val top = (0 until PdfOutlineReader.MAX_ENTRIES + 1000).map { bookmark("Bookmark $it", doc.to(0)) }
        doc.withOutline(*top.toTypedArray())
        val entries = PdfOutlineReader.read(doc)
        assertEquals(PdfOutlineReader.MAX_ENTRIES, entries.size)
        assertEquals("Bookmark 0", entries.first().title)
    }

    @Test
    fun `a very long title is cut`() {
        val doc = document(1)
        doc.withOutline(bookmark("x".repeat(5_000), doc.to(0)))
        assertTrue(PdfOutlineReader.read(doc).single().title.length <= 200)
    }

    @Test
    fun `a big book resolves every bookmark to its own page`() {
        val pages = 2_000
        val doc = document(pages)
        doc.withOutline(*(0 until pages).map { bookmark("Page ${it + 1}", doc.to(it)) }.toTypedArray())
        val entries = PdfOutlineReader.read(doc)
        assertEquals(pages, entries.size)
        entries.forEachIndexed { position, e -> assertEquals(position, e.index) }
    }
}
