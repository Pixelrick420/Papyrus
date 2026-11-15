package io.github.pixelrick420.papyrus.viewer.pptx.text

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Grades [LineMetricsModel] against the numbers committed under `reference/`. A failing case names the
 * deck, the case and the line, which is what points at the rule to change (plan §7.3 step 5).
 */
class LineMetricsCalibrationTest {

    private val model = LineMetricsModel.DEFAULT

    @Test
    fun `every committed control deck reproduces its recorded baselines`() {
        val decks = ReferenceDeck.loadAll(REFERENCE)
        assumeTrue("no PowerPoint control deck is committed yet: run the C-LNSP round (plan §7.3)", decks.isNotEmpty())
        for (deck in decks) {
            for (case in deck.cases) {
                val box = model.line(case.sizePt, case.lineSpacing, font = case.fontBox)
                val first = model.spaceBefore(case.sizePt, case.spaceBefore) + box.baselinePt
                val pitch = case.spaceBefore == null && case.spaceAfter == null
                for ((index, line) in case.lines.withIndex()) {
                    if (index > 0 && !pitch) break
                    assertEquals(
                        "${deck.name} ${case.id} line ${index + 1} baseline for '${line.text}'",
                        first + index * box.heightPt,
                        line.baseline,
                        TOLERANCE_PT,
                    )
                }
            }
        }
    }

    @Test
    fun `the appendix A case fixes the single-line constant`() {
        val deck = ReferenceDeck.load(File(REFERENCE, "C-LNSP.json"))
        val case = deck.cases.single()
        val box = model.line(case.sizePt, case.lineSpacing, font = case.fontBox)

        assertEquals("arial-18-lnSpcPct100", case.id)
        assertEquals(1.2, model.singleLineEm, EPSILON)
        assertEquals("text height", case.textHeightPt, box.heightPt, TOLERANCE_PT)
        assertEquals("first baseline", case.lines.single().baseline, box.baselinePt, TOLERANCE_PT)
    }

    private companion object {

        const val TOLERANCE_PT = 1.0

        const val EPSILON = 1e-9

        val REFERENCE = File("src/test/resources/io/github/pixelrick420/papyrus/viewer/pptx/reference")
    }
}
