package io.github.pixelrick420.papyrus.viewer

import io.github.pixelrick420.papyrus.viewer.pptx.slideLinkTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SlideLinksTest {

    private fun target(
        action: String? = null,
        url: String? = null,
        slide: Int? = null,
        current: Int = 1,
        count: Int = 4,
    ): PdfLinkTarget? = slideLinkTarget(action, url, slide, current, count)

    @Test
    fun `a safe external link leaves the app`() {
        assertEquals(PdfLinkTarget.External("https://example.com"), target(url = "https://example.com"))
        assertEquals(PdfLinkTarget.External("mailto:a@b.c"), target(url = "mailto:a@b.c"))
        assertEquals(PdfLinkTarget.External("tel:+123"), target(url = "tel:+123"))
    }

    @Test
    fun `an unsafe or missing external link is ignored`() {
        assertNull(target(url = "javascript:alert(1)"))
        assertNull(target(url = "file:///etc/passwd"))
        assertNull(target(url = null))
    }

    @Test
    fun `a slide jump scrolls to the related slide`() {
        assertEquals(PdfLinkTarget.Internal(2), target("ppaction://hlinksldjump", slide = 2))
        assertEquals(PdfLinkTarget.Internal(3), target("ppaction://hlinksldjump", slide = 3))
    }

    @Test
    fun `a slide jump that points outside the deck is ignored`() {
        assertNull(target("ppaction://hlinksldjump", slide = 4))
        assertNull(target("ppaction://hlinksldjump", slide = null))
    }

    @Test
    fun `show-jump actions step from the current slide`() {
        assertEquals(PdfLinkTarget.Internal(2), target("ppaction://hlinkshowjump?jump=nextslide", current = 1))
        assertEquals(PdfLinkTarget.Internal(0), target("ppaction://hlinkshowjump?jump=previousslide", current = 1))
        assertEquals(PdfLinkTarget.Internal(0), target("ppaction://hlinkshowjump?jump=firstslide", current = 2))
        assertEquals(PdfLinkTarget.Internal(3), target("ppaction://hlinkshowjump?jump=lastslide", current = 2))
    }

    @Test
    fun `a show-jump past either end is ignored`() {
        assertNull(target("ppaction://hlinkshowjump?jump=nextslide", current = 3))
        assertNull(target("ppaction://hlinkshowjump?jump=previousslide", current = 0))
    }

    @Test
    fun `other actions are ignored even when a relationship is present`() {
        for (action in listOf("ppaction://endshow", "ppaction://program", "ppaction://macro", "ppaction://ole")) {
            assertNull(target(action, url = "https://example.com", slide = 2))
        }
    }

    @Test
    fun `an unknown action is ignored and a blank one still follows its url`() {
        assertNull(target("ppaction://somethingelse", slide = 2))
        assertEquals(PdfLinkTarget.External("https://example.com"), target("  ", url = "https://example.com"))
    }
}
