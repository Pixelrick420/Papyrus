package com.papyrus.app.ui.viewer

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import com.papyrus.app.viewer.TextSpan
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first

/*
 * Matches are stepped one at a time, but a chunk, block or page can be taller than the screen, so
 * moving to the next match inside one has to scroll to that match, not just to its container.
 */

/**
 * One navigation to a match; whoever draws it scrolls once and marks this consumed. Owned above the
 * lazy list so recomposing an item cannot pull the view back.
 */
internal class RevealTicket {
    var consumed = false
}

/** Room kept around the current match when it is scrolled into view, so it is not flush with an edge. */
internal val RevealMargin = 48.dp

/**
 * Text with find highlights; [reveal] is passed only to the container holding the current match.
 * [spans] is the document's formatting drawn under the highlights, [background] the fill they sit on.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun FindText(
    text: String,
    highlight: FindHighlight?,
    style: TextStyle,
    modifier: Modifier = Modifier,
    reveal: RevealTicket? = null,
    spans: List<TextSpan> = emptyList(),
    background: Color? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val surface = background ?: scheme.surface
    val palette = remember(surface, scheme.primary) { FormatPalette(surface, scheme.primary) }
    val annotated = remember(text, spans, highlight, palette) {
        if (spans.isEmpty()) highlightedText(text, highlight) else formattedText(text, spans, highlight, palette)
    }
    val activeRange = remember(text, highlight) { highlight?.activeRange(text) }
    // Reset with the text: a layout of other text can be shorter than an offset into this one.
    var layout by remember(text) { mutableStateOf<TextLayoutResult?>(null) }
    val requester = remember { BringIntoViewRequester() }
    val margin = with(LocalDensity.current) { RevealMargin.toPx() }

    LaunchedEffect(activeRange, reveal) {
        if (activeRange == null || reveal == null || reveal.consumed) return@LaunchedEffect
        // The text may have only just been composed, by a scroll that brought its item in.
        val measured = snapshotFlow { layout }.filterNotNull().first()
        reveal.consumed = true
        val box = measured.getBoundingBox(activeRange.first)
        requester.bringIntoView(Rect(box.left - margin, box.top - margin, box.right + margin, box.bottom + margin))
    }

    Text(
        annotated,
        style = style,
        modifier = modifier.bringIntoViewRequester(requester),
        onTextLayout = { layout = it },
    )
}

/**
 * Brings [index] into composition so its own match can be revealed. An item already on screen stays
 * put: jumping it to the top first made every step inside it twitch before settling.
 */
internal suspend fun LazyListState.scrollToItemUnlessVisible(index: Int) {
    if (layoutInfo.visibleItemsInfo.none { it.index == index }) scrollToItem(index)
}
