package io.github.pixelrick420.papyrus.viewer.pptx.text

/** A measurer with one width per character, so alignment and tab geometry have exact expectations. */
internal class BlockMeasurer(
    private val charPt: Double = 10.0,
    private val spacePt: Double = 5.0,
) : TextMeasurer {

    override fun metrics(font: ResolvedFont, sizePt: Float): FontMetricsPt =
        FontMetricsPt(ascentPt = sizePt * 0.8, descentPt = sizePt * 0.2)

    override fun advance(
        font: ResolvedFont,
        sizePt: Float,
        text: CharSequence,
        start: Int,
        end: Int,
        rtl: Boolean,
        features: FontFeatures,
        letterSpacingEm: Float,
    ): Float = (start until end).sumOf { widthOf(text[it]) + letterSpacingEm * sizePt }.toFloat()

    override fun offsetForAdvance(
        font: ResolvedFont,
        sizePt: Float,
        text: CharSequence,
        start: Int,
        end: Int,
        rtl: Boolean,
        features: FontFeatures,
        letterSpacingEm: Float,
        maxAdvance: Float,
    ): Int {
        var width = 0.0
        var offset = start
        while (offset < end) {
            width += widthOf(text[offset]) + letterSpacingEm * sizePt
            offset++
            if (width >= maxAdvance) return offset
        }
        return end
    }

    private fun widthOf(char: Char): Double = if (char == ' ') spacePt else charPt
}
