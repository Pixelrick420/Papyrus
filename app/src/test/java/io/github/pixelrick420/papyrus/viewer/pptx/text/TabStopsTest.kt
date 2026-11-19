package io.github.pixelrick420.papyrus.viewer.pptx.text

import org.junit.Assert.assertEquals
import org.junit.Test

class TabStopsTest {

    @Test
    fun `a later custom stop wins even when a default pitch is nearer`() {
        val stops = TabStops(stops = listOf(stop(342.4)))

        assertEquals(342.4, stops.stopAt(268.0).positionPt, EPSILON)
    }

    @Test
    fun `no custom stops uses the next default pitch`() {
        assertEquals(288.0, TabStops.DEFAULT.stopAt(268.0).positionPt, EPSILON)
    }

    @Test
    fun `past the last custom stop uses the next default pitch`() {
        val stops = TabStops(stops = listOf(stop(342.4)))

        assertEquals(360.0, stops.stopAt(350.0).positionPt, EPSILON)
    }

    @Test
    fun `custom stops keep their alignment`() {
        for (alignment in TextTabAlignment.entries) {
            val stops = TabStops(stops = listOf(stop(342.4, alignment)))

            val resolved = stops.stopAt(268.0)

            assertEquals(342.4, resolved.positionPt, EPSILON)
            assertEquals(alignment, resolved.alignment)
        }
    }

    private fun stop(positionPt: Double, alignment: TextTabAlignment = TextTabAlignment.LEFT) =
        TextTabStop(positionPt * TextLayoutEngine.EMU_PER_POINT, alignment)

    private companion object {

        const val EPSILON = 1e-9
    }
}
