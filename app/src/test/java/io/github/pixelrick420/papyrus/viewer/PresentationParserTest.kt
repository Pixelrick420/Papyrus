package io.github.pixelrick420.papyrus.viewer

import io.github.pixelrick420.papyrus.viewer.pptx.OoxmlNamespace
import io.github.pixelrick420.papyrus.viewer.pptx.PptxFormatException
import io.github.pixelrick420.papyrus.viewer.pptx.PptxLimits
import io.github.pixelrick420.papyrus.viewer.pptx.PresentationInfo
import io.github.pixelrick420.papyrus.viewer.pptx.PresentationParser
import io.github.pixelrick420.papyrus.viewer.pptx.SlideSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PresentationParserTest {

    private val p14 = OoxmlNamespace.P14
    private val mc = OoxmlNamespace.MC

    private fun parse(xml: String, limits: PptxLimits = PptxLimits()): PresentationInfo =
        PresentationParser(OfficeFixtures::parser, limits)
            .parse(xml.trimIndent().toByteArray(), "presentation.xml")

    private fun parse(deck: Deck, limits: PptxLimits = PptxLimits()): PresentationInfo =
        PresentationParser(OfficeFixtures::parser, limits)
            .parse(deck.parts.getValue("ppt/presentation.xml"), "presentation.xml")

    private fun presentation(
        body: String,
        firstSlideNum: Int? = null,
        sections: String = "",
    ): String {
        val first = firstSlideNum?.let { """ firstSlideNum="$it"""" }.orEmpty()
        return """
            <p:presentation xmlns:p="${OoxmlNamespace.P}" xmlns:r="${OoxmlNamespace.R}"
                xmlns:a="${OoxmlNamespace.A}" xmlns:p14="$p14" xmlns:mc="$mc"
                mc:Ignorable="p14"$first>$body$sections</p:presentation>
        """
    }

    @Test
    fun `a fixture deck's presentation reads its size, slides and first slide number`() {
        val info = parse(
            pptx {
                layout("Blank")
                slide {}
                slide {}
            },
        )

        assertEquals(12_192_000L, info.size.widthEmu)
        assertEquals(6_858_000L, info.size.heightEmu)
        assertEquals(2, info.slides.size)
        assertEquals(256L, info.slides[0].id)
        assertEquals("rId4", info.slides[0].relationshipId)
        assertEquals(257L, info.slides[1].id)
        assertEquals("rId5", info.slides[1].relationshipId)
        assertEquals(1, info.firstSlideNumber)
        assertTrue(info.sections.isEmpty())
        assertNotNull(info.defaultTextStyle)
        assertEquals(9, info.defaultTextStyle?.children?.size)
    }

    @Test
    fun `a deck's own first slide number is kept`() {
        val info = parse(
            pptx {
                layout("Blank")
                slide {}
                firstSlideNumber(5)
            },
        )

        assertEquals(5, info.firstSlideNumber)
    }

    @Test
    fun `sections keep their name, id and slides in presentation order`() {
        val info = parse(
            pptx {
                layout("Blank")
                slide {}
                slide {}
                slide {}
                section("Intro", 0)
                section("Body", 1, 2)
            },
        )

        assertEquals(2, info.sections.size)
        assertEquals("Intro", info.sections[0].name)
        assertEquals("{1000}", info.sections[0].id)
        assertEquals(listOf(256L), info.sections[0].slideIds)
        assertEquals("Body", info.sections[1].name)
        assertEquals(listOf(257L, 258L), info.sections[1].slideIds)
    }

    @Test
    fun `a slide edge is clamped at the limit, and the other edge is left alone`() {
        val info = parse(
            pptx {
                slideSize(60_000_000, 10_000_000)
                layout("Blank")
            },
        )

        assertEquals(51_206_400L, info.size.widthEmu)
        assertEquals(10_000_000L, info.size.heightEmu)
        assertEquals(56.0, info.size.widthInches, 0.0)
    }

    @Test
    fun `the slide-edge limit is the one the limits carry`() {
        val info = parse(
            presentation("""<p:sldSz cx="60000000" cy="60000000"/>"""),
            PptxLimits(maxSlideEdgeInches = 10),
        )

        assertEquals(9_144_000L, info.size.widthEmu)
        assertEquals(9_144_000L, info.size.heightEmu)
    }

    @Test
    fun `a presentation with no slide size falls back to the 4 by 3 default`() {
        val info = parse(presentation(""))

        assertEquals(SlideSize.DEFAULT.widthEmu, info.size.widthEmu)
        assertEquals(SlideSize.DEFAULT.heightEmu, info.size.heightEmu)
    }

    @Test
    fun `a slide size of zero or less falls back edge by edge`() {
        val info = parse(presentation("""<p:sldSz cx="1000000" cy="0"/>"""))

        assertEquals(1_000_000L, info.size.widthEmu)
        assertEquals(SlideSize.DEFAULT.heightEmu, info.size.heightEmu)
    }

    @Test
    fun `a presentation with no slide list has no slides`() {
        val info = parse(presentation("""<p:sldSz cx="12192000" cy="6858000"/>"""))

        assertTrue(info.slides.isEmpty())
    }

    @Test
    fun `the slide list is capped at the limit`() {
        val list = """<p:sldIdLst>""" +
            """<p:sldId id="256" r:id="rId4"/>""" +
            """<p:sldId id="257" r:id="rId5"/>""" +
            """<p:sldId id="258" r:id="rId6"/>""" +
            "</p:sldIdLst>"

        val info = parse(presentation(list), PptxLimits(maxSlides = 2))

        assertEquals(2, info.slides.size)
        assertEquals(257L, info.slides[1].id)
    }

    @Test
    fun `a slide entry with no id or no relationship is dropped`() {
        val list = """<p:sldIdLst>""" +
            """<p:sldId r:id="rId4"/>""" +
            """<p:sldId id="257"/>""" +
            """<p:sldId id="not-a-number" r:id="rId6"/>""" +
            """<p:sldId id="258" r:id="rId7"/>""" +
            "</p:sldIdLst>"

        val info = parse(presentation(list))

        assertEquals(1, info.slides.size)
        assertEquals(258L, info.slides[0].id)
        assertEquals("rId7", info.slides[0].relationshipId)
    }

    @Test
    fun `a section slide id that is not a number is dropped`() {
        val sections = """<p14:sectionLst><p14:section name="Intro" id="{1}"><p14:sldIdLst>""" +
            """<p14:sldId id="256"/><p14:sldId id="nope"/></p14:sldIdLst>""" +
            "</p14:section></p14:sectionLst>"

        val info = parse(presentation("""<p:sldIdLst><p:sldId id="256" r:id="rId4"/></p:sldIdLst>""", sections = sections))

        assertEquals(listOf(256L), info.sections.single().slideIds)
    }

    @Test
    fun `a strict presentation reads the same as its transitional twin`() {
        val strict = """
            <p:presentation xmlns:p="http://purl.oclc.org/ooxml/presentationml/main"
                xmlns:r="http://purl.oclc.org/ooxml/officeDocument/relationships">
                <p:sldIdLst><p:sldId id="256" r:id="rId4"/></p:sldIdLst>
                <p:sldSz cx="12192000" cy="6858000"/>
            </p:presentation>
        """

        val info = parse(strict)

        assertEquals(12_192_000L, info.size.widthEmu)
        assertEquals("rId4", info.slides.single().relationshipId)
    }

    @Test
    fun `a part that is not a presentation is refused`() {
        val failure = runCatching {
            parse("""<p:sldSz xmlns:p="${OoxmlNamespace.P}" cx="12192000" cy="6858000"/>""")
        }.exceptionOrNull()

        assertTrue(failure is PptxFormatException)
    }

    @Test
    fun `an empty part is refused`() {
        val failure = runCatching {
            PresentationParser(OfficeFixtures::parser).parse(ByteArray(0), "presentation.xml")
        }.exceptionOrNull()

        assertTrue(failure is PptxFormatException)
    }
}
