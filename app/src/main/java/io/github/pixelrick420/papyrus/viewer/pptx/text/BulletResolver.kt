package io.github.pixelrick420.papyrus.viewer.pptx.text

import io.github.pixelrick420.papyrus.viewer.pptx.Rgba
import io.github.pixelrick420.papyrus.viewer.pptx.ScenePaint
import io.github.pixelrick420.papyrus.viewer.pptx.XNode

/** A bullet as the line layout draws it: beside the paragraph, never inside its text. */
internal sealed interface BulletMarker {
    object None : BulletMarker

    class Symbol(
        /** The character to draw, when the file's character has a Unicode equivalent. */
        val text: String? = null,
        /** Vector geometry for a character with no usable Unicode glyph, drawn in the marker colour. */
        val shape: SymbolShape? = null,
        /** Null when the marker takes the platform chain, as a mapped symbol glyph and `buFontTx` do. */
        val font: String? = null,
        val sizePt: Double = 0.0,
        val color: Rgba? = null,
    ) : BulletMarker

    class Picture(val image: XNode? = null, val sizePt: Double = 0.0, val color: Rgba? = null) : BulletMarker

    /** Generated `a)`, `IV.` or `①` text; never part of the paragraph's own runs. */
    class Number(val text: String, val sizePt: Double = 0.0, val color: Rgba? = null) : BulletMarker
}

/** Where a paragraph's own lines start and the box its marker occupies, in EMU from the frame's left inset. */
internal class BulletMetrics(
    val textLeftEmu: Double,
    val markerLeftEmu: Double,
    val markerRightEmu: Double,
) {

    val hangingEmu: Double get() = markerRightEmu - markerLeftEmu
}

internal class BulletLayout(val marker: BulletMarker, val metrics: BulletMetrics)

/** Resolves `buChar`, `buBlip`, `buClr`, `buSz` and the level indents into the values the layout pass paints. */
internal class BulletResolver(
    private val symbols: SymbolFonts = SymbolFonts(),
    private val numbers: AutoNumbers = AutoNumbers(),
) {

    /**
     * One layout per paragraph, with `buAutoNum` markers counted across the whole body. A single
     * paragraph cannot be numbered on its own, so this is the entry point the layout pass uses.
     */
    fun layouts(body: TextBody, scale: AutofitScale = AutofitScale.NONE): List<BulletLayout> {
        val markers = numbers.markers(body)
        return body.paragraphs.mapIndexed { index, paragraph ->
            val layout = layout(paragraph, scale)
            val number = markers[index] ?: return@mapIndexed layout
            BulletLayout(numberMarker(paragraph, number, scale), layout.metrics)
        }
    }

    fun layout(paragraph: TextParagraph, scale: AutofitScale = AutofitScale.NONE): BulletLayout {
        val run = paragraph.runs.firstOrNull()?.properties ?: paragraph.endProperties
        return BulletLayout(
            marker(paragraph.properties.bullet, scale.size(run.sizePt), (run.fill as? ScenePaint.Solid)?.color),
            metrics(paragraph.level, paragraph.properties),
        )
    }

    fun marker(bullet: TextBullet, baseSizePt: Double, baseColor: Rgba?): BulletMarker = when (bullet) {
        TextBullet.None -> BulletMarker.None
        is TextBullet.Character -> characterMarker(bullet, baseSizePt, baseColor)
        is TextBullet.Numbering -> BulletMarker.None
        is TextBullet.Picture -> BulletMarker.Picture(
            image = bullet.image,
            sizePt = sizeOf(bullet.size, baseSizePt),
            color = bullet.color ?: baseColor,
        )
    }

    fun metrics(level: Int, properties: TextParagraphProperties): BulletMetrics {
        val hasBullet = properties.bullet != TextBullet.None
        val textLeft = properties.marginLeftEmu ?: level.coerceIn(0, MAX_LEVEL - 1) * LEVEL_INDENT_EMU
        val indent = properties.indentEmu ?: 0.0
        if (!hasBullet) return BulletMetrics(textLeft, textLeft + minOf(0.0, indent), textLeft)
        val markerLeft = (textLeft + minOf(0.0, indent)).coerceAtLeast(0.0)
        val markerRight = markerLeft + maxOf(-indent, DEFAULT_HANGING_EMU)
        return BulletMetrics(maxOf(textLeft, markerRight), markerLeft, markerRight)
    }

    private fun numberMarker(
        paragraph: TextParagraph,
        text: String,
        scale: AutofitScale,
    ): BulletMarker.Number {
        val bullet = paragraph.properties.bullet as? TextBullet.Numbering ?: return BulletMarker.Number(text)
        val run = paragraph.runs.firstOrNull()?.properties ?: paragraph.endProperties
        return BulletMarker.Number(
            text = text,
            sizePt = sizeOf(bullet.size, scale.size(run.sizePt)),
            color = bullet.color ?: (run.fill as? ScenePaint.Solid)?.color,
        )
    }

    private fun characterMarker(
        bullet: TextBullet.Character,
        baseSizePt: Double,
        baseColor: Rgba?,
    ): BulletMarker {
        val size = sizeOf(bullet.size, baseSizePt)
        val color = bullet.color ?: baseColor
        return when (val glyph = symbols.glyph(bullet.font, bullet.char)) {
            is SymbolGlyph.Text -> BulletMarker.Symbol(glyph.text, font = null, sizePt = size, color = color)
            is SymbolGlyph.Vector -> BulletMarker.Symbol(shape = glyph.shape, sizePt = size, color = color)
            null -> BulletMarker.Symbol(
                text = bullet.char.takeIf { it.isNotEmpty() },
                font = bullet.font,
                sizePt = size,
                color = color,
            )
        }
    }

    private fun sizeOf(size: TextBulletSize, baseSizePt: Double): Double = when (size) {
        TextBulletSize.Inherit -> baseSizePt
        is TextBulletSize.Percent -> baseSizePt * size.value / 100.0
        is TextBulletSize.Points -> size.value
    }

    private companion object {
        const val LEVEL_INDENT_EMU = 457_200.0
        const val DEFAULT_HANGING_EMU = 228_600.0

        const val MAX_LEVEL = 9
    }
}
