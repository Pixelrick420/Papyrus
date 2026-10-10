package com.papyrus.app.ui.viewer

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import com.papyrus.app.viewer.BlockAlign
import com.papyrus.app.viewer.CharFormat
import com.papyrus.app.viewer.ScriptShift
import com.papyrus.app.viewer.TextSpan
import kotlin.math.abs

/**
 * What a document's formatting is drawn against: the colour behind the text and links take, so a
 * document's white-paper colours stay usable on whatever surface the app shows.
 */
internal class FormatPalette(val surface: Color, val link: Color)

/** `0xRRGGBB` as an opaque colour. */
internal fun rgbColor(rgb: Int): Color = Color(0xFF000000.toInt() or rgb)

/**
 * A fill this close to white is the document's paper, not a chosen colour: it is dropped so a cell
 * keeps the app's surface, while a tinted fill (pale blue, highlighter yellow) is kept.
 */
internal fun isPaperWhite(rgb: Int): Boolean {
    val red = (rgb shr 16) and 0xFF
    val green = (rgb shr 8) and 0xFF
    val blue = rgb and 0xFF
    return minOf(red, green, blue) >= PAPER_CHANNEL_MIN
}

private const val PAPER_CHANNEL_MIN = 0xF0

/** Black on a light fill, white on a dark one. */
internal fun readableOn(background: Color): Color =
    if (background.luminance() > MID_LUMINANCE) Color.Black else Color.White

/** Below this luminance gap a colour is, to a reader, the colour it sits on. */
private const val MIN_CONTRAST_GAP = 0.25f
private const val MID_LUMINANCE = 0.5f

/** Superscripts and subscripts are set smaller than the line they ride on. */
private const val SCRIPT_SCALE = 0.75

internal fun BlockAlign.toTextAlign(): TextAlign = when (this) {
    BlockAlign.START -> TextAlign.Start
    BlockAlign.CENTER -> TextAlign.Center
    BlockAlign.END -> TextAlign.End
}

private fun spanStyleFor(format: CharFormat, palette: FormatPalette): SpanStyle {
    val background = format.background?.let(::rgbColor)
    val surface = background ?: palette.surface
    // A colour the document asked for is kept only if it can be read where it lands: black text
    // from a white page vanishes on the dark theme's surface, so it falls back to the theme's own.
    val requested = format.color?.let(::rgbColor)
        ?.takeIf { abs(it.luminance() - surface.luminance()) >= MIN_CONTRAST_GAP }
    val color = when {
        format.link != null -> palette.link
        requested != null -> requested
        // A highlighter mark is painted under text whose own colour is the theme's, often pale.
        background != null -> readableOn(background)
        else -> Color.Unspecified
    }
    val decorations = ArrayList<TextDecoration>(2)
    if (format.underline || format.link != null) decorations += TextDecoration.Underline
    if (format.strike) decorations += TextDecoration.LineThrough
    return SpanStyle(
        color = color,
        background = background ?: Color.Unspecified,
        fontWeight = if (format.bold) FontWeight.Bold else null,
        fontStyle = if (format.italic) FontStyle.Italic else null,
        fontFamily = if (format.mono) FontFamily.Monospace else null,
        fontSize = if (format.script == ScriptShift.NONE) TextUnit.Unspecified else SCRIPT_SCALE.em,
        baselineShift = when (format.script) {
            ScriptShift.SUPER -> BaselineShift.Superscript
            ScriptShift.SUB -> BaselineShift.Subscript
            ScriptShift.NONE -> null
        },
        textDecoration = when (decorations.size) {
            0 -> null
            1 -> decorations[0]
            else -> TextDecoration.combine(decorations)
        },
    )
}

/**
 * [text] with the document's [spans] under any find highlights, added last so a match wins over
 * formatting and each match gets exactly one style.
 */
internal fun formattedText(
    text: String,
    spans: List<TextSpan>,
    highlight: FindHighlight?,
    palette: FormatPalette,
): AnnotatedString {
    val ranges = if (highlight == null) emptyList() else findMatchRanges(text, highlight.query)
    if (spans.isEmpty() && ranges.isEmpty()) return AnnotatedString(text)
    return buildAnnotatedString {
        append(text)
        for (span in spans) {
            val start = span.start.coerceIn(0, text.length)
            val end = span.end.coerceIn(start, text.length)
            if (end == start) continue
            addStyle(spanStyleFor(span.format, palette), start, end)
            // The look is the span style's; the annotation only makes the range tappable.
            span.format.link?.let { addLink(LinkAnnotation.Url(it), start, end) }
        }
        ranges.forEachIndexed { index, range ->
            val style = if (index == highlight?.activeOccurrence) ActiveSpan else OtherSpan
            addStyle(style, range.first, range.last + 1)
        }
    }
}
