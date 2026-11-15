package io.github.pixelrick420.papyrus.viewer.pptx.text

/** A line's box in points: [heightPt] tall, with its baseline [baselinePt] below the top. */
internal data class LineBox(val heightPt: Double, val baselinePt: Double)

/** The first-baseline formulas the sources report (plan §3.3 #6); calibration picks one. */
internal enum class FirstBaselineRule {
    /** Split the box by the font's `usWinAscent`/`usWinDescent`. */
    ASCENT_SHARE,

    /** Centre the ascent-plus-descent band in the line, so the extra leading sits half above and half below. */
    BALANCED_LEADING,

    /** Put all the extra leading above the baseline, which is where a first line's ascent starts from. */
    LEADING_ABOVE,
}

/**
 * PowerPoint's line-height, paragraph-spacing and baseline rules, isolated so a calibration round
 * changes exactly one file (plan §4.7 step 6).
 *
 * Sizes are points and arrive already scaled by autofit, which is what makes `spcPct` follow the
 * font scale while `spcPts` stays an absolute pitch (plan §3.3 #5).
 */
internal class LineMetricsModel(
    val singleLineEm: Double = SINGLE_LINE_EM,
    val firstBaselineRule: FirstBaselineRule = FirstBaselineRule.ASCENT_SHARE,
    val fallbackAscentEm: Double = FALLBACK_ASCENT_EM,
    val fallbackDescentEm: Double = FALLBACK_DESCENT_EM,
) {

    /** The box one line occupies; [largestSizePt] is the largest run size on that line. */
    fun line(
        largestSizePt: Double,
        spacing: TextSpacing? = null,
        fontAlignment: TextFontAlignment? = null,
        metrics: SfntMetrics? = null,
    ): LineBox {
        val ascent = (metrics?.ascentEm ?: fallbackAscentEm) * largestSizePt
        val descent = (metrics?.descentEm ?: fallbackDescentEm) * largestSizePt
        val height = heightPt(largestSizePt, spacing)
        val baseline = when (fontAlignment) {
            TextFontAlignment.TOP -> ascent
            TextFontAlignment.CENTER -> centred(height, ascent, descent)
            TextFontAlignment.BOTTOM -> height - descent
            else -> when (firstBaselineRule) {
                FirstBaselineRule.ASCENT_SHARE -> height * ascent / (ascent + descent)
                FirstBaselineRule.BALANCED_LEADING -> centred(height, ascent, descent)
                FirstBaselineRule.LEADING_ABOVE -> height
            }
        }
        return LineBox(height, baseline)
    }

    /** `spcBef` before the first line, and between two paragraphs on the same slide. */
    fun spaceBefore(sizePt: Double, spacing: TextSpacing?): Double = spacingPt(sizePt, spacing)

    /** `spcAft` after a paragraph, before whatever follows it. */
    fun spaceAfter(sizePt: Double, spacing: TextSpacing?): Double = spacingPt(sizePt, spacing)

    private fun heightPt(largestSizePt: Double, spacing: TextSpacing?): Double {
        val natural = singleLineEm * largestSizePt
        return spacing?.points ?: spacing?.percent?.let { natural * it / FULL_PERCENT } ?: natural
    }

    private fun spacingPt(sizePt: Double, spacing: TextSpacing?): Double =
        spacing?.points ?: spacing?.percent?.let { singleLineEm * sizePt * it / FULL_PERCENT } ?: 0.0

    private fun centred(height: Double, ascent: Double, descent: Double): Double =
        (height + ascent - descent) / 2

    companion object {

        /**
         * The defaults the sources report (plan §3.3 #3 to #6), not yet measured against a PowerPoint
         * build: the C-LNSP and C-PARA rounds are what freeze them.
         */
        const val SINGLE_LINE_EM = 1.2

        const val FALLBACK_ASCENT_EM = 0.8

        const val FALLBACK_DESCENT_EM = 0.2

        private const val FULL_PERCENT = 100.0

        val DEFAULT = LineMetricsModel()
    }
}
