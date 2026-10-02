package com.papyrus.app.ui.viewer

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString

/** Background for the chunk, block or page holding the current hit. */
internal val ActiveMatchColor = Color(0xFFFF9800)

/** Background for every other match. Saturated yellow: the pale one it replaced was near-invisible. */
internal val OtherMatchColor = Color(0xFFFFEB3B)

/** Fixed, not inherited: the dark theme's own text colour is pale, which reads as invisible here. */
internal val MatchTextColor = Color.Black

/**
 * Every occurrence in the chunk is marked, not only the first, and the active chunk's are darker.
 * Marking the chunk whole, as this used to, left matched text indistinguishable from its context.
 */
@Immutable
data class FindHighlight(val query: String, val active: Boolean)

internal fun findHighlightFor(query: String, active: Boolean): FindHighlight? =
    if (query.isBlank()) null else FindHighlight(query, active)

internal fun highlightedText(text: String, highlight: FindHighlight?): AnnotatedString {
    if (highlight == null) return AnnotatedString(text)
    val ranges = findMatchRanges(text, highlight.query)
    if (ranges.isEmpty()) return AnnotatedString(text)
    val style = SpanStyle(
        background = if (highlight.active) ActiveMatchColor else OtherMatchColor,
        color = MatchTextColor,
    )
    return buildAnnotatedString {
        append(text)
        for (range in ranges) addStyle(style, range.first, range.last + 1)
    }
}
