package io.github.pixelrick420.papyrus.viewer

import io.github.pixelrick420.papyrus.viewer.pptx.PptxPackage
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PptxPackageTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun read(bytes: ByteArray, mediaDir: File? = null): PptxPackage =
        PptxPackage.read(OfficeFixtures.streamFactory(bytes), mediaDir, newParserFactory = OfficeFixtures::parser)

    private fun read(deck: Deck, mediaDir: File? = null): PptxPackage = read(deck.toBytes(), mediaDir)

    private fun Deck.withPart(name: String, edit: (String) -> String): Deck = Deck(
        parts.mapValues { (part, bytes) ->
            if (part == name) edit(bytes.toString(Charsets.UTF_8)).toByteArray(Charsets.UTF_8) else bytes
        },
    )

    private fun Deck.retag(from: String, to: String): Deck = withPart(CONTENT_TYPES_PART) { it.replace(from, to) }

    private fun sample() = pptx {
        slideSize(12_192_000, 6_858_000)
        layout("Title and Content") { placeholder(Type.TITLE, idx = 0) }
        slide { title("Hello") }
    }

    @Test
    fun `a fixture deck opens with its main part and its slide`() {
        val deck = sample()

        val pkg = read(deck)

        assertEquals("ppt/presentation.xml", pkg.mainPart)
        assertTrue(pkg.hasPart("ppt/slides/slide1.xml"))
        assertEquals(deck.parts.getValue("ppt/slides/slide1.xml").size, pkg.part("ppt/slides/slide1.xml")?.size)
        assertEquals(
            "application/vnd.openxmlformats-officedocument.presentationml.slide+xml",
            pkg.contentTypes.of("ppt/slides/slide1.xml"),
        )
        assertTrue(pkg.mediaNames.isEmpty())
        assertEquals(0, pkg.mediaDropped)
    }

    @Test
    fun `the package's own relationships are read from the root rels part`() {
        val pkg = read(sample())

        val office = pkg.relationshipsOf("").single()

        assertEquals("rId1", office.id)
        assertEquals("ppt/presentation.xml", office.target)
        assertEquals("ppt/presentation.xml", pkg.targetOf("", "rId1"))
    }

    @Test
    fun `a part is found whatever case it is stored under`() {
        val deck = Deck(
            LinkedHashMap(sample().parts).apply {
                put("PPT/Presentation.XML", remove("ppt/presentation.xml")!!)
            },
        )

        val pkg = read(deck)

        assertEquals("PPT/Presentation.XML", pkg.mainPart)
        assertNotNull(pkg.part("ppt/presentation.xml"))
        assertNotNull(pkg.part("PPT/PRESENTATION.xml"))
        assertTrue(pkg.hasPart("ppt/PRESENTATION.XML"))
    }

    @Test
    fun `a relative, an absolute and a dot-dot target all resolve to one part`() {
        val deck = pptx {
            layout("Blank")
            slide {
                rel("rId2", REL_IMAGE, "../media/image1.png")
                rel("rId3", REL_IMAGE, "/ppt/media/image1.png")
                rel("rId4", REL_IMAGE, "../media/../media/image1.png")
            }
            part("ppt/media/image1.png", OfficeFixtures.PNG_1X1)
        }

        val pkg = read(deck, temp.newFolder("media"))
        val slide = "ppt/slides/slide1.xml"

        assertEquals("ppt/media/image1.png", pkg.targetOf(slide, "rId2"))
        assertEquals("ppt/media/image1.png", pkg.targetOf(slide, "rId3"))
        assertEquals("ppt/media/image1.png", pkg.targetOf(slide, "rId4"))
    }

    @Test
    fun `a target that climbs above the package is dropped`() {
        val deck = pptx {
            layout("Blank")
            slide {
                rel("rId2", REL_IMAGE, "../../../../etc/passwd")
                rel("rId3", REL_IMAGE, "/../secret.xml")
            }
        }

        val pkg = read(deck)

        assertNull(pkg.relationship("ppt/slides/slide1.xml", "rId2"))
        assertNull(pkg.relationship("ppt/slides/slide1.xml", "rId3"))
    }

    @Test
    fun `an external hyperlink is kept and an external picture is not`() {
        val deck = pptx {
            layout("Blank")
            slide {
                rel("rId2", REL_HYPERLINK, "https://example.com/notes", external = true)
                rel("rId3", REL_IMAGE, "https://example.com/pixel.png", external = true)
                rel("rId4", REL_HYPERLINK, "mailto:someone@example.com", external = true)
            }
        }

        val pkg = read(deck)
        val slide = "ppt/slides/slide1.xml"

        assertEquals("https://example.com/notes", pkg.relationship(slide, "rId2")?.target)
        assertTrue(pkg.relationship(slide, "rId2")!!.external)
        assertEquals("mailto:someone@example.com", pkg.relationship(slide, "rId4")?.target)
        assertNull("an external picture is never followed", pkg.relationship(slide, "rId3"))
        assertTrue(pkg.mediaNames.isEmpty())
    }

    @Test
    fun `a link whose scheme could run something is dropped`() {
        val deck = pptx {
            layout("Blank")
            slide {
                rel("rId2", REL_HYPERLINK, "javascript:alert(1)", external = true)
                rel("rId3", REL_HYPERLINK, "file:///etc/passwd", external = true)
                rel("rId4", REL_HYPERLINK, "content://com.example/x", external = true)
            }
        }

        val pkg = read(deck)
        val slide = "ppt/slides/slide1.xml"

        assertNull(pkg.relationship(slide, "rId2"))
        assertNull(pkg.relationship(slide, "rId3"))
        assertNull(pkg.relationship(slide, "rId4"))
    }

    @Test
    fun `a template, a slideshow and the macro-enabled variants open the same way`() {
        val base = "application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml"
        val variants = listOf(
            "application/vnd.openxmlformats-officedocument.presentationml.template.main+xml",
            "application/vnd.openxmlformats-officedocument.presentationml.slideshow.main+xml",
            "application/vnd.ms-powerpoint.presentation.macroEnabled.main+xml",
            "application/vnd.ms-powerpoint.template.macroEnabled.main+xml",
            "application/vnd.ms-powerpoint.slideshow.macroEnabled.main+xml",
        )

        for (type in variants) {
            val pkg = read(sample().retag(base, type))

            assertEquals(type, pkg.mainPart.let { pkg.contentTypes.of(it) })
            assertTrue(pkg.hasPart("ppt/slides/slide1.xml"))
        }
    }

    @Test
    fun `a write-protected deck opens normally`() {
        val deck = sample().withPart("ppt/presentation.xml") {
            it.replace(
                "</p:presentation>",
                """<p:modifyVerifier algorithmName="SHA-1" hashValue="AAAA" saltValue="BBBB" spinValue="100000"/></p:presentation>""",
            )
        }

        val pkg = read(deck)

        assertEquals("ppt/presentation.xml", pkg.mainPart)
        assertTrue(pkg.hasPart("ppt/slides/slide1.xml"))
    }

    @Test
    fun `macros are never read`() {
        val deck = pptx {
            layout("Blank")
            slide { rel("rId2", REL_VBA, "../vbaProject.bin") }
            part("ppt/vbaProject.bin", byteArrayOf(1, 2, 3, 4))
        }

        val pkg = read(deck)

        assertFalse(pkg.hasPart("ppt/vbaProject.bin"))
        assertTrue(pkg.partNames.none { it.endsWith(".bin", ignoreCase = true) })
        assertTrue(pkg.mediaNames.isEmpty())
    }

    @Test
    fun `media a slide names is written to the cache under its sanitised name`() {
        val dir = temp.newFolder("media")
        val deck = pptx {
            layout("Blank")
            slide { picture(OfficeFixtures.PNG_1X1, 0, 0, 1, 1) }
        }

        val pkg = read(deck, dir)

        assertEquals(setOf("ppt/media/image1.png"), pkg.mediaNames)
        val file = pkg.mediaFile("ppt/media/image1.png")
        assertNotNull(file)
        assertTrue(file!!.exists())
        assertEquals("image1.png", file.name)
        assertEquals(dir, file.parentFile)
        assertArrayEquals(OfficeFixtures.PNG_1X1, file.readBytes())
        assertEquals(0, pkg.mediaDropped)
    }

    @Test
    fun `two media that sanitise to one file name are written apart`() {
        val dir = temp.newFolder("media")
        val other = byteArrayOf(9, 8, 7, 6)
        val deck = pptx {
            layout("Blank")
            slide {
                rel("rId2", REL_IMAGE, "../media/image1.png")
                rel("rId3", REL_IMAGE, "../media/sub/image1.png")
            }
            part("ppt/media/image1.png", OfficeFixtures.PNG_1X1)
            part("ppt/media/sub/image1.png", other)
        }

        val pkg = read(deck, dir)

        assertEquals(setOf("ppt/media/image1.png", "ppt/media/sub/image1.png"), pkg.mediaNames)
        val first = pkg.mediaFile("ppt/media/image1.png")!!
        val second = pkg.mediaFile("ppt/media/sub/image1.png")!!
        assertTrue("the two parts must not share a file", first != second)
        assertArrayEquals(OfficeFixtures.PNG_1X1, first.readBytes())
        assertArrayEquals(other, second.readBytes())
    }

    @Test
    fun `media nothing names is not written`() {
        val dir = temp.newFolder("media")
        val deck = pptx {
            layout("Blank")
            slide { title("no picture here") }
            part("ppt/media/orphan.png", OfficeFixtures.PNG_1X1)
        }

        val pkg = read(deck, dir)

        assertTrue(pkg.mediaNames.isEmpty())
        assertEquals(0, pkg.mediaDropped)
        assertEquals(emptyList<String>(), dir.list()?.toList().orEmpty())
    }

    @Test
    fun `media is dropped, not followed, when there is no cache directory`() {
        val deck = pptx {
            layout("Blank")
            slide { picture(OfficeFixtures.PNG_1X1, 0, 0, 1, 1) }
        }

        val pkg = read(deck, mediaDir = null)

        assertTrue(pkg.mediaNames.isEmpty())
        assertEquals(1, pkg.mediaDropped)
    }

    private companion object {
        const val CONTENT_TYPES_PART = "[Content_Types].xml"

        const val REL_BASE = "http://schemas.openxmlformats.org/officeDocument/2006/relationships/"

        const val REL_IMAGE = REL_BASE + "image"

        const val REL_HYPERLINK = REL_BASE + "hyperlink"

        const val REL_VBA = REL_BASE + "vbaProject"
    }
}
