package com.papyrus.app.ui.viewer

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString

internal val ActiveMatchColor = Color(0xFFFF9800)

/** Every other match. Saturated yellow: the pale one it replaced was near-invisible. */
internal val OtherMatchColor = Color(0xFFFFEB3B)

/** Fixed, not inherited: the dark theme's text colour is pale and reads as invisible here. */
internal val MatchTextColor = Color.Black

// [onlyActive] drops every other occurrence: a search result arriving lights the one match it landed on and lets it fade, where find lights every match.
@Immutable
data class FindHighlight(
    val query: String,
    val activeOccurrence: Int?,
    val alpha: Float = 1f,
    val onlyActive: Boolean = false,
)

internal fun findHighlightFor(
    query: String,
    activeOccurrence: Int?,
    alpha: Float = 1f,
    onlyActive: Boolean = false,
): FindHighlight? =
    if (query.isBlank()) null else FindHighlight(query, activeOccurrence, alpha, onlyActive)

// A match is numbered across the block's whole text, so text after [consumed] matches (a table row's second cell) needs an offset.
internal fun FindHighlight.skipping(consumed: Int): FindHighlight =
    FindHighlight(query, activeOccurrence?.minus(consumed)?.takeIf { it >= 0 }, alpha, onlyActive)

internal fun FindHighlight.activeRange(text: String): IntRange? =
    activeOccurrence?.let { findMatchRanges(text, query).getOrNull(it) }

internal fun FindHighlight.paints(index: Int): Boolean = !onlyActive || index == activeOccurrence

// At [alpha] 1 the style is exactly the one find has always drawn; a fade returns the text to [baseTextColor].
internal fun FindHighlight.styleFor(index: Int, baseTextColor: Color): SpanStyle =
    if (index == activeOccurrence) activeSpan(alpha, baseTextColor) else otherSpan(alpha, baseTextColor)

internal fun activeSpan(alpha: Float, baseTextColor: Color): SpanStyle =
    matchSpan(ActiveMatchColor, alpha, baseTextColor)

internal fun otherSpan(alpha: Float, baseTextColor: Color): SpanStyle =
    matchSpan(OtherMatchColor, alpha, baseTextColor)

private fun matchSpan(background: Color, alpha: Float, baseTextColor: Color): SpanStyle {
    val faded = alpha.coerceIn(0f, 1f)
    return SpanStyle(background = background.copy(alpha = faded), color = matchTextColor(faded, baseTextColor))
}

// A caller with no concrete base colour keeps black and lets it go transparent with the background, rather than guessing a theme colour.
private fun matchTextColor(alpha: Float, baseTextColor: Color): Color =
    if (baseTextColor == Color.Unspecified) {
        if (alpha <= 0f) Color.Unspecified else MatchTextColor.copy(alpha = alpha)
    } else {
        lerp(MatchTextColor, baseTextColor, 1f - alpha)
    }

internal fun highlightedText(
    text: String,
    highlight: FindHighlight?,
    baseTextColor: Color = Color.Unspecified,
): AnnotatedString {
    if (highlight == null) return AnnotatedString(text)
    val ranges = findMatchRanges(text, highlight.query)
    if (ranges.isEmpty()) return AnnotatedString(text)
    return buildAnnotatedString {
        append(text)
        ranges.forEachIndexed { index, range ->
            if (!highlight.paints(index)) return@forEachIndexed
            // One style per range, never orange layered over yellow: no reliance on which of two
            // overlapping spans wins.
            addStyle(highlight.styleFor(index, baseTextColor), range.first, range.last + 1)
        }
    }
}
