package com.papyrus.app.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow

/**
 * One search result's excerpt, with the words that matched drawn over a highlight.
 *
 * [highlights] index the same string as [text], so nothing here has to re-run the query or guess at
 * word boundaries — the excerpt and its ranges are produced together by the search itself.
 */
@Composable
fun SnippetText(
    text: String,
    highlights: List<IntRange>,
    modifier: Modifier = Modifier,
) {
    val highlight = MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)
    // Keyed on all three so a recycled row cannot keep the previous result's highlight positions.
    val annotated = remember(text, highlights, highlight) {
        buildAnnotatedString {
            append(text)
            for (range in highlights) {
                // Clamped rather than trusted: a range past the end would throw, and a wrong
                // highlight is not worth taking the screen down for.
                val start = range.first.coerceIn(0, text.length)
                val end = (range.last + 1).coerceIn(start, text.length)
                if (end > start) addStyle(SpanStyle(background = highlight), start, end)
            }
        }
    }
    Text(
        text = annotated,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}
