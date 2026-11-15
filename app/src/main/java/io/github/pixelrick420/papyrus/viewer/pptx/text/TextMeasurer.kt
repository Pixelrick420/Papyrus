package io.github.pixelrick420.papyrus.viewer.pptx.text

/** Shaping features a run turns on; PowerPoint kerns only below the run's `kern` size. */
internal data class FontFeatures(val kerning: Boolean = false, val ligatures: Boolean = false)

/** A font's ascent and descent in points at one size; [descentPt] is positive. */
internal class FontMetricsPt(
    val ascentPt: Double,
    val descentPt: Double,
    val lineGapPt: Double = 0.0,
) {

    /** The em fractions [LineMetricsModel] takes, at the size these metrics were read at. */
    fun emBox(sizePt: Double): FontEmBox =
        if (sizePt <= 0.0 || ascentPt + descentPt <= 0.0) {
            FontEmBox.FALLBACK
        } else {
            FontEmBox(ascentPt / sizePt, descentPt / sizePt)
        }
}

/** A typeface resolved through [FontSubstitution] plus the weight and slant the run asks for. */
internal class ResolvedFont(
    val choice: FontChoice,
    val bold: Boolean = false,
    val italic: Boolean = false,
)

/**
 * Measures text in points, so layout never depends on zoom or density (plan §4.1).
 *
 * Production draws with [PaintTextMeasurer]; JVM tests use AWT on the same bundled TTFs.
 */
internal interface TextMeasurer {

    fun metrics(font: ResolvedFont, sizePt: Float): FontMetricsPt

    fun advance(
        font: ResolvedFont,
        sizePt: Float,
        text: CharSequence,
        start: Int,
        end: Int,
        rtl: Boolean,
        features: FontFeatures = FontFeatures(),
    ): Float

    /** The next offset at or past [maxAdvance]; the filler steps back to the last one that fits. */
    fun offsetForAdvance(
        font: ResolvedFont,
        sizePt: Float,
        text: CharSequence,
        start: Int,
        end: Int,
        rtl: Boolean,
        features: FontFeatures = FontFeatures(),
        maxAdvance: Float,
    ): Int

    /** False when [font] has no glyph for [codePoint], which is where a fallback face has to take over. */
    fun hasGlyph(font: ResolvedFont, codePoint: Int): Boolean = true
}
