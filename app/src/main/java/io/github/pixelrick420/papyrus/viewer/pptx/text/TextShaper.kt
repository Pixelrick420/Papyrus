package io.github.pixelrick420.papyrus.viewer.pptx.text

/** A paragraph's characters flattened out of its runs, with the style each one carries. */
internal class ParagraphText(
    val text: String,
    val styles: List<TextRunProperties>,
    /** Offsets a `a:br` forces a new line at; one can sit at the very start or end of the text. */
    val breaks: List<Int>,
    /** The paragraph mark's style, which sizes a paragraph with no runs. */
    val endStyle: TextRunProperties,
    /** True for characters synthesised from a lowercase small-caps letter, which draw at the reduced size. */
    val smallCaps: List<Boolean> = List(text.length) { false },
)

/** A stretch of text one [ResolvedFont] shapes without a font or direction change. */
internal class ShapedRun(
    val start: Int,
    val end: Int,
    val level: Int,
    val font: ResolvedFont,
    val sizePt: Float,
    val properties: TextRunProperties,
) {

    val rtl: Boolean get() = level % 2 == 1

    val features: FontFeatures get() = properties.features(sizePt.toDouble())

    val letterSpacingEm: Float get() = properties.letterSpacingEm(sizePt.toDouble())
}

/**
 * Flattens a paragraph into per-character styles, then splits it where the resolved font, the size,
 * the style or the platform's glyph coverage changes, and where bidi changes direction (§4.7 step 2–3).
 */
internal class TextShaper(private val slots: FontSlots = FontSlots()) {

    fun flatten(paragraph: TextParagraph): ParagraphText {
        val text = StringBuilder()
        val styles = ArrayList<TextRunProperties>()
        val breaks = ArrayList<Int>()
        val smallCaps = ArrayList<Boolean>()
        for (run in paragraph.runs) {
            when (run) {
                is TextRun.Text -> append(text, styles, smallCaps, run.text, run.properties)
                is TextRun.Field -> append(text, styles, smallCaps, run.text, run.properties)
                is TextRun.Break -> breaks += text.length
            }
        }
        return ParagraphText(text.toString(), styles, breaks, paragraph.endProperties, smallCaps)
    }

    fun shape(
        text: ParagraphText,
        baseRtl: Boolean,
        scale: AutofitScale = AutofitScale.NONE,
        hasGlyph: (ResolvedFont, Int) -> Boolean = { _, _ -> true },
    ): List<ShapedRun> {
        val faces = facesOf(text, hasGlyph, scale)
        if (faces.isEmpty()) return emptyList()
        val runs = ArrayList<ShapedRun>(faces.size)
        for (direction in Bidi.runs(text.text, baseRtl)) {
            for (face in faces) {
                val start = maxOf(face.start, direction.start)
                val end = minOf(face.end, direction.end)
                if (end <= start) continue
                runs += ShapedRun(start, end, direction.level, face.font, face.sizePt, face.properties)
            }
        }
        return runs
    }

    private fun append(
        text: StringBuilder,
        styles: ArrayList<TextRunProperties>,
        smallCaps: ArrayList<Boolean>,
        value: String,
        properties: TextRunProperties,
    ) {
        for (char in value) {
            val synthesised = properties.caps == TextCaps.SMALL && char.isLowerCase()
            val drawn = if (properties.caps == TextCaps.ALL || synthesised) Character.toUpperCase(char) else char
            text.append(drawn)
            styles += properties
            smallCaps += synthesised
        }
    }

    private fun facesOf(
        text: ParagraphText,
        hasGlyph: (ResolvedFont, Int) -> Boolean,
        scale: AutofitScale,
    ): List<Face> {
        val faces = ArrayList<Face>()
        var index = 0
        var previousMissing = false
        while (index < text.text.length) {
            val properties = text.styles[index]
            val codePoint = Character.codePointAt(text.text, index)
            val length = Character.charCount(codePoint)
            val font = slots.resolve(properties, codePoint)
            val sizePt = (scale.size(properties.sizePt) * capsScale(text.smallCaps, index)).toFloat()
            val missing = !hasGlyph(font, codePoint)
            val last = faces.lastOrNull()
            if (last == null || last.properties !== properties || last.font !== font ||
                last.sizePt != sizePt || missing || previousMissing
            ) {
                faces += Face(index, index + length, font, sizePt, properties)
            } else {
                last.end = index + length
            }
            previousMissing = missing
            index += length
        }
        return faces
    }

    /** Small caps are synthesised as uppercase at a reduced size, since DrawingML has no such feature. */
    private fun capsScale(smallCaps: List<Boolean>, index: Int): Double =
        if (smallCaps.getOrElse(index) { false }) SMALL_CAPS_SCALE else 1.0

    private class Face(
        val start: Int,
        var end: Int,
        val font: ResolvedFont,
        val sizePt: Float,
        val properties: TextRunProperties,
    )

    private companion object {

        const val SMALL_CAPS_SCALE = 0.8
    }
}
