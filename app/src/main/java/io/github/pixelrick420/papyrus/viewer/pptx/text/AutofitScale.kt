package io.github.pixelrick420.papyrus.viewer.pptx.text

import kotlin.math.roundToInt

/** The stored autofit percentages as the layout applies them (plan §4.7 step 8). */
internal class AutofitScale private constructor(
    val sizeFactor: Double,
    val lineSpacingReductionPct: Double,
) {

    /** A run's size at the stored scale; PowerPoint's recomputed sizes are whole points. */
    fun size(sizePt: Double): Double =
        if (sizeFactor == 1.0) sizePt else (sizePt * sizeFactor).roundToInt().coerceAtLeast(1).toDouble()

    /** `lnSpcReduction` shortens a percent line spacing; a point spacing stays absolute. */
    fun lineSpacing(spacing: TextSpacing?): TextSpacing? {
        val percent = spacing?.percent ?: return spacing
        return TextSpacing((percent - lineSpacingReductionPct).coerceAtLeast(0.0), spacing.points)
    }

    companion object {

        val NONE = AutofitScale(1.0, 0.0)

        fun of(autofit: TextAutofit): AutofitScale = when (autofit) {
            is TextAutofit.Normal -> AutofitScale(autofit.fontScale / PERCENT, autofit.lineSpacingReduction)
            else -> NONE
        }

        private const val PERCENT = 100.0
    }
}
