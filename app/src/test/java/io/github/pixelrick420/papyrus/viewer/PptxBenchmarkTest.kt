package io.github.pixelrick420.papyrus.viewer

import io.github.pixelrick420.papyrus.viewer.pptx.SlideTextExtractor
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Records the §6 text-path baseline on a generated 100-slide deck. The device-only targets (1080 px first-slide and
 * per-slide render time, peak scroll memory and APK delta on low-end and mid-range hardware) are accepted exceptions:
 * the JVM cannot measure them, so they stay with the device harness. Regressions are reviewed, not asserted in CI.
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
        val perSlideMs = totalMs / SLIDES
        val heapDelta = after - before
        println(
            "PPTX text benchmark: slides=$SLIDES deck=${full.size}B firstSlide=${firstMs}ms " +
                "perSlide=${"%.3f".format(perSlideMs)}ms heapDelta=${heapDelta}B chars=$chars",
        )
        println(
            "PPTX §6 baseline: slides=$SLIDES deck=${BASELINE_DECK_BYTES}B firstSlide=${BASELINE_FIRST_MS}ms " +
                "perSlide=${BASELINE_PER_SLIDE_MS}ms heapDelta=${BASELINE_HEAP_DELTA}B chars=$BASELINE_CHARS",
        )
        println(
            "PPTX §6 ratio: deck=${"%.2f".format(full.size.toDouble() / BASELINE_DECK_BYTES)} " +
                "firstSlide=${"%.2f".format(firstMs / BASELINE_FIRST_MS)} " +
                "perSlide=${"%.2f".format(perSlideMs / BASELINE_PER_SLIDE_MS)} " +
                "heapDelta=${"%.2f".format(heapDelta.toDouble() / BASELINE_HEAP_DELTA)}",
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

        const val BASELINE_DECK_BYTES = 98_216

        const val BASELINE_FIRST_MS = 2.262631

        const val BASELINE_PER_SLIDE_MS = 0.389

        const val BASELINE_HEAP_DELTA = 30_408_704L

        const val BASELINE_CHARS = 8_068
    }
}
