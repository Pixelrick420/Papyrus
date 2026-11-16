package io.github.pixelrick420.papyrus.viewer.pptx.text

import java.awt.Font
import java.awt.font.FontRenderContext
import java.io.File

/**
 * [TextMeasurer] on AWT over the same bundled TTFs the app ships. Spike S2 found AWT's unhinted
 * advances agree with `Paint`'s, so JVM tests break lines on real metrics.
 */
internal class AwtTextMeasurer(private val pixelsPerPoint: Double = 1.0) : TextMeasurer {

    private val context = FontRenderContext(null, true, true)

    private val faces = HashMap<String, Font>()

    private val tables = LazyCache<String, ByteArray>()

    override fun metrics(font: ResolvedFont, sizePt: Float): FontMetricsPt {
        val metrics = sized(font, sizePt).getLineMetrics(SAMPLE, context)
        return FontMetricsPt(
            ascentPt = metrics.ascent / pixelsPerPoint,
            descentPt = metrics.descent / pixelsPerPoint,
            lineGapPt = metrics.leading / pixelsPerPoint,
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
    ): Float = if (start >= end) {
        0f
    } else {
        val base = glyphs(font, sizePt, text, start, end).logicalBounds.width / pixelsPerPoint
        (base + letterSpacingEm * sizePt * codePoints(text, start, end)).toFloat()
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
        var offset = start
        while (offset < end) {
            offset += Character.charCount(Character.codePointAt(text, offset))
            if (offset >= end ||
                advance(font, sizePt, text, start, offset, rtl, features, letterSpacingEm) >= maxAdvance
            ) {
                return offset
            }
        }
        return end
    }

    override fun hasGlyph(font: ResolvedFont, codePoint: Int): Boolean = face(font).canDisplay(codePoint)

    override fun decorations(font: ResolvedFont, sizePt: Float): FontDecorations =
        sfnt(font)?.let { FontDecorations.of(it, sizePt.toDouble()) }
            ?: FontDecorations.fallback(sizePt.toDouble())

    private fun sfnt(font: ResolvedFont): SfntMetrics? {
        val choice = font.choice as? FontChoice.Bundled ?: return null
        val asset = choice.font.face(FaceStyle.of(font.bold, font.italic)).asset
        return tables.get(asset) { path -> runCatching { File("src/main/assets/$path").readBytes() }.getOrNull() }
            ?.let(SfntMetrics::parse)
    }

    private fun codePoints(text: CharSequence, start: Int, end: Int): Int {
        var count = 0
        var offset = start
        while (offset < end) {
            offset += Character.charCount(Character.codePointAt(text, offset))
            count++
        }
        return count
    }

    private fun glyphs(font: ResolvedFont, sizePt: Float, text: CharSequence, start: Int, end: Int) =
        sized(font, sizePt).createGlyphVector(context, text.subSequence(start, end).toString())

    private fun face(font: ResolvedFont): Font = faces.getOrPut(key(font)) {
        val choice = font.choice
        val face = when (choice) {
            is FontChoice.Bundled -> choice.font.face(FaceStyle.of(font.bold, font.italic))
            is FontChoice.System -> null
            is FontChoice.Platform -> null
        }
        val file = File("src/main/assets/${face?.asset ?: FALLBACK_ASSET}")
        val base = Font.createFont(Font.TRUETYPE_FONT, file)
        if (face != null && face.synthetic) base.deriveFont(style(font)) else base
    }

    private fun sized(font: ResolvedFont, sizePt: Float): Font =
        face(font).deriveFont((sizePt * pixelsPerPoint).toFloat())

    private fun style(font: ResolvedFont): Int =
        (if (font.bold) Font.BOLD else Font.PLAIN) or (if (font.italic) Font.ITALIC else Font.PLAIN)

    private fun key(font: ResolvedFont): String {
        val choice = font.choice
        val name = when (choice) {
            is FontChoice.Bundled -> choice.font.face(FaceStyle.of(font.bold, font.italic)).asset
            is FontChoice.System -> choice.family
            is FontChoice.Platform -> choice.requested ?: FontSubstitution.SYSTEM_SANS
        }
        return "$name:${font.bold}:${font.italic}"
    }

    private companion object {

        const val SAMPLE = "Ag"

        const val FALLBACK_ASSET = "ooxml/fonts/metric/PptxSans-Regular.ttf"
    }
}
