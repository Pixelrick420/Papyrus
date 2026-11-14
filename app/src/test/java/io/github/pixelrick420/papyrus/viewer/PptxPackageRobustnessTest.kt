package io.github.pixelrick420.papyrus.viewer

import io.github.pixelrick420.papyrus.viewer.pptx.PptxFormatException
import io.github.pixelrick420.papyrus.viewer.pptx.PptxLimits
import io.github.pixelrick420.papyrus.viewer.pptx.PptxPackage
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
import java.io.IOException

// A .pptx is an archive of XML from someone else, so every case here assumes it is hostile: no crash, bounded
// work, and either the deck opens or it is refused with a message that says why.
class PptxPackageRobustnessTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun read(bytes: ByteArray, mediaDir: File? = null, limits: PptxLimits = PptxLimits()): PptxPackage =
        PptxPackage.read(OfficeFixtures.streamFactory(bytes), mediaDir, limits, OfficeFixtures::parser)

    private fun read(deck: Deck, mediaDir: File? = null, limits: PptxLimits = PptxLimits()): PptxPackage =
        read(deck.toBytes(), mediaDir, limits)

    private fun refusal(bytes: ByteArray, limits: PptxLimits = PptxLimits()): String? =
        runCatching { read(bytes, null, limits) }.exceptionOrNull()?.message

    private fun deck() = pptx {
        layout("Title and Content")
        slide { title("Hello") }
    }

    private fun pictureDeck() = pptx {
        layout("Blank")
        slide { picture(OfficeFixtures.PNG_1X1, 0, 0, 1, 1) }
    }

    private fun withEntryAfter(after: String, name: String, bytes: ByteArray): ByteArray {
        val out = LinkedHashMap<String, ByteArray>()
        for ((part, content) in deck().parts) {
            out[part] = content
            if (part == after) out[name] = bytes
        }
        return OfficeFixtures.zipOf(out)
    }

    private fun withEntries(vararg extra: Pair<String, ByteArray>): ByteArray =
        OfficeFixtures.zipOf(LinkedHashMap(deck().parts).apply { extra.forEach { (name, bytes) -> put(name, bytes) } })

    private fun Deck.withPart(name: String, bytes: ByteArray): Deck =
        Deck(LinkedHashMap(parts).apply { put(name, bytes) })

    @Test
    fun `an entry that inflates past the ratio is refused`() {
        val bytes = withEntryAfter("[Content_Types].xml", "ppt/media/bomb.bin", ByteArray(2_000_000))

        assertEquals("implausible compression", refusal(bytes))
    }

    @Test
    fun `an entry past the per-entry cap is refused`() {
        assertEquals("too large", refusal(deck().toBytes(), PptxLimits(maxEntryBytes = 100)))
    }

    @Test
    fun `more entries than the cap is refused`() {
        assertEquals("too many entries", refusal(deck().toBytes(), PptxLimits(maxEntries = 5)))
    }

    @Test
    fun `an XML part past its cap is refused`() {
        assertEquals("[Content_Types].xml is too large", refusal(deck().toBytes(), PptxLimits(maxXmlPartBytes = 100)))
    }

    @Test
    fun `all the XML parts together past their cap is refused`() {
        assertEquals("xml parts are too large", refusal(deck().toBytes(), PptxLimits(maxXmlBytes = 2_000)))
    }

    @Test
    fun `a truncated archive is refused, never crashes`() {
        val bytes = deck().toBytes()

        for (cut in 0 until bytes.size step 61) {
            try {
                read(bytes.copyOf(cut))
            } catch (_: IOException) {
                // a cut-short archive is refused, which is the point of the test
            }
        }
    }

    @Test
    fun `a cyclic or missing relationship does not stop the package opening`() {
        val deck = pptx {
            layout("Blank")
            slide {
                rel("rId2", REL_SLIDE, "slide1.xml")
                rel("rId3", REL_IMAGE, "../media/missing.png")
            }
        }

        val pkg = read(deck)
        val slide = "ppt/slides/slide1.xml"

        assertEquals(slide, pkg.targetOf(slide, "rId2"))
        assertNull(pkg.targetOf(slide, "rId3"))
        assertEquals(1, pkg.mediaDropped)
        assertTrue(pkg.mediaNames.isEmpty())
    }

    @Test
    fun `a malformed relationships part is ignored, not fatal`() {
        val deck = deck().withPart("ppt/slides/_rels/slide1.xml.rels", "not xml".toByteArray())

        val pkg = read(deck)

        assertTrue(pkg.relationshipsOf("ppt/slides/slide1.xml").isEmpty())
        assertEquals("ppt/presentation.xml", pkg.mainPart)
    }

    @Test
    fun `a package that declares a main part it does not contain is refused`() {
        val without = Deck(deck().parts - "ppt/presentation.xml")

        assertEquals("main part ppt/presentation.xml is missing", refusal(without.toBytes()))
    }

    @Test
    fun `a document that is not a presentation is refused with the reason`() {
        assertEquals("not a presentation", refusal(OfficeFixtures.docx(OfficeFixtures.docxBody("<w:p/>"))))
        assertEquals(
            "no [Content_Types].xml: not a presentation",
            refusal(OfficeFixtures.odt(OfficeFixtures.odtContent("<text:p>x</text:p>"))),
        )

        val failure = runCatching { read(OLE2_HEADER) }.exceptionOrNull()
        assertTrue("got $failure", failure is IOException)
    }

    @Test
    fun `content types that are not XML are refused`() {
        val bytes = OfficeFixtures.zipOf(mapOf("[Content_Types].xml" to "not xml".toByteArray()))

        assertEquals("content types are not valid", refusal(bytes))
    }

    @Test
    fun `slides are accepted at the cap and refused above it`() {
        val deck = pptx {
            layout("Blank")
            slide { title("one") }
            slide { title("two") }
        }

        assertNotNull(read(deck.toBytes(), null, PptxLimits(maxSlides = 2)))
        assertEquals("too many slides", refusal(deck.toBytes(), PptxLimits(maxSlides = 1)))
    }

    @Test
    fun `media is accepted at its cap and refused above it`() {
        val deck = pictureDeck()
        val size = OfficeFixtures.PNG_1X1.size

        assertEquals(1, read(deck.toBytes(), temp.newFolder("a"), PptxLimits(maxImageBytes = size)).mediaNames.size)
        assertTrue(read(deck.toBytes(), temp.newFolder("b"), PptxLimits(maxImageBytes = size - 1)).mediaNames.isEmpty())

        assertEquals(1, read(deck.toBytes(), temp.newFolder("c"), PptxLimits(maxMediaBytes = size.toLong())).mediaNames.size)
        assertTrue(read(deck.toBytes(), temp.newFolder("d"), PptxLimits(maxMediaBytes = size - 1L)).mediaNames.isEmpty())

        assertEquals(1, read(deck.toBytes(), temp.newFolder("e"), PptxLimits(maxImages = 1)).mediaNames.size)
        assertTrue(read(deck.toBytes(), temp.newFolder("f"), PptxLimits(maxImages = 0)).mediaNames.isEmpty())
    }

    @Test
    fun `an entry name that would escape the archive is ignored`() {
        val bytes = withEntries(
            "../evil.xml" to "<a/>".toByteArray(),
            "/evil.xml" to "<a/>".toByteArray(),
            "ppt\\..\\evil.xml" to "<a/>".toByteArray(),
        )

        val pkg = read(bytes)

        assertEquals("ppt/presentation.xml", pkg.mainPart)
        assertFalse(pkg.partNames.any { it.contains("evil") })
    }

    @Test
    fun `a media target that escapes the package writes nothing outside the cache`() {
        val dir = temp.newFolder("media")
        val deck = pptx {
            layout("Blank")
            slide { rel("rId2", REL_IMAGE, "../../../../tmp/evil.png") }
            part("tmp/evil.png", OfficeFixtures.PNG_1X1)
        }

        val pkg = read(deck, dir)

        assertTrue(pkg.mediaNames.isEmpty())
        assertEquals(0, dir.list()?.size ?: 0)
    }

    @Test
    fun `a deck with nothing but content types opens or is refused, never crashes`() {
        val bytes = OfficeFixtures.zipOf(mapOf("[Content_Types].xml" to "<Types/>".toByteArray()))

        val failure = runCatching { read(bytes) }.exceptionOrNull()

        assertTrue("got $failure", failure is PptxFormatException)
        assertEquals("not a presentation", failure?.message)
    }

    @Test
    fun `a hostile package is refused without touching a file`() {
        val dir = temp.newFolder("media")
        val bytes = withEntryAfter("[Content_Types].xml", "ppt/media/bomb.bin", ByteArray(2_000_000))

        try {
            read(bytes, dir)
            fail("a bomb must be refused")
        } catch (_: IOException) {
            assertEquals(0, dir.list()?.size ?: 0)
        }
    }

    private companion object {
        const val REL_BASE = "http://schemas.openxmlformats.org/officeDocument/2006/relationships/"

        const val REL_IMAGE = REL_BASE + "image"

        const val REL_SLIDE = REL_BASE + "slide"

        val OLE2_HEADER = byteArrayOf(
            0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(), 0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte(),
        ) + ByteArray(504)
    }
}
