package io.github.pixelrick420.papyrus.viewer

import io.github.pixelrick420.papyrus.viewer.pptx.LazyDeckScenes
import io.github.pixelrick420.papyrus.viewer.pptx.PptxLimits
import io.github.pixelrick420.papyrus.viewer.pptx.PptxPackage
import io.github.pixelrick420.papyrus.viewer.pptx.PresentationParser
import io.github.pixelrick420.papyrus.viewer.pptx.SceneSize
import io.github.pixelrick420.papyrus.viewer.pptx.StyleResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LazyDeckScenesTest {

    @Test
    fun `a lazy deck resolves slides on demand`() {
        val scenes = provider(deck())

        assertEquals(3, scenes.count)
        assertEquals(SceneSize(12_192_000.0, 6_858_000.0), scenes.size)
        assertEquals(setOf(1), scenes.hidden)
        assertNull(scenes.scene(3))
        assertEquals(listOf("First"), scenes.scene(0)!!.scene.nodes.map { it.name })
        assertEquals(listOf("Second"), scenes.scene(1)!!.scene.nodes.map { it.name })
        assertEquals(listOf("Third"), scenes.scene(2)!!.scene.nodes.map { it.name })
    }

    @Test
    fun `hidden slides are known before resolving and survive a clear`() {
        val scenes = provider(deck())

        assertEquals(setOf(1), scenes.hiddenSnapshot)
        scenes.clear()
        assertEquals(setOf(1), scenes.hiddenSnapshot)
    }

    @Test
    fun `trimming evicts cached scenes without changing the deck`() {
        val scenes = provider(deck())

        scenes.scene(0)
        scenes.trimMemory(evictAll = true)
        assertEquals(listOf("First"), scenes.scene(0)!!.scene.nodes.map { it.name })
        scenes.trimMemory(evictAll = false)
        assertEquals(3, scenes.count)
    }

    private fun provider(deck: Deck): LazyDeckScenes {
        val parser = OfficeFixtures::parser
        val bytes = deck.toBytes()
        val pkg = PptxPackage.read({ bytes.inputStream() }, mediaDir = null, newParserFactory = parser)
        val main = pkg.mainPart
        val presentation = PresentationParser(parser, PptxLimits()).parse(pkg.part(main)!!, main)
        return StyleResolver(parser).lazyScenes(pkg, presentation)
    }

    private fun deck(): Deck = pptx {
        slide { shape(Preset.RECT, 0, 0, 1, 1, name = "First") }
        slide(hidden = true) { shape(Preset.RECT, 0, 0, 1, 1, name = "Second") }
        slide { shape(Preset.RECT, 0, 0, 1, 1, name = "Third") }
    }
}
