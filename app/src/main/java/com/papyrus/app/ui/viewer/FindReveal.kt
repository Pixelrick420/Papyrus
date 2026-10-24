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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import com.papyrus.app.viewer.TextSpan
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first

// A chunk, block or page can exceed the screen, so a match needs its own reveal, not its container's.

/** One navigation to a match; the drawer scrolls once, then marks it consumed. */
internal class RevealTicket {
    var consumed = false
}

/** Room around the current match when scrolled into view, so it is not flush with an edge. */
internal val RevealMargin = 48.dp

/**
 * Text with find highlights; [reveal] goes only to the container holding the current match, and
 * [syntax] colours source code under the highlights. [speaking] is the part being read aloud: shaded
 * behind the text, and kept on screen while it says to follow.
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
    syntax: SyntaxStyling? = null,
    softWrap: Boolean = true,
    onLayout: ((TextLayoutResult) -> Unit)? = null,
    speaking: SpokenRange? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val surface = background ?: scheme.surface
    val palette = remember(surface, scheme.primary) { FormatPalette(surface, scheme.primary) }
    // A fading match returns its text to this colour; a style with none inherits the surface's own.
    val baseTextColor = style.color.takeIf { it != Color.Unspecified } ?: scheme.onSurface
    // A tapped link goes back to the viewer screen, which owns the failure message; the default
    // opener does nothing, so a surface outside a viewer keeps working.
    val opener = LocalLinkOpener.current
    val annotated = remember(text, spans, highlight, palette, syntax, opener, baseTextColor) {
        when {
            syntax != null -> syntaxHighlightedText(text, syntax, highlight, baseTextColor)
            spans.isEmpty() -> highlightedText(text, highlight, baseTextColor)
            else -> formattedText(text, spans, highlight, palette, opener, baseTextColor)
        }
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

    // Follow the voice: the line it is on is brought into view when it moves to a new one, and again
    // when the lock is pressed back on. Keyed on the line, not the word, so a word that stays on its
    // line never interrupts the scroll already under way.
    val spokenOffset = speaking?.let { it.word?.first ?: it.sentence?.first }
    val spokenLine = layout?.let { laidOut ->
        spokenOffset?.let { laidOut.getLineForOffset(it.coerceIn(0, laidOut.layoutInput.text.length)) }
    }
    val following = speaking?.follow == true
    val followMargin = with(LocalDensity.current) { FollowTopMargin.toPx() }
    LaunchedEffect(following, spokenLine) {
        val spoken = speaking
        val laidOut = layout
        if (!following || spoken == null || laidOut == null || spokenLine == null) return@LaunchedEffect
        val top = laidOut.getLineTop(spokenLine)
        val bottom = laidOut.getLineBottom(spokenLine)
        val window = followWindow(bottom - top, spoken.viewportHeight().toFloat(), followMargin)
        requester.bringIntoView(Rect(0f, top - window.above, laidOut.size.width.toFloat(), top + window.below))
    }

    // Drawn behind the text from its own layout, so a new word repaints and nothing more: no new
    // string, no new measure.
    val sentenceColor = spokenSentenceColor()
    val wordColor = spokenWordColor()
    val shade = if (speaking == null) {
        Modifier
    } else {
        Modifier.drawBehind {
            val laidOut = layout ?: return@drawBehind
            val limit = laidOut.layoutInput.text.length
            speaking.sentence?.let {
                val path = laidOut.getPathForRange(it.first.coerceIn(0, limit), (it.last + 1).coerceIn(0, limit))
                drawPath(path, sentenceColor)
            }
            speaking.word?.let {
                val path = laidOut.getPathForRange(it.first.coerceIn(0, limit), (it.last + 1).coerceIn(0, limit))
                drawPath(path, wordColor)
            }
        }
    }

    Text(
        annotated,
        style = style,
        modifier = modifier.bringIntoViewRequester(requester).then(shade),
        softWrap = softWrap,
        onTextLayout = {
            layout = it
            onLayout?.invoke(it)
        },
    )
}

/** Brings [index] into composition so its match can be revealed; an item on screen stays put. */
internal suspend fun LazyListState.scrollToItemUnlessVisible(index: Int) {
    if (layoutInfo.visibleItemsInfo.none { it.index == index }) scrollToItem(index)
}
