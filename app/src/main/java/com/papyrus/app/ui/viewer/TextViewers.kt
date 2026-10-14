package com.papyrus.app.ui.viewer

import android.text.Spanned
import android.text.SpannableString
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.widget.TextView
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.papyrus.app.R
import com.papyrus.app.viewer.CodeLanguage
import com.papyrus.app.viewer.LexStates
import com.papyrus.app.viewer.OfficeBlock
import com.papyrus.app.viewer.SyntaxHighlighter
import com.papyrus.app.viewer.TEXT_LINES_PER_CHUNK
import io.noties.markwon.Markwon
import io.noties.markwon.ext.tables.TableRowSpan
import kotlin.math.roundToInt

/** Zoom and scrollbar are overlays, so showing them never re-measures the scrollable. */
@Composable
private fun ZoomableScrollArea(
    geometry: State<ScrollGeometry>,
    onScrollBy: suspend (Float) -> Unit,
    zoom: ZoomState,
    modifier: Modifier = Modifier,
    content: @Composable (Modifier) -> Unit,
) {
    // The preview is a graphicsLayer scale on the child, and a layer does not clip itself, so this
    // unscaled parent must clip or the page grows over the header above.
    Box(modifier.fillMaxSize().clipToBounds()) {
        // Zoom goes on the scrollable: on a wrapper, modifier order decides who wins the drag.
        content(Modifier.fillMaxSize().zoomable(zoom))
        Box(
            Modifier
                .align(Alignment.CenterEnd)
                .width(ScrollbarTouchTarget)
                .fillMaxSize()
                .autoHideScrollbar(
                    geometry,
                    // Higher than the default: lines are short, so the same flick covers more of the
                    // document and the estimated offset is noisier, and the knob showed too eagerly.
                    showVelocity = TEXT_SHOW_VELOCITY,
                    topClearance = ZoomBadgeTopClearance,
                    zoom = zoom,
                    onScrollBy = onScrollBy,
                ),
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
    // Markwon leaves the TextView without a movement method, which is why its links do nothing at
    // all today; this one makes them tappable and routes the tap through the viewer's own opener
    // instead of the resolver that turns a `file://` link into a crash.
    val opener = LocalLinkOpener.current
    val movement = remember(opener) { GuardedLinkMovementMethod(opener) }

    // A commit re-wraps the text, so the anchor pins the paragraph at the commit; the text reaches its
    // new height a measure later, so the scroll follows and the bridge paints between.
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
                                // The setter, not `isTextSelectable = true`: that resolves to the read-only val.
                                setTextIsSelectable(true)
                            }
                        },
                        update = { view ->
                            view.setTextColor(textColor)
                            view.setLinkTextColor(linkColor)
                            view.movementMethod = movement
                            // Committed scale, not live: the preview layer paints the in-flight gesture,
                            // gesture, and resizing per frame relaid out the whole document.
                            view.setTextSize(TypedValue.COMPLEX_UNIT_PX, textPx)
                            // Only on a change: setting text kills a copy in progress; the view too, since
                            // `ScaledTypography` composes differently at 1x.
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

/** Plain fields, not Compose state: the update block runs during layout, and state would recompose per set. */
private class MarkdownTextState {
    var view: TextView? = null
    var text: Spanned? = null
}

// Table row spans freeze the paint at first draw, so a document with tables gets a fresh `Spanned`
// whenever the paint changes; one without tables keeps the shared parse and pays nothing.
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

/**
 * What the plain-text surface needs besides its chunks. The chunk list itself never changes with these,
 * so a settings toggle redraws rows and re-measures nothing that was read from the file.
 */
@Immutable
class PlainTextOptions(
    /** Off, each line stays on one row and the whole list scrolls sideways together. */
    val wrapLines: Boolean = true,
    val showLineNumbers: Boolean = false,
    /** Colours the text as source; null for plain text, or when highlighting is switched off. */
    val language: CodeLanguage? = null,
    val lexStates: LexStates? = null,
    /** Sizes the gutter, which is as wide for line 100 000 as it is at the top of the file. */
    val lineCount: Int = 0,
    /** Sizes the sideways scroll extent when [wrapLines] is off. */
    val maxLineColumns: Int = 0,
    val truncated: Boolean = false,
)

@Composable
fun PlainTextViewer(
    chunks: List<String>,
    findQuery: String,
    activeHit: Int?,
    activeOccurrence: Int?,
    zoom: ZoomState,
    modifier: Modifier = Modifier,
    options: PlainTextOptions = PlainTextOptions(),
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

    // One sideways scroll for the whole list, so every chunk moves together; the vertical anchors only
    // know about rows, so this one keeps the column under a pinch where it was.
    val sideways = rememberScrollState()
    val sidewaysAnchor = remember(sideways) { SidewaysZoomAnchor(sideways) }
    ZoomAnchorEffect(zoom, sidewaysAnchor)
    LaunchedEffect(zoom.lastCommit) {
        zoom.lastCommit?.let { sidewaysAnchor.settle(zoom) }
    }

    ZoomableScrollArea(geometry, { listState.scrollBy(it) }, zoom, modifier) { scrollModifier ->
        ScaledTypography(zoom.committedScale) {
            PlainTextList(
                chunks = chunks,
                findQuery = findQuery,
                activeHit = activeHit,
                activeOccurrence = activeOccurrence,
                options = options,
                listState = listState,
                sideways = sideways,
                reveal = reveal,
                zoom = zoom,
                modifier = scrollModifier,
            )
        }
    }
}

/** Inside [ScaledTypography], so every size it measures is the one on screen at the committed zoom. */
@Composable
private fun PlainTextList(
    chunks: List<String>,
    findQuery: String,
    activeHit: Int?,
    activeOccurrence: Int?,
    options: PlainTextOptions,
    listState: LazyListState,
    sideways: ScrollState,
    reveal: RevealTicket,
    zoom: ZoomState,
    modifier: Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)
    val measurer = rememberTextMeasurer(cacheSize = NUMBER_LAYOUT_CACHE)
    // Monospace, so one measured run gives the width of every column, in the pixels of the current zoom.
    val columnPx = remember(measurer, textStyle) { measurer.measure(COLUMN_PROBE, textStyle).size.width / COLUMN_PROBE.length.toFloat() }

    val gutter = if (options.showLineNumbers) {
        val gapPx = with(density) { GutterGap.toPx() }
        val digits = options.lineCount.coerceAtLeast(1).toString().length
        val numberColor = colors.onSurfaceVariant.copy(alpha = GUTTER_ALPHA)
        val dividerColor = colors.outlineVariant
        // Remembered, so the chunks on screen are not all handed a new object, and recompose, on every pass.
        remember(columnPx, gapPx, digits, measurer, textStyle, numberColor, dividerColor) {
            Gutter(
                widthPx = columnPx * digits + gapPx * 2,
                gapPx = gapPx,
                measurer = measurer,
                style = textStyle.copy(color = numberColor),
                dividerColor = dividerColor,
            )
        }
    } else {
        null
    }
    val gutterWidth = with(density) { (gutter?.widthPx ?: 0f).toDp() }

    // The widest line sets the extent, not the lines currently composed: a width that followed the visible
    // chunks would change as the list scrolls and drag the sideways position with it. Capped well under
    // Compose's limit on a layout dimension, which a long minified line at a high zoom would otherwise pass.
    val unwrappedWidth: Dp? = if (options.wrapLines) {
        null
    } else {
        with(density) { ((gutter?.widthPx ?: 0f) + (options.maxLineColumns + 2) * columnPx).coerceAtMost(MAX_UNWRAPPED_WIDTH_PX).toDp() }
    }

    val syntaxPalette = SyntaxPalette.forSurface(colors.surface)

    SelectionContainer {
        LazyColumn(
            if (options.wrapLines) modifier else modifier.horizontalScroll(sideways, enabled = !zoom.isScrollLocked),
            // The state the scrollbar, zoom anchor and find all read; without it the list
            // scrolls its own private state and they see an empty one.
            state = listState,
            userScrollEnabled = !zoom.isScrollLocked,
            // Trailing pad clears the scrollbar overlay.
            contentPadding = PaddingValues(start = 16.dp, end = 20.dp, top = 16.dp, bottom = 16.dp),
        ) {
            items(chunks.size) { index ->
                // Every match in the chunk, but only in composed chunks, so big files stay cheap.
                // Only the active chunk knows which of its matches is current; the rest are all yellow.
                val isActive = index == activeHit
                TextChunk(
                    text = chunks[index],
                    index = index,
                    isLast = index == chunks.lastIndex,
                    highlight = findHighlightFor(findQuery, activeOccurrence = activeOccurrence.takeIf { isActive }),
                    reveal = reveal.takeIf { isActive },
                    style = textStyle,
                    options = options,
                    syntaxPalette = syntaxPalette,
                    gutter = gutter,
                    gutterWidth = gutterWidth,
                    width = unwrappedWidth,
                )
            }
            if (options.truncated) {
                item(key = TRUNCATED_ITEM_KEY) {
                    Text(
                        text = stringResource(R.string.viewer_truncated),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                        modifier = Modifier.padding(start = gutterWidth, top = 12.dp),
                    )
                }
            }
        }
    }
}

/** One chunk of 40 lines, its text coloured as source if it has a language, its numbers drawn beside it if asked for. */
@Composable
private fun TextChunk(
    text: String,
    index: Int,
    isLast: Boolean,
    highlight: FindHighlight?,
    reveal: RevealTicket?,
    style: TextStyle,
    options: PlainTextOptions,
    syntaxPalette: SyntaxPalette,
    gutter: Gutter?,
    gutterWidth: Dp,
    width: Dp?,
) {
    val language = options.language
    val startState = options.lexStates?.get(index) ?: 0
    // Per chunk and on demand, so a file only pays for the part on screen. A chunk over the cap is minified
    // code, which colour does not help and tens of thousands of spans would slow down.
    val syntax = remember(text, language, startState, syntaxPalette) {
        language?.takeIf { text.length <= SyntaxHighlighter.MAX_HIGHLIGHT_CHARS }?.let {
            SyntaxStyling(SyntaxHighlighter.tokenize(it, text, startState), syntaxPalette)
        }
    }

    // The numbers are drawn from the text's own layout, one per source line at the top of its first row, so a
    // wrapped line still has a single number. Drawn, not laid out, so they are never selected or copied.
    var layout by remember(text) { mutableStateOf<TextLayoutResult?>(null) }
    val lineStarts = remember(text, gutter != null, isLast) {
        if (gutter == null) NO_LINES else lineStartOffsets(text, dropEmptyTail = isLast)
    }
    val firstLine = index * TEXT_LINES_PER_CHUNK + 1

    val drawGutterBehind = if (gutter == null) {
        Modifier
    } else {
        Modifier.drawBehind {
            val laidOut = layout ?: return@drawBehind
            drawGutter(laidOut, lineStarts, firstLine, gutter)
        }
    }
    Box((if (width != null) Modifier.width(width) else Modifier.fillMaxWidth()).then(drawGutterBehind)) {
        FindText(
            text = text,
            highlight = highlight,
            style = style,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = gutterWidth, top = ChunkVerticalPadding, bottom = ChunkVerticalPadding),
            reveal = reveal,
            syntax = syntax,
            softWrap = options.wrapLines,
            onLayout = if (gutter != null) ({ layout = it }) else null,
        )
    }
}

/** Sizes and style of the line-number column, in the pixels of the zoom it was measured at. */
@Immutable
private class Gutter(
    val widthPx: Float,
    val gapPx: Float,
    val measurer: TextMeasurer,
    val style: TextStyle,
    val dividerColor: Color,
)

private fun DrawScope.drawGutter(layout: TextLayoutResult, lineStarts: IntArray, firstLine: Int, gutter: Gutter) {
    val top = ChunkVerticalPadding.toPx()
    val textLength = layout.layoutInput.text.length
    for (k in lineStarts.indices) {
        val row = layout.getLineForOffset(lineStarts[k].coerceAtMost(textLength))
        val number = gutter.measurer.measure((firstLine + k).toString(), gutter.style)
        drawText(
            number,
            topLeft = Offset(gutter.widthPx - gutter.gapPx - number.size.width, top + layout.getLineTop(row)),
        )
    }
    val dividerX = gutter.widthPx - gutter.gapPx / 2
    drawLine(gutter.dividerColor, Offset(dividerX, 0f), Offset(dividerX, size.height), strokeWidth = 1.dp.toPx())
}

/**
 * Offset of each line's first character. A final chunk that ends in a newline has an empty line after
 * it, which editors leave unnumbered, so [dropEmptyTail] omits it.
 */
private fun lineStartOffsets(text: String, dropEmptyTail: Boolean): IntArray {
    var count = 1
    for (c in text) if (c == '\n') count++
    val starts = IntArray(count)
    var next = 1
    for (i in text.indices) if (text[i] == '\n') starts[next++] = i + 1
    return if (dropEmptyTail && count > 1 && text.endsWith('\n')) starts.copyOf(count - 1) else starts
}

/** Holds the column under the pinch fixed when unwrapped text rescales: the other anchors only know about rows. */
private class SidewaysZoomAnchor(private val scroll: ScrollState) : ZoomAnchor {

    private var target: Float? = null

    override fun onCommit(commit: ScaleCommit) {
        target = null
        val focal = commit.focal
        if (commit.ratio == 1f || focal == Offset.Unspecified) return
        // The focal point is a viewport coordinate, so the same growth rule as a row applies.
        target = anchoredScroll(scroll.value.toFloat(), focal.x, commit)
    }

    /** The new width lands a measure after the commit, and `maxValue` lags it, so this chases it a few frames. */
    suspend fun settle(zoom: ZoomState) {
        val wanted = target ?: return
        target = null
        repeat(SIDEWAYS_SETTLE_FRAMES) {
            withFrameNanos {}
            if (zoom.isZooming || scroll.isScrollInProgress) return
            val to = wanted.coerceIn(0f, scroll.maxValue.toFloat()).roundToInt()
            if (to != scroll.value) scroll.scrollTo(to)
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
    jumpTo: OutlineJump? = null,
) {
    val listState = rememberLazyListState()
    val geometry = rememberLazyScrollGeometry(listState)

    // The outline names a heading by its block index, which is its row, so a jump is a plain scroll.
    LaunchedEffect(jumpTo) {
        val target = jumpTo?.index ?: return@LaunchedEffect
        if (target in blocks.indices) listState.scrollToItem(target)
    }

    // Block indices equal LazyColumn indices, so a hit scrolls to its own item; a long paragraph or
    // table holds several matches, so the block's text then scrolls to the current one.
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
                    // The state the scrollbar, zoom anchor and find all read; without it the list
                    // scrolls its own private state and they see an empty one.
                    state = listState,
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

/** Scroll speed (dp/s) that reveals the knob on text surfaces; the PDF viewer keeps the 880 default. */
private val TEXT_SHOW_VELOCITY = 1600.dp
private const val HIGHLIGHT_DEBOUNCE_MS = 150L
private val MARKDOWN_BASE_SP = 16.sp
private const val LINE_SPACING_MULTIPLIER = 1.25f

private val NO_LINES = IntArray(0)
private val GutterGap = 8.dp
private val ChunkVerticalPadding = 2.dp
private const val COLUMN_PROBE = "0000000000"
private const val GUTTER_ALPHA = 0.7f
private const val NUMBER_LAYOUT_CACHE = 64
private const val TRUNCATED_ITEM_KEY = "truncated"
private const val SIDEWAYS_SETTLE_FRAMES = 3

/** Under the 2^16 or so a layout dimension can reach once the other one is tall; lines past it clip. */
private const val MAX_UNWRAPPED_WIDTH_PX = 60_000f
