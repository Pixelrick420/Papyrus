package io.github.pixelrick420.papyrus.viewer.pptx.text

import kotlin.math.floor

/** A tab stop in points from the paragraph's left margin, with the alignment the text after it takes. */
internal class ResolvedTab(val positionPt: Double, val alignment: TextTabAlignment)

/** Resolves `a:defTabSz` and `a:tab` into the next stop a tab jumps to (plan §4.7 step 7). */
internal class TabStops(
    defaultSizePt: Double = DEFAULT_TAB_SIZE_PT,
    stops: List<TextTabStop> = emptyList(),
) {

    private val pitchPt = defaultSizePt.takeIf { it > 0.0 } ?: DEFAULT_TAB_SIZE_PT

    private val custom = stops
        .map { ResolvedTab(it.positionEmu / TextLayoutEngine.EMU_PER_POINT, it.alignment) }
        .sortedBy { it.positionPt }

    /** The next stop after [xPt]: the next custom stop if one remains, else the next default pitch. */
    fun stopAt(xPt: Double): ResolvedTab {
        val nextCustom = custom.firstOrNull { it.positionPt > xPt }
        if (nextCustom != null) return nextCustom
        return ResolvedTab((floor(xPt / pitchPt) + 1.0) * pitchPt, TextTabAlignment.LEFT)
    }

    companion object {

        const val DEFAULT_TAB_SIZE_PT = 72.0

        const val DEFAULT_TAB_SIZE_EMU = DEFAULT_TAB_SIZE_PT * 12_700.0

        val DEFAULT = TabStops()
    }
}
