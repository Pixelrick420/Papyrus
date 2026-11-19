package io.github.pixelrick420.papyrus.viewer

import io.github.pixelrick420.papyrus.viewer.pptx.PptxLimits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// The values are the plan's proposed starting points; S6 and S7 may tune them. A limit that a parser consumes is
// also exercised at its boundary in PptxPackageRobustnessTest - these pin the ones no parser reads yet.
class PptxLimitsTest {

    @Test
    fun `every limit holds the value the plan proposes`() {
        val limits = PptxLimits()

        assertEquals(10_000, limits.maxEntries)
        assertEquals(64L * 1024 * 1024, limits.maxEntryBytes)
        assertEquals(512L * 1024 * 1024, limits.maxTotalBytes)
        assertEquals(100, limits.maxRatio)
        assertEquals(1024L * 1024, limits.ratioFloorBytes)

        assertEquals(32 * 1024 * 1024, limits.maxXmlPartBytes)
        assertEquals(128L * 1024 * 1024, limits.maxXmlBytes)
        assertEquals(500_000, limits.maxXmlElements)
        assertEquals(200, limits.maxXmlDepth)

        assertEquals(3_000, limits.maxSlides)
        assertEquals(10_000, limits.maxShapes)
        assertEquals(32, limits.maxGroupDepth)
        assertEquals(500_000, limits.maxTextChars)
        assertEquals(20_000, limits.maxParagraphs)
        assertEquals(64, limits.maxGradientStops)
        assertEquals(16, limits.maxDashEntries)

        assertEquals(400, limits.maxImages)
        assertEquals(32 * 1024 * 1024, limits.maxImageBytes)
        assertEquals(160L * 1024 * 1024, limits.maxMediaBytes)
        assertEquals(64_000_000L, limits.maxImagePixels)

        assertEquals(1_024, limits.maxGuideEvaluations)
        assertEquals(256, limits.maxPaths)
        assertEquals(8_192, limits.maxPathCommands)

        assertEquals(200, limits.maxTableColumns)
        assertEquals(2_000, limits.maxTableRows)
        assertEquals(50_000, limits.maxTableCells)

        assertEquals(100, limits.maxChartSeries)
        assertEquals(50_000, limits.maxChartPoints)

        assertEquals(16_384, limits.maxRenderEdgePx)
        assertEquals(56, limits.maxSlideEdgeInches)
    }

    @Test
    fun `a limit is never zero or negative`() {
        val limits = PptxLimits()

        val values = listOf(
            limits.maxEntries.toLong(), limits.maxEntryBytes, limits.maxTotalBytes, limits.maxRatio.toLong(),
            limits.ratioFloorBytes, limits.maxXmlPartBytes.toLong(), limits.maxXmlBytes,
            limits.maxXmlElements.toLong(), limits.maxXmlDepth.toLong(), limits.maxSlides.toLong(),
            limits.maxShapes.toLong(), limits.maxGroupDepth.toLong(), limits.maxTextChars.toLong(),
            limits.maxParagraphs.toLong(), limits.maxGradientStops.toLong(), limits.maxDashEntries.toLong(),
            limits.maxImages.toLong(), limits.maxImageBytes.toLong(), limits.maxMediaBytes, limits.maxImagePixels,
            limits.maxGuideEvaluations.toLong(), limits.maxPaths.toLong(), limits.maxPathCommands.toLong(),
            limits.maxTableColumns.toLong(), limits.maxTableRows.toLong(), limits.maxTableCells.toLong(),
            limits.maxChartSeries.toLong(), limits.maxChartPoints.toLong(), limits.maxRenderEdgePx.toLong(),
            limits.maxSlideEdgeInches.toLong(),
        )

        assertTrue("a limit of zero would refuse every deck", values.all { it > 0 })
    }

    @Test
    fun `the limits that pair with another stay ordered`() {
        val limits = PptxLimits()

        assertTrue("one part cannot exceed all the parts", limits.maxXmlPartBytes <= limits.maxXmlBytes)
        assertTrue("one image cannot exceed all the media", limits.maxImageBytes <= limits.maxMediaBytes)
        assertTrue("one cell is inside the table", limits.maxTableCells <= limits.maxTableColumns * limits.maxTableRows)
        assertTrue("a slide cannot be wider than the package allows", limits.maxSlideEdgeInches > 0)
    }

    @Test
    fun `the render edge is the page source's own height cap`() {
        assertEquals(PdfPageSource.MAX_PAGE_HEIGHT_PX, PptxLimits().maxRenderEdgePx)
    }

    @Test
    fun `the zip limits are the shared ones, so the readers cannot drift apart`() {
        assertEquals(ZipLimits(), PptxLimits().zipLimits)
        assertEquals(
            ZipLimits(maxEntries = 3, maxEntryBytes = 1_000, maxTotalBytes = 2_500, maxRatio = 10, ratioFloorBytes = 100),
            PptxLimits(maxEntries = 3, maxEntryBytes = 1_000, maxTotalBytes = 2_500, maxRatio = 10, ratioFloorBytes = 100).zipLimits,
        )
    }
}
