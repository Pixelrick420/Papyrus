package io.github.pixelrick420.papyrus.viewer.pptx.text

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CalibrationReferenceTest {

    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun `loads the appendix A deck shape`() {
        val deck = ReferenceDeck.load(write(SAMPLE))

        assertEquals("C-LNSP", deck.name)
        assertEquals("16.x", deck.powerpointBuild)
        assertEquals("PowerPoint", deck.oracle)

        val case = deck.cases.single()
        assertEquals("arial-18-lnSpcPct100", case.id)
        assertEquals("Arial", case.font)
        assertEquals(18.0, case.sizePt, EPSILON)
        assertEquals(100.0, case.lineSpacing?.percent!!, EPSILON)
        assertNull(case.lineSpacing?.points)
        assertNull(case.spaceBefore)
        assertNull(case.spaceAfter)
        assertEquals(21.6, case.textHeightPt, EPSILON)

        val line = case.lines.single()
        assertEquals("The quick brown fox", line.text)
        assertEquals(7.2, line.x, EPSILON)
        assertEquals(21.6, line.baseline, EPSILON)
        assertEquals(164.3, line.width, EPSILON)
    }

    @Test
    fun `reads point spacing and the oracle that produced the numbers`() {
        val deck = ReferenceDeck.load(
            write(
                """
                {
                  "deck": "C-PARA",
                  "powerpointBuild": "16.x",
                  "oracle": "LibreOffice 25.2",
                  "cases": [
                    {
                      "id": "arial-18-spcBef200",
                      "font": "Arial",
                      "sizePt": 18,
                      "lnSpcPts": 24,
                      "spcBefPct": 200,
                      "spcAftPts": 12,
                      "lines": [],
                      "textHeightPt": 0
                    }
                  ]
                }
                """.trimIndent(),
            ),
        )

        assertEquals("LibreOffice 25.2", deck.oracle)
        val case = deck.cases.single()
        assertEquals(24.0, case.lineSpacing?.points!!, EPSILON)
        assertEquals(200.0, case.spaceBefore?.percent!!, EPSILON)
        assertEquals(12.0, case.spaceAfter?.points!!, EPSILON)
    }

    @Test
    fun `loads every json in a directory in name order`() {
        val directory = folder.newFolder("reference")
        File(directory, "C-PARA.json").writeText(SAMPLE.replace("C-LNSP", "C-PARA"))
        File(directory, "C-LNSP.json").writeText(SAMPLE)
        File(directory, "notes.txt").writeText("ignored")

        assertEquals(listOf("C-LNSP", "C-PARA"), ReferenceDeck.loadAll(directory).map { it.name })
    }

    private fun write(text: String): File = folder.newFile().apply { writeText(text) }

    private companion object {

        const val EPSILON = 1e-9

        val SAMPLE = """
            {
              "deck": "C-LNSP",
              "powerpointBuild": "16.x",
              "cases": [
                {
                  "id": "arial-18-lnSpcPct100",
                  "font": "Arial",
                  "sizePt": 18,
                  "lnSpcPct": 100,
                  "lines": [
                    {
                      "text": "The quick brown fox",
                      "x": 7.2,
                      "baseline": 21.6,
                      "width": 164.3
                    }
                  ],
                  "textHeightPt": 21.6
                }
              ]
            }
        """.trimIndent()
    }
}
