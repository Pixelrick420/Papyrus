package com.papyrus.app.ui.viewer

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString

/** Background for the one match the find bar is on. */
internal val ActiveMatchColor = Color(0xFFFF9800)

/** Background for every other match. Saturated yellow: the pale one it replaced was near-invisible. */
internal val OtherMatchColor = Color(0xFFFFEB3B)

/** Fixed, not inherited: the dark theme's own text colour is pale, which reads as invisible here. */
internal val MatchTextColor = Color.Black

/**
 * Marks every occurrence, the current one ([activeOccurrence], 0-based within this text) in orange
 * and the rest yellow. A match out of range paints nothing.
 */
@Immutable
data class FindHighlight(val query: String, val activeOccurrence: Int?)

internal fun findHighlightFor(query: String, activeOccurrence: Int?): FindHighlight? =
    if (query.isBlank()) null else FindHighlight(query, activeOccurrence)

/**
 * The same highlight for text following [consumed] matches of the block, such as a table row's
 * second cell: a match is numbered across the block's whole text.
 */
internal fun FindHighlight.skipping(consumed: Int): FindHighlight =
    FindHighlight(query, activeOccurrence?.minus(consumed)?.takeIf { it >= 0 })

/** The range of the current match in [text], or null if the current match is not in this text. */
internal fun FindHighlight.activeRange(text: String): IntRange? =
    activeOccurrence?.let { findMatchRanges(text, query).getOrNull(it) }

internal val ActiveSpan = SpanStyle(background = ActiveMatchColor, color = MatchTextColor)
internal val OtherSpan = SpanStyle(background = OtherMatchColor, color = MatchTextColor)

internal fun highlightedText(text: String, highlight: FindHighlight?): AnnotatedString {
    if (highlight == null) return AnnotatedString(text)
    val ranges = findMatchRanges(text, highlight.query)
    if (ranges.isEmpty()) return AnnotatedString(text)
    return buildAnnotatedString {
        append(text)
        ranges.forEachIndexed { index, range ->
            // One style per range, never orange layered over yellow: no reliance on which of two
            // overlapping spans wins.
            val style = if (index == highlight.activeOccurrence) ActiveSpan else OtherSpan
            addStyle(style, range.first, range.last + 1)
        }
    }
}
