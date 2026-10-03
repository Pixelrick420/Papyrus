package com.papyrus.app.ui.viewer

import android.text.Spanned
import android.text.SpannableString
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.widget.TextView
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.papyrus.app.viewer.OfficeBlock
import io.noties.markwon.Markwon

/** [findChunkHits] over flattened block text. Stays here because it needs [OfficeBlock]. */
fun findBlockHits(blocks: List<OfficeBlock>, query: String): List<Int> {
    if (query.isBlank()) return emptyList()
    return blocks.mapIndexedNotNull { index, block ->
        val text = when (block) {
            is OfficeBlock.Heading -> block.text
            is OfficeBlock.Paragraph -> block.text
            is OfficeBlock.Table -> block.rows.flatten().joinToString(" ") { it.text }
            is OfficeBlock.Image -> ""
        }
        index.takeIf { text.contains(query, ignoreCase = true) }
    }
}

/** Zoom and scrollbar are overlays, so showing them never re-measures the scrollable. */
@Composable
private fun ZoomableScrollArea(
    geometry: State<ScrollGeometry>,
    onScrollBy: suspend (Float) -> Unit,
    zoom: ZoomState,
    modifier: Modifier = Modifier,
    content: @Composable (Modifier) -> Unit,
) {
    Box(modifier.fillMaxSize()) {
        // Zoom goes on the scrollable: on a wrapper, modifier order decides who wins the drag.
        content(Modifier.fillMaxSize().zoomable(zoom))
        Box(
            Modifier
                .align(Alignment.CenterEnd)
                .width(ScrollbarTouchTarget)
                .fillMaxSize()
                .autoHideScrollbar(geometry, onScrollBy),
        )
    }
}

/** Zoom scales fontScale, not pixels: a layer scale would leave the scroll extent at 1x. */
@Composable
private fun ScaledTypography(scale: Float, content: @Composable () -> Unit) {
    if (scale == 1f) {
        content()
        return
    }
    val density = LocalDensity.current
    CompositionLocalProvider(
        LocalDensity provides Density(density = density.density, fontScale = density.fontScale * scale),
        content = content,
    )
}

/** Markwon renders to Spannable in a TextView, so find has no chunks to jump between. */
@Composable
fun MarkdownViewer(
    markwon: Markwon,
    text: Spanned,
    findQuery: String,
    zoom: ZoomState,
    modifier: Modifier = Modifier,
) {
    val scroll = rememberScrollState()
    val geometry = rememberScrollGeometry(scroll)
    val textColor = MaterialTheme.colorScheme.onSurface.toArgb()
    val linkColor = MaterialTheme.colorScheme.primary.toArgb()
    val highlighted = rememberHighlightedText(text, findQuery)
    val baseTextPx = with(LocalDensity.current) { MARKDOWN_BASE_SP.toPx() }

    // A commit re-wraps the text, so re-anchor or the paragraph under the finger slides away.
    LaunchedEffect(zoom.lastCommit) {
        zoom.lastCommit?.let { scroll.reanchorTo(it) }
    }

    ZoomableScrollArea(geometry, { scroll.scrollBy(it) }, zoom, modifier) { scrollModifier ->
        ScaledTypography(zoom.committedScale) {
            SelectionContainer {
                Column(
                    scrollModifier
                        .verticalScroll(scroll, enabled = !zoom.isPinching)
                        .padding(16.dp),
                ) {
                    AndroidView(
                        modifier = Modifier.fillMaxWidth(),
                        // The native TextView is outside the Compose type system; its size tracks that scale.
                        factory = { context ->
                            TextView(context).apply {
                                textSize = baseTextPx
                                setLineSpacing(0f, LINE_SPACING_MULTIPLIER)
                            }
                        },
                        update = { view ->
                            view.setTextColor(textColor)
                            view.setLinkTextColor(linkColor)
                            // Committed scale, not live: the preview layer paints the in-flight gesture,
                            // gesture, and resizing per frame relaid out the whole document.
                            view.textSize = baseTextPx * zoom.committedScale
                            markwon.setParsedMarkdown(view, highlighted)
                        },
                    )
                }
            }
        }
    }
}

/** Debounced: applying spans walks the whole document, which a fast typist would feel. */
@Composable
private fun rememberHighlightedText(text: Spanned, query: String): Spanned {
    var highlighted by remember(text) { mutableStateOf(text) }
    LaunchedEffect(text, query) {
        if (query.isBlank()) {
            highlighted = text
            return@LaunchedEffect
        }
        kotlinx.coroutines.delay(HIGHLIGHT_DEBOUNCE_MS)
        highlighted = text.withHighlight(query)
    }
    return highlighted
}

private fun Spanned.withHighlight(query: String): Spanned {
    val result = SpannableString(this)
    for (range in findMatchRanges(toString(), query)) {
        // One span object per match: `setSpan` moves an object attached elsewhere, so a shared
        // instance left only the last match highlighted.
        result.setSpan(
            BackgroundColorSpan(OtherMatchColor.toArgb()),
            range.first,
            range.last + 1,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        result.setSpan(
            ForegroundColorSpan(MatchTextColor.toArgb()),
            range.first,
            range.last + 1,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
    }
    return result
}

@Composable
fun PlainTextViewer(
    chunks: List<String>,
    findQuery: String,
    activeHit: Int?,
    zoom: ZoomState,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val geometry = rememberLazyScrollGeometry(listState)

    LaunchedEffect(activeHit) {
        activeHit?.let { listState.scrollToItem(it) }
    }

    LaunchedEffect(zoom.lastCommit) {
        zoom.lastCommit?.let { listState.reanchorTo(it) }
    }

    ZoomableScrollArea(geometry, { listState.scrollBy(it) }, zoom, modifier) { scrollModifier ->
        ScaledTypography(zoom.committedScale) {
            SelectionContainer {
                LazyColumn(
                    scrollModifier,
                    userScrollEnabled = !zoom.isPinching,
                    // Trailing pad clears the scrollbar overlay.
                    contentPadding = PaddingValues(start = 16.dp, end = 20.dp, top = 16.dp, bottom = 16.dp),
                ) {
                    items(chunks.size) { index ->
                        // Every match in the chunk, but only in composed chunks, so big files stay cheap.
                        val highlight = findHighlightFor(findQuery, active = index == activeHit)
                        val text = remember(chunks[index], highlight) { highlightedText(chunks[index], highlight) }
                        Text(
                            text,
                            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun OfficeViewer(
    blocks: List<OfficeBlock>,
    findQuery: String,
    activeHit: Int?,
    zoom: ZoomState,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val geometry = rememberLazyScrollGeometry(listState)

    // Block indices equal LazyColumn indices, so a hit scrolls straight to its own item, no offset.
    LaunchedEffect(activeHit) {
        activeHit?.let { listState.scrollToItem(it) }
    }

    LaunchedEffect(zoom.lastCommit) {
        zoom.lastCommit?.let { listState.reanchorTo(it) }
    }

    ZoomableScrollArea(geometry, { listState.scrollBy(it) }, zoom, modifier) { scrollModifier ->
        ScaledTypography(zoom.committedScale) {
            SelectionContainer {
                LazyColumn(
                    scrollModifier,
                    userScrollEnabled = !zoom.isPinching,
                    contentPadding = PaddingValues(start = 16.dp, end = 20.dp, top = 16.dp, bottom = 16.dp),
                ) {
                    items(blocks.size, key = { it }) { index ->
                        OfficeBlockView(
                            blocks[index],
                            Modifier.fillMaxWidth(),
                            highlight = findHighlightFor(findQuery, active = index == activeHit),
                        )
                    }
                }
            }
        }
    }
}

private const val HIGHLIGHT_DEBOUNCE_MS = 150L
private val MARKDOWN_BASE_SP = 16.sp
private const val LINE_SPACING_MULTIPLIER = 1.25f
