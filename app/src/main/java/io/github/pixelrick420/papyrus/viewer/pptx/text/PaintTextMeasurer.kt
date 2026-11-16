package io.github.pixelrick420.papyrus.viewer.pptx.text

import android.graphics.Paint
import android.graphics.Typeface

/**
 * [TextMeasurer] on `android.graphics.Paint`, unhinted, linear and subpixel so the advances match the
 * font file rather than the device's hinting (plan §4.1).
 */
internal class PaintTextMeasurer(private val catalog: FontCatalog) : TextMeasurer {

    private val paints = HashMap<String, Paint>()

    override fun metrics(font: ResolvedFont, sizePt: Float): FontMetricsPt {
        val metrics = paint(font, sizePt, FontFeatures(), 0f).fontMetrics
        return FontMetricsPt(
            ascentPt = -metrics.ascent.toDouble(),
            descentPt = metrics.descent.toDouble(),
            lineGapPt = metrics.leading.toDouble(),
        )
    }

    override fun advance(
        font: ResolvedFont,
        sizePt: Float,
        text: CharSequence,
        start: Int,
        end: Int,
        rtl: Boolean,
        features: FontFeatures,
        letterSpacingEm: Float,
    ): Float {
        if (start >= end) return 0f
        return paint(font, sizePt, features, letterSpacingEm)
            .getRunAdvance(text, start, end, start, end, rtl, end)
    }

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
        if (start >= end) return start
        val offset = paint(font, sizePt, features, letterSpacingEm)
            .getOffsetForAdvance(text, start, end, start, end, rtl, maxAdvance)
        return offset.coerceIn(start, end)
    }

    override fun hasGlyph(font: ResolvedFont, codePoint: Int): Boolean =
        paint(font, GLYPH_SIZE_PT, FontFeatures(), 0f).hasGlyph(String(Character.toChars(codePoint)))

    override fun decorations(font: ResolvedFont, sizePt: Float): FontDecorations =
        catalog.sfnt(font)?.let { FontDecorations.of(it, sizePt.toDouble()) }
            ?: FontDecorations.fallback(sizePt.toDouble())

    private fun paint(
        font: ResolvedFont,
        sizePt: Float,
        features: FontFeatures,
        letterSpacingEm: Float,
    ): Paint = paints.getOrPut("${keyOf(font)}:$sizePt:$features:$letterSpacingEm") {
        buildPaint(font, sizePt, features, letterSpacingEm)
    }

    private fun buildPaint(
        font: ResolvedFont,
        sizePt: Float,
        features: FontFeatures,
        letterSpacingEm: Float,
    ): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = typefaceOf(font)
        textSize = sizePt
        hinting = Paint.HINTING_OFF
        isLinearText = true
        isSubpixelText = true
        fontFeatureSettings = featureSettings(features)
        letterSpacing = letterSpacingEm
    }

    private fun typefaceOf(font: ResolvedFont): Typeface = when (val choice = font.choice) {
        is FontChoice.Bundled -> catalog.bundled(choice.font, font.bold, font.italic)
        is FontChoice.System -> catalog.system(choice.family, font.bold, font.italic)
        is FontChoice.Platform ->
            catalog.system(choice.requested ?: FontSubstitution.SYSTEM_SANS, font.bold, font.italic)
    }

    private fun keyOf(font: ResolvedFont): String = when (val choice = font.choice) {
        is FontChoice.Bundled -> "asset:${choice.font.family}:${font.bold}:${font.italic}"
        is FontChoice.System -> "family:${choice.family}:${font.bold}:${font.italic}"
        is FontChoice.Platform -> "platform:${choice.requested}:${font.bold}:${font.italic}"
    }

    private fun featureSettings(features: FontFeatures): String =
        "\"kern\" ${if (features.kerning) 1 else 0}, " +
            "\"liga\" ${if (features.ligatures) 1 else 0}, \"clig\" 0"

    private companion object {
        // Glyph coverage does not depend on the size, so one size serves every hasGlyph query.
        const val GLYPH_SIZE_PT = 12f
    }
}
