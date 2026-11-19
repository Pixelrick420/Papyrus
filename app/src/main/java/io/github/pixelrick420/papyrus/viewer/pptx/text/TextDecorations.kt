package io.github.pixelrick420.papyrus.viewer.pptx.text

import io.github.pixelrick420.papyrus.viewer.pptx.ScenePaint

/** Where one rule sits at one size; a positive [offsetPt] is below the baseline. */
internal class RuleMetrics(val offsetPt: Double, val thicknessPt: Double)

/**
 * The font's own underline and strikeout geometry, read from `post` and `OS/2` rather than from
 * `Paint.underlinePosition`, which only exists from API 29 (plan §4.7).
 */
internal class FontDecorations(val underline: RuleMetrics, val strike: RuleMetrics) {

    companion object {

        private const val UNDERLINE_OFFSET_EM = 0.1

        private const val UNDERLINE_THICKNESS_EM = 0.05

        private const val STRIKEOUT_OFFSET_EM = 0.25

        private const val STRIKEOUT_THICKNESS_EM = 0.05

        fun of(metrics: SfntMetrics, sizePt: Double): FontDecorations = FontDecorations(
            underline = rule(
                position = metrics.underlinePosition,
                thickness = metrics.underlineThickness,
                fallbackOffsetEm = UNDERLINE_OFFSET_EM,
                fallbackThicknessEm = UNDERLINE_THICKNESS_EM,
                metrics = metrics,
                sizePt = sizePt,
            ),
            strike = rule(
                position = metrics.strikeoutPosition,
                thickness = metrics.strikeoutSize,
                fallbackOffsetEm = STRIKEOUT_OFFSET_EM,
                fallbackThicknessEm = STRIKEOUT_THICKNESS_EM,
                metrics = metrics,
                sizePt = sizePt,
            ),
        )

        private fun rule(
            position: Int?,
            thickness: Int?,
            fallbackOffsetEm: Double,
            fallbackThicknessEm: Double,
            metrics: SfntMetrics,
            sizePt: Double,
        ): RuleMetrics {
            val offsetEm = position?.let(metrics::em) ?: fallbackOffsetEm
            val thicknessEm = thickness?.let(metrics::em)?.takeIf { it > 0.0 } ?: fallbackThicknessEm
            return RuleMetrics(-offsetEm * sizePt, thicknessEm * sizePt)
        }

        /** Used for faces that ship no `post` or `OS/2`, so a rule still lands somewhere sane. */
        fun fallback(sizePt: Double): FontDecorations = FontDecorations(
            underline = RuleMetrics(UNDERLINE_OFFSET_EM * sizePt, UNDERLINE_THICKNESS_EM * sizePt),
            strike = RuleMetrics(STRIKEOUT_OFFSET_EM * sizePt, STRIKEOUT_THICKNESS_EM * sizePt),
        )
    }
}

internal enum class TextDecorationKind { UNDERLINE, STRIKE }

/** One engine-drawn underline or strike rule on a line, in points from the frame's left. */
internal class TextDecoration(
    val kind: TextDecorationKind,
    /** The `a:u` value, or the strike variant; the paint pass turns it into dots, dashes or waves. */
    val style: String,
    val leftPt: Double,
    val rightPt: Double,
    val offsetPt: Double,
    val thicknessPt: Double,
    /** `uFill` when it outranks the run's own fill. */
    val paint: ScenePaint? = null,
)
