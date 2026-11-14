package io.github.pixelrick420.papyrus.viewer

import io.github.pixelrick420.papyrus.viewer.pptx.SlideTextExtractor
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Measures the text path on a generated deck. Device render and APK numbers cannot be taken on the JVM, so this
 * records only the parts it can: package size, first-slide text time, per-slide text time, heap delta and characters.
 */
class PptxBenchmarkTest {

    @Test
    fun `text extraction on a generated 100-slide deck`() {
        val extractor = SlideTextExtractor(OfficeFixtures::parser)
        val first = deck(1).toBytes()
        val full = deck(SLIDES).toBytes()

        repeat(WARM_UPS) {
            extractor.extract(OfficeFixtures.streamFactory(first))
            extractor.extract(OfficeFixtures.streamFactory(full))
        }

        val (_, firstMs) = timed { extractor.extract(OfficeFixtures.streamFactory(first)) }
        val before = usedHeap()
        val (slides, totalMs) = timed { extractor.extract(OfficeFixtures.streamFactory(full)) }
        val after = usedHeap()

        val chars = slides.sumOf { it.length }
        println(
            "PPTX text benchmark: slides=$SLIDES deck=${full.size}B firstSlide=${firstMs}ms " +
                "perSlide=${"%.3f".format(totalMs / SLIDES)}ms heapDelta=${after - before}B chars=$chars",
        )
        assertEquals(SLIDES, slides.size)
    }

    private fun deck(slides: Int): Deck = pptx {
        layout("Title and Content")
        repeat(slides) { index ->
            slide {
                title("Slide ${index + 1}")
                body {
                    para("First point on slide ${index + 1}")
                    para("Second point on slide ${index + 1}")
                    para("Third point on slide ${index + 1}")
                }
            }
        }
    }

    private fun usedHeap(): Long = Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }

    private inline fun <T> timed(block: () -> T): Pair<T, Double> {
        val start = System.nanoTime()
        val result = block()
        return result to (System.nanoTime() - start) / 1_000_000.0
    }

    private companion object {
        const val SLIDES = 100

        const val WARM_UPS = 2
    }
}
