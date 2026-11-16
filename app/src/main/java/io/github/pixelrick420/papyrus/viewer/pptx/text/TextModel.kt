package io.github.pixelrick420.papyrus.viewer.pptx.text

import io.github.pixelrick420.papyrus.viewer.pptx.Rgba
import io.github.pixelrick420.papyrus.viewer.pptx.ScenePaint
import io.github.pixelrick420.papyrus.viewer.pptx.XNode

/** A `p:txBody` (or table cell `a:txBody`) whose styles have already been cascaded into flat values. */
internal class TextBody(
    val properties: TextBodyProperties = TextBodyProperties(),
    val paragraphs: List<TextParagraph> = emptyList(),
    /** True when the paragraph or character limits cut stored text out of the body. */
    val truncated: Boolean = false,
)

/** The `bodyPr` insets in EMU. */
internal data class TextInsets(val left: Double, val top: Double, val right: Double, val bottom: Double) {

    companion object {
        /** PowerPoint's own `bodyPr` defaults: 0.1 in horizontally, 0.05 in vertically. */
        val DEFAULT = TextInsets(91_440.0, 45_720.0, 91_440.0, 45_720.0)
    }
}

internal enum class TextAnchor(val value: String) {
    TOP("t"),
    CENTER("ctr"),
    BOTTOM("b"),
    JUSTIFIED("just"),
    DISTRIBUTED("dist"),
    ;

    companion object {
        fun of(value: String?): TextAnchor = entries.firstOrNull { it.value == value } ?: TOP
    }
}

internal enum class TextWrap { SQUARE, NONE }

internal enum class TextVertical(val value: String) {
    HORZ("horz"),
    VERT("vert"),
    VERT_270("vert270"),
    EAST_ASIAN_VERTICAL("eaVert"),
    WORD_ART_VERTICAL("wordArtVert"),
    MONGOLIAN_VERTICAL("mongolianVert"),
    ;

    companion object {
        fun of(value: String?): TextVertical = entries.firstOrNull { it.value == value } ?: HORZ
    }
}

internal sealed interface TextAutofit {
    object None : TextAutofit

    /** `normAutofit`: the stored scale percentages, 100.0 meaning unscaled. */
    class Normal(val fontScale: Double, val lineSpacingReduction: Double) : TextAutofit

    /** `spAutoFit`: the shape is resized to the text rather than the text to the shape. */
    object Shape : TextAutofit
}

internal class TextBodyProperties(
    val insets: TextInsets = TextInsets.DEFAULT,
    val anchor: TextAnchor = TextAnchor.TOP,
    val anchorCenter: Boolean = false,
    val wrap: TextWrap = TextWrap.SQUARE,
    val vertical: TextVertical = TextVertical.HORZ,
    /** Clockwise rotation in degrees. */
    val rotation: Double = 0.0,
    val rightToLeftColumns: Boolean = false,
    val columnCount: Int = 1,
    val columnSpacingEmu: Double? = null,
    val autofit: TextAutofit = TextAutofit.None,
    val upright: Boolean = false,
    val compatibleLineSpacing: Boolean = false,
)

internal enum class TextAlignment(val value: String) {
    LEFT("l"),
    CENTER("ctr"),
    RIGHT("r"),
    JUSTIFY("just"),
    JUSTIFY_LOW("justLow"),
    DISTRIBUTED("dist"),
    THAI_DISTRIBUTED("thaiDist"),
    ;

    companion object {
        fun of(value: String?): TextAlignment? = entries.firstOrNull { it.value == value }
    }
}

internal enum class TextFontAlignment(val value: String) {
    AUTO("auto"),
    TOP("t"),
    CENTER("ctr"),
    BOTTOM("b"),
    BASELINE("base"),
    ;

    companion object {
        fun of(value: String?): TextFontAlignment? = entries.firstOrNull { it.value == value }
    }
}

/** A line, before or after spacing: a `spcPct` as a percentage, or a `spcPts` in points. */
internal class TextSpacing(val percent: Double? = null, val points: Double? = null)

internal enum class TextTabAlignment(val value: String) {
    LEFT("l"),
    CENTER("ctr"),
    RIGHT("r"),
    DECIMAL("dec"),
    ;

    companion object {
        fun of(value: String?): TextTabAlignment = entries.firstOrNull { it.value == value } ?: LEFT
    }
}

internal class TextTabStop(val positionEmu: Double, val alignment: TextTabAlignment = TextTabAlignment.LEFT)

internal sealed interface TextBulletSize {
    object Inherit : TextBulletSize

    class Percent(val value: Double) : TextBulletSize

    class Points(val value: Double) : TextBulletSize
}

internal sealed interface TextBullet {
    object None : TextBullet

    class Character(
        val char: String,
        val font: String?,
        val color: Rgba?,
        val size: TextBulletSize,
    ) : TextBullet

    class Numbering(
        val scheme: String,
        val startAt: Int?,
        val color: Rgba?,
        val size: TextBulletSize,
    ) : TextBullet

    /** A picture bullet; the `a:blip` is held whole so the image cache can resolve its relationship. */
    class Picture(
        val image: XNode?,
        val color: Rgba?,
        val size: TextBulletSize,
    ) : TextBullet
}

internal class TextParagraphProperties(
    val alignment: TextAlignment? = null,
    val marginLeftEmu: Double? = null,
    val marginRightEmu: Double? = null,
    /** Negative is a hanging indent. */
    val indentEmu: Double? = null,
    val defaultTabSizeEmu: Double? = null,
    val tabStops: List<TextTabStop> = emptyList(),
    val rightToLeft: Boolean = false,
    val lineSpacing: TextSpacing? = null,
    val spaceBefore: TextSpacing? = null,
    val spaceAfter: TextSpacing? = null,
    val fontAlignment: TextFontAlignment? = null,
    val eastAsianLineBreak: Boolean = true,
    val latinLineBreak: Boolean = true,
    val hangingPunctuation: Boolean = false,
    val bullet: TextBullet = TextBullet.None,
)

internal class TextParagraph(
    val level: Int = 0,
    val properties: TextParagraphProperties = TextParagraphProperties(),
    val runs: List<TextRun> = emptyList(),
    /** The `endParaRPr`; it is what gives a paragraph with no runs its height. */
    val endProperties: TextRunProperties = TextRunProperties(),
)

/** One item inside a paragraph: stored text, a line break, or a field. */
internal sealed interface TextRun {
    val properties: TextRunProperties

    class Text(val text: String, override val properties: TextRunProperties) : TextRun

    class Break(override val properties: TextRunProperties) : TextRun

    /** An `a:fld`; [text] is the cached result the file stores beside it. */
    class Field(val type: String, val text: String, override val properties: TextRunProperties) : TextRun
}

internal enum class TextStrike {
    NONE,
    SINGLE,
    DOUBLE,
    ;

    companion object {
        fun of(value: String?): TextStrike = when (value) {
            "sngStrike" -> SINGLE
            "dblStrike" -> DOUBLE
            else -> NONE
        }
    }
}

internal enum class TextCaps {
    NONE,
    SMALL,
    ALL,
    ;

    companion object {
        fun of(value: String?): TextCaps = when (value) {
            "small" -> SMALL
            "all" -> ALL
            else -> NONE
        }
    }
}

/**
 * An `a:u` value and the `uFill`/`uLn` that can outrank it; [fill] and [line] are null when the
 * underline takes the run's own paint.
 */
internal class TextUnderline(
    val style: String = NONE_STYLE,
    val fill: ScenePaint? = null,
    val line: XNode? = null,
) {

    val visible: Boolean get() = style != NONE_STYLE

    companion object {
        const val NONE_STYLE = "none"

        val NONE = TextUnderline()
    }
}

/** An `a:hlinkClick` on a run, with the theme's link colours resolved. */
internal class TextHyperlink(
    val relationshipId: String? = null,
    val action: String? = null,
    val color: Rgba? = null,
    val visitedColor: Rgba? = null,
)

internal class TextRunProperties(
    val sizePt: Double = DEFAULT_SIZE_PT,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: TextUnderline = TextUnderline.NONE,
    val strike: TextStrike = TextStrike.NONE,
    val caps: TextCaps = TextCaps.NONE,
    /** Baseline shift as a fraction of the font size: positive is superscript, negative subscript. */
    val baseline: Double = 0.0,
    /** Character spacing in points; negative is tighter. */
    val spacingPt: Double = 0.0,
    /** Text at or below this size in points is kerned; 0 disables kerning. */
    val kerningPt: Double = 0.0,
    val fill: ScenePaint? = null,
    /** The `a:ln` outline, held whole for the paint pass. */
    val outline: XNode? = null,
    /** The `effectLst` shadow or glow, held whole for the paint pass. */
    val effects: XNode? = null,
    val highlight: Rgba? = null,
    val latinTypeface: String? = null,
    val eastAsianTypeface: String? = null,
    val complexScriptTypeface: String? = null,
    val symbolTypeface: String? = null,
    val hyperlink: TextHyperlink? = null,
    val language: String? = null,
    val alternativeLanguage: String? = null,
) {

    /** PowerPoint kerns only at or below `kern`, and DrawingML exposes no ligature control. */
    fun features(sizePt: Double): FontFeatures = FontFeatures(
        kerning = kerningPt > 0.0 && sizePt <= kerningPt,
        ligatures = false,
    )

    /** `spc` as a fraction of the em, which is the unit both measuring and drawing are given. */
    fun letterSpacingEm(sizePt: Double): Float =
        if (sizePt <= 0.0) 0f else (spacingPt / sizePt).toFloat()

    companion object {
        /** The built-in size PowerPoint falls back to when no level in the chain declares one. */
        const val DEFAULT_SIZE_PT = 18.0
    }
}
