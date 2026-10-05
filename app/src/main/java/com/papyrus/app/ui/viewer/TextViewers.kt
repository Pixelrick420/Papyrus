package com.papyrus.app.ui.viewer

import android.text.Spanned
import android.text.SpannableString
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.papyrus.app.viewer.OfficeBlock
import io.noties.markwon.Markwon
import io.noties.markwon.ext.tables.TableRowSpan

/** Zoom and scrollbar are overlays, so showing them never re-measures the scrollable. */
@Composable
private fun ZoomableScrollArea(
    geometry: State<ScrollGeometry>,
    onScrollBy: suspend (Float) -> Unit,
    zoom: ZoomState,
    modifier: Modifier = Modifier,
    content: @Composable (Modifier) -> Unit,
) {
    // clipToBounds on this unscaled parent: the pinch preview is a graphicsLayer scale on the child,
    // and a layer does not clip itself, so without this the page grows over the header above.
    Box(modifier.fillMaxSize().clipToBounds()) {
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
    source: String,
    text: Spanned,
    findQuery: String,
    zoom: ZoomState,
    modifier: Modifier = Modifier,
) {
    val scroll = rememberScrollState()
    val geometry = rememberScrollGeometry(scroll)
    val textColor = MaterialTheme.colorScheme.onSurface.toArgb()
    val linkColor = MaterialTheme.colorScheme.primary.toArgb()
    val baseTextPx = with(LocalDensity.current) { MARKDOWN_BASE_SP.toPx() }
    // Pixels, not sp: `TextView.textSize` reads sp, so handing it `toPx()` scaled the body by the density twice.
    val textPx = baseTextPx * zoom.committedScale
    val rendered = rememberRenderedMarkdown(markwon, source, text, textPx, textColor, linkColor)
    val highlighted = rememberHighlightedText(rendered, findQuery)
    val textState = remember { MarkdownTextState() }

    // A commit re-wraps the text, so the paragraph under the finger has to be pinned across it. The
    // anchor reads that paragraph at the commit, before the re-wrap; the text only reaches its new
    // height a measure later, so the scroll follows, and the bridge paints it until it has.
    val anchor = remember(scroll) { ScrollZoomAnchor(scroll, horizontal = false) }
    ZoomAnchorEffect(zoom, anchor)
    LaunchedEffect(zoom.lastCommit) {
        zoom.lastCommit?.let { anchor.settle(zoom) }
    }

    ZoomableScrollArea(geometry, { scroll.scrollBy(it) }, zoom, modifier) { scrollModifier ->
        ScaledTypography(zoom.committedScale) {
            SelectionContainer {
                Column(
                    scrollModifier
                        .verticalScroll(scroll, enabled = !zoom.isScrollLocked)
                        .anchorBridge(anchor)
                        .padding(16.dp),
                ) {
                    AndroidView(
                        modifier = Modifier.fillMaxWidth(),
                        // The native TextView is outside the Compose type system; its size tracks that scale.
                        factory = { context ->
                            TextView(context).apply {
                                setTextSize(TypedValue.COMPLEX_UNIT_PX, textPx)
                                setLineSpacing(0f, LINE_SPACING_MULTIPLIER)
                                // The setter, not `isTextSelectable = true`: that resolves to the read-only
                                // val and does not compile.
                                setTextIsSelectable(true)
                            }
                        },
                        update = { view ->
                            view.setTextColor(textColor)
                            view.setLinkTextColor(linkColor)
                            // Committed scale, not live: the preview layer paints the in-flight gesture,
                            // gesture, and resizing per frame relaid out the whole document.
                            view.setTextSize(TypedValue.COMPLEX_UNIT_PX, textPx)
                            // Only on a change: setting text collapses the selection and dismisses the
                            // copy toolbar, so a find query or a zoom commit would kill a copy in progress.
                            // The view is compared too, because `ScaledTypography` composes differently at
                            // 1x, so crossing 1x hands over a new TextView.
                            if (textState.view !== view || textState.text !== highlighted) {
                                textState.view = view
                                textState.text = highlighted
                                markwon.setParsedMarkdown(view, highlighted)
                            }
                        },
                    )
                }
            }
        }
    }
}

/**
 * Plain fields, not Compose state: `AndroidView`'s update block runs during layout, and an observable
 * one would recompose on every text set.
 */
private class MarkdownTextState {
    var view: TextView? = null
    var text: Spanned? = null
}

/**
 * A table row span lays its cells out once per canvas width, copying the TextView's paint (size and
 * colours) at that moment. Zooming or switching theme changes the paint but not the width, so cells
 * kept the size and colour of the first draw: huge afterwards, and unmoved by zoom. The spans cannot
 * be reset from outside, so a document with tables gets a fresh `Spanned` whenever the paint changes;
 * one without tables keeps the shared parse and pays nothing.
 */
@Composable
private fun rememberRenderedMarkdown(
    markwon: Markwon,
    source: String,
    parsed: Spanned,
    textPx: Float,
    textColor: Int,
    linkColor: Int,
): Spanned {
    val hasTables = remember(parsed) { parsed.getSpans(0, parsed.length, TableRowSpan::class.java).isNotEmpty() }
    return if (hasTables) {
        remember(markwon, source, textPx, textColor, linkColor) { markwon.toMarkdown(source) }
    } else {
        parsed
    }
}

/** Debounced: applying spans walks the whole document, which a fast typist would feel. */
@Composable
private fun rememberHighlightedText(text: Spanned, query: String): Spanned {
    // Seeded with the highlight, not the bare text: a new `text` (a table re-render) would otherwise blank
    // the matches until the debounce fires.
    var highlighted by remember(text) { mutableStateOf(if (query.isBlank()) text else text.withHighlight(query)) }
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
    activeOccurrence: Int?,
    zoom: ZoomState,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val geometry = rememberLazyScrollGeometry(listState)

    // A chunk is 40 lines, so it can be taller than the screen. This brings the chunk in; its own
    // `FindText` then scrolls to the current match inside it, once per step.
    val reveal = remember(activeHit, activeOccurrence, findQuery) { RevealTicket() }
    LaunchedEffect(reveal) {
        activeHit?.let { listState.scrollToItemUnlessVisible(it) }
    }

    val anchor = remember(listState) { LazyZoomAnchor(listState) }
    ZoomAnchorEffect(zoom, anchor)
    LaunchedEffect(zoom.lastCommit) {
        zoom.lastCommit?.let { anchor.settle(zoom) }
    }

    ZoomableScrollArea(geometry, { listState.scrollBy(it) }, zoom, modifier) { scrollModifier ->
        ScaledTypography(zoom.committedScale) {
            SelectionContainer {
                LazyColumn(
                    scrollModifier,
                    userScrollEnabled = !zoom.isScrollLocked,
                    // Trailing pad clears the scrollbar overlay.
                    contentPadding = PaddingValues(start = 16.dp, end = 20.dp, top = 16.dp, bottom = 16.dp),
                ) {
                    items(chunks.size) { index ->
                        // Every match in the chunk, but only in composed chunks, so big files stay cheap.
                        // Only the active chunk knows which of its matches is current; the rest are all yellow.
                        val isActive = index == activeHit
                        FindText(
                            text = chunks[index],
                            highlight = findHighlightFor(findQuery, activeOccurrence = activeOccurrence.takeIf { isActive }),
                            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp),
                            reveal = reveal.takeIf { isActive },
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
    activeOccurrence: Int?,
    zoom: ZoomState,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val geometry = rememberLazyScrollGeometry(listState)

    // Block indices equal LazyColumn indices, so a hit scrolls straight to its own item, no offset.
    // A long paragraph or table can hold several matches, so the block's own text then scrolls to
    // the current one.
    val reveal = remember(activeHit, activeOccurrence, findQuery) { RevealTicket() }
    LaunchedEffect(reveal) {
        activeHit?.let { listState.scrollToItemUnlessVisible(it) }
    }

    val anchor = remember(listState) { LazyZoomAnchor(listState) }
    ZoomAnchorEffect(zoom, anchor)
    LaunchedEffect(zoom.lastCommit) {
        zoom.lastCommit?.let { anchor.settle(zoom) }
    }

    ZoomableScrollArea(geometry, { listState.scrollBy(it) }, zoom, modifier) { scrollModifier ->
        ScaledTypography(zoom.committedScale) {
            SelectionContainer {
                LazyColumn(
                    scrollModifier,
                    userScrollEnabled = !zoom.isScrollLocked,
                    contentPadding = PaddingValues(start = 16.dp, end = 20.dp, top = 16.dp, bottom = 16.dp),
                ) {
                    items(blocks.size, key = { it }) { index ->
                        val isActive = index == activeHit
                        OfficeBlockView(
                            blocks[index],
                            Modifier.fillMaxWidth(),
                            highlight = findHighlightFor(findQuery, activeOccurrence = activeOccurrence.takeIf { isActive }),
                            reveal = reveal.takeIf { isActive },
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
