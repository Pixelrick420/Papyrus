package io.github.pixelrick420.papyrus.viewer

import io.github.pixelrick420.papyrus.viewer.pptx.PresentationParser
import io.github.pixelrick420.papyrus.viewer.pptx.slideOutline
import org.junit.Assert.assertEquals
import org.junit.Test

class SlideOutlineTest {

    private fun entries(titles: List<String>, build: DeckBuilder.() -> Unit): List<OutlineEntry> {
        val deck = pptx(build)
        val info = PresentationParser(OfficeFixtures::parser).parse(deck.parts.getValue("ppt/presentation.xml"))
        return slideOutline(info, titles)
    }

    @Test
    fun `every slide is a top-level entry when the deck has no sections`() {
        val outline = entries(listOf("Intro", "", "Results")) {
            layout("Blank")
            slide {}
            slide {}
            slide {}
        }

        assertEquals(listOf(OutlineEntry(0, 0, "Intro"), OutlineEntry(1, 0, "Slide 2"), OutlineEntry(2, 0, "Results")), outline)
    }

    @Test
    fun `sections become parent entries over their slides`() {
        val outline = entries(listOf("Intro", "Body", "Results")) {
            layout("Blank")
            slide {}
            slide {}
            slide {}
            section("Start", 0)
            section("Data", 1, 2)
        }

        assertEquals(
            listOf(
                OutlineEntry(0, 0, "Start"),
                OutlineEntry(1, 0, "Data"),
                OutlineEntry(2, 1, "Results"),
            ),
            outline,
        )
    }

    @Test
    fun `a slide outside every section stays at the top level`() {
        val outline = entries(listOf("Cover", "One", "Two")) {
            layout("Blank")
            slide {}
            slide {}
            slide {}
            section("Body", 1, 2)
        }

        assertEquals(
            listOf(
                OutlineEntry(0, 0, "Cover"),
                OutlineEntry(1, 0, "Body"),
                OutlineEntry(2, 1, "Two"),
            ),
            outline,
        )
    }

    @Test
    fun `a section without a name leaves its slides as top-level entries`() {
        val outline = entries(listOf("One", "Two")) {
            layout("Blank")
            slide {}
            slide {}
            section("", 0, 1)
        }

        assertEquals(listOf(OutlineEntry(0, 0, "One"), OutlineEntry(1, 0, "Two")), outline)
    }

    @Test
    fun `a section id that matches no slide is dropped`() {
        val deck = pptx {
            layout("Blank")
            slide {}
        }
        val info = PresentationParser(OfficeFixtures::parser).parse(deck.parts.getValue("ppt/presentation.xml"))
        val withStray = io.github.pixelrick420.papyrus.viewer.pptx.PresentationInfo(
            size = info.size,
            slides = info.slides,
            sections = listOf(io.github.pixelrick420.papyrus.viewer.pptx.Section("{1}", "Ghost", listOf(999L))),
            defaultTextStyle = info.defaultTextStyle,
            firstSlideNumber = info.firstSlideNumber,
        )

        assertEquals(listOf(OutlineEntry(0, 0, "Slide 1")), slideOutline(withStray, listOf("")))
    }
}
