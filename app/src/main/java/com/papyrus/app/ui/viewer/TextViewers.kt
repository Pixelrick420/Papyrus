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
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.papyrus.app.data.LocationKind
import com.papyrus.app.viewer.CodeLanguage
import com.papyrus.app.viewer.LexStates
import com.papyrus.app.viewer.OfficeBlock
import com.papyrus.app.viewer.OfficeCell
import com.papyrus.app.viewer.SyntaxHighlighter
import com.papyrus.app.viewer.TEXT_LINES_PER_CHUNK
import io.noties.markwon.Markwon
import io.noties.markwon.ext.tables.TableRowSpan
import kotlinx.coroutines.flow.first
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
    position: PositionHooks = PositionHooks.None,
    ttsOffset: Int? = null,
) {
    val scroll = rememberScrollState()
    val geometry = rememberScrollGeometry(scroll)
    val textColor = MaterialTheme.colorScheme.onSurface.toArgb()
    val linkColor = MaterialTheme.colorScheme.primary.toArgb()
    val baseTextPx = with(LocalDensity.current) { MARKDOWN_BASE_SP.toPx() }
    // Pixels, not sp: `TextView.textSize` reads sp, so handing it `toPx()` scaled the body by the
    // density twice.
    val textPx = baseTextPx * zoom.committedScale
    val rendered = rememberRenderedMarkdown(markwon, source, text, textPx, textColor, linkColor)
    val highlighted = rememberHighlightedText(rendered, findQuery)
    val textState = remember { MarkdownTextState() }
    // The column's own 16.dp padding sits above the text inside the scrolled content.
    val contentTopPx = with(LocalDensity.current) { 16.dp.toPx() }
    MarkdownPositionEffects(
        scroll = scroll,
        layout = { textState.view?.layout },
        textLength = text.length,
        zoom = zoom,
        position = position,
        target = rememberRestoredTarget(LocationKind.CHAR, text.length, position),
        contentTopPx = contentTopPx,
    )
    // Follow-along scroll: bring the spoken paragraph's first line to the top, waiting out the first
    // measure exactly as the restore path does.
    LaunchedEffect(ttsOffset) {
        if (ttsOffset == null) return@LaunchedEffect
        snapshotFlow { scroll.maxValue }.first { it != Int.MAX_VALUE }
        val layout = textState.view?.layout ?: return@LaunchedEffect
        val line = layout.getLineForOffset(ttsOffset.coerceIn(0, layout.text.length))
        scroll.scrollTo((layout.getLineTop(line) + contentTopPx).roundToInt())
    }
    // Markwon leaves the TextView without a movement method, so its links do nothing; this one routes the
    // tap through the viewer's own opener instead of the resolver that turns a `file://` link into a crash.
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
                        // Native TextView: outside the Compose type system, its size tracks that scale.
                        factory = { context ->
                            TextView(context).apply {
                                setTextSize(TypedValue.COMPLEX_UNIT_PX, textPx)
                                setLineSpacing(0f, LINE_SPACING_MULTIPLIER)
                                // The setter, not `isTextSelectable = true`: that resolves to the
                                // read-only val.
                                setTextIsSelectable(true)
                            }
                        },
                        update = { view ->
                            view.setTextColor(textColor)
                            view.setLinkTextColor(linkColor)
                            view.movementMethod = movement
                            // Committed scale, not live: the preview layer paints the in-flight gesture, and
                            // resizing per frame relaid out the whole document.
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

/**
 * Plain fields, not Compose state: the update block runs during layout, and state would recompose
 * on every set.
 */
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
 * What the plain-text surface needs besides its chunks. None of these change the chunk list, so a settings
 * toggle redraws rows and re-measures nothing read from the file.
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
    position: PositionHooks = PositionHooks.None,
    ttsChunk: Int? = null,
) {
    val restoreTarget = rememberRestoredTarget(LocationKind.CHUNK, chunks.size, position)
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = restoreTarget?.unit ?: 0)
    val geometry = rememberLazyScrollGeometry(listState)
    LazyPositionEffects(listState, LocationKind.CHUNK, chunks.size, zoom, position, restoreTarget)

    // A chunk is 40 lines, so it can be taller than the screen. This brings the chunk in; its own
    // `FindText` then scrolls to the current match inside it, once per step.
    val reveal = remember(activeHit, activeOccurrence, findQuery) { RevealTicket() }
    LaunchedEffect(reveal) {
        activeHit?.let { listState.scrollToItemUnlessVisible(it) }
    }

    // Follow-along: the chunk being read is revealed the way a find result is, and tinted below.
    LaunchedEffect(ttsChunk) {
        ttsChunk?.let { listState.scrollToItemUnlessVisible(it) }
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

/** Inside [ScaledTypography], so every measured size is the on-screen size at the committed zoom. */
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
    // Monospace, so one measured run gives the width of every column, in the pixels of the
    // current zoom.
    val columnPx = remember(measurer, textStyle) { measurer.measure(COLUMN_PROBE, textStyle).size.width / COLUMN_PROBE.length.toFloat() }

    val gutter = if (options.showLineNumbers) {
        val gapPx = with(density) { GutterGap.toPx() }
        val digits = options.lineCount.coerceAtLeast(1).toString().length
        val numberColor = colors.onSurfaceVariant.copy(alpha = GUTTER_ALPHA)
        val dividerColor = colors.outlineVariant
        // Remembered, so the chunks on screen are not all handed a new object, and recompose, on
        // every pass.
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

    // The widest line sets the extent, not the composed lines: a width that followed the visible chunks would
    // drag the sideways position as the list scrolls. Capped under Compose's layout-dimension limit.
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

/**
 * One chunk of 40 lines, coloured as source if it has a language, its numbers drawn beside it if
 * asked for.
 */
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
    // Measured once per layout, not per frame: which row each line starts on and what its number
    // measures to, keyed on exactly what already drives lineStarts (plus the layout itself, which
    // arrives after). Drawing then only reads the marks and paints them.
    val marks = remember(layout, lineStarts, firstLine, gutter) {
        val laidOut = layout
        if (laidOut == null || gutter == null) {
            emptyList<GutterMark>()
        } else {
            val textLength = laidOut.layoutInput.text.length
            lineStarts.indices.map { k ->
                GutterMark(
                    laidOut.getLineForOffset(lineStarts[k].coerceAtMost(textLength)),
                    gutter.measurer.measure((firstLine + k).toString(), gutter.style),
                )
            }
        }
    }

    val drawGutterBehind = if (gutter == null) {
        Modifier
    } else {
        Modifier.drawBehind {
            // Nothing until the layout arrives — same frame the gutter used to start measuring on.
            val laidOut = layout
            if (laidOut != null && marks.isNotEmpty()) drawGutter(marks, laidOut, gutter)
        }
    }
    Box(
        (if (width != null) Modifier.width(width) else Modifier.fillMaxWidth())
            .then(drawGutterBehind),
    ) {
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

/** One line's number, measured when the chunk is laid out: the row it labels and the text itself. */
private class GutterMark(val row: Int, val number: TextLayoutResult)

/** Paints [marks] and the divider — no measuring, no lookups; that work happened once, in TextChunk. */
private fun DrawScope.drawGutter(marks: List<GutterMark>, layout: TextLayoutResult, gutter: Gutter) {
    val top = ChunkVerticalPadding.toPx()
    for (mark in marks) {
        drawText(
            mark.number,
            topLeft = Offset(gutter.widthPx - gutter.gapPx - mark.number.size.width, top + layout.getLineTop(mark.row)),
        )
    }
    val dividerX = gutter.widthPx - gutter.gapPx / 2
    drawLine(gutter.dividerColor, Offset(dividerX, 0f), Offset(dividerX, size.height), strokeWidth = 1.dp.toPx())
}

/**
 * Offset of each line's first character. A final chunk ending in a newline has an empty line after it,
 * which editors leave unnumbered, so [dropEmptyTail] omits it.
 */
private fun lineStartOffsets(text: String, dropEmptyTail: Boolean): IntArray {
    var count = 1
    for (c in text) if (c == '\n') count++
    val starts = IntArray(count)
    var next = 1
    for (i in text.indices) if (text[i] == '\n') starts[next++] = i + 1
    return if (dropEmptyTail && count > 1 && text.endsWith('\n')) starts.copyOf(count - 1) else starts
}

/**
 * Holds the column under the pinch fixed when unwrapped text rescales; the other anchors only know
 * about rows.
 */
private class SidewaysZoomAnchor(private val scroll: ScrollState) : ZoomAnchor {

    private var target: Float? = null

    override fun onCommit(commit: ScaleCommit) {
        target = null
        val focal = commit.focal
        if (commit.ratio == 1f || focal == Offset.Unspecified) return
        // The focal point is a viewport coordinate, so the same growth rule as a row applies.
        target = anchoredScroll(scroll.value.toFloat(), focal.x, commit)
    }

    /**
     * The new width lands a measure after the commit, and `maxValue` lags it, so this chases it for
     * a few frames.
     */
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
    position: PositionHooks = PositionHooks.None,
    ttsBlock: Int? = null,
) {
    val units = remember(blocks) { officeUnits(blocks) }
    // One sideways scroll per table, shared by every band of it, so dragging any band moves the
    // whole table; saved per block index, what the single per-item scroll was before the split.
    val tableScrolls = List(blocks.size) { index ->
        if (blocks[index] is OfficeBlock.Table) {
            rememberSaveable(index, saver = ScrollState.Saver) { ScrollState(0) }
        } else {
            null
        }
    }

    val restoreTarget = rememberRestoredTarget(LocationKind.BLOCK, blocks.size, position)
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = restoreTarget?.takeIf { blocks.isNotEmpty() }?.let { units.firstUnitOfBlock[it.unit] }
            ?: 0,
    )
    val geometry = rememberLazyScrollGeometry(listState)
    // A stored location names a block; a table is several band units, so restore splits one stored
    // fraction across the bands and sampling folds the visible band back. Identity for every other
    // block, so the mapping round-trips exactly and plain viewers pass no hooks at all.
    LazyPositionEffects(
        listState,
        LocationKind.BLOCK,
        blocks.size,
        zoom,
        position,
        restoreTarget,
        unitOfBlock = units::splitAcrossBands,
        blockOfUnit = units::joinBands,
    )

    // The outline names a heading by its block index, so the jump lands on the block's first unit.
    LaunchedEffect(jumpTo) {
        val target = jumpTo?.index ?: return@LaunchedEffect
        if (target in blocks.indices) listState.scrollToItem(units.firstUnitOfBlock[target])
    }

    // Follow-along: the block being read is revealed the same way, unless it is already on screen.
    LaunchedEffect(ttsBlock) {
        val target = ttsBlock ?: return@LaunchedEffect
        if (target in blocks.indices) listState.scrollToItemUnlessVisible(units.firstUnitOfBlock[target])
    }

    // A hit names its block; a table is several units now, so it scrolls to the band holding the
    // active occurrence. The band's own text then scrolls to the occurrence, as every block did.
    val reveal = remember(activeHit, activeOccurrence, findQuery) { RevealTicket() }
    LaunchedEffect(reveal) {
        activeHit?.let { listState.scrollToItemUnlessVisible(units.unitOfMatch(blocks, it, findQuery, activeOccurrence)) }
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
                    items(units.size, key = { it }) { unitIndex ->
                        val unit = units[unitIndex]
                        val block = blocks[unit.block]
                        val isActive = unit.block == activeHit
                        val highlight = findHighlightFor(findQuery, activeOccurrence = activeOccurrence.takeIf { isActive })
                        val band = unit.rows
                        if (band != null && block is OfficeBlock.Table) {
                            OfficeTableView(
                                block,
                                firstRow = band.first,
                                rowCount = band.last - band.first + 1,
                                hScroll = tableScrolls[unit.block] ?: rememberScrollState(),
                                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                                highlight = highlight,
                                reveal = reveal.takeIf { isActive },
                            )
                        } else {
                            OfficeBlockView(
                                block,
                                Modifier.fillMaxWidth(),
                                highlight = highlight,
                                reveal = reveal.takeIf { isActive },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** One lazy item: the block it draws, or a table band — the rows of its slice. */
private class OfficeUnit(val block: Int, val rows: IntRange?)

/**
 * The items OfficeViewer lays out, plus the maps every block-index consumer jumps through: the
 * outline, a restored position and the find reveal all name blocks, and one table is several units.
 */
private class OfficeUnits(
    val units: List<OfficeUnit>,
    /** First unit of each block, so a jump to a block lands on the band that starts it. */
    val firstUnitOfBlock: IntArray,
    val blockOfUnit: IntArray,
    /** Units per block — more than one only for a table tall enough to split. */
    val bandCounts: IntArray,
) {
    val size: Int get() = units.size

    operator fun get(index: Int): OfficeUnit = units[index]

    /** A stored (block, fraction) as the unit to scroll to and the fraction within that unit. */
    fun splitAcrossBands(block: Int, fraction: Float): Pair<Int, Float> {
        val first = firstUnitOfBlock.getOrNull(block) ?: return 0 to fraction
        val bands = bandCounts[block]
        if (bands <= 1) return first to fraction
        // The fraction rides the band index, so a save inside band b restores inside band b —
        // exact for any band sizes, because the divide-back undoes the multiply-forward.
        val position = fraction.coerceIn(0f, 1f) * bands
        val band = position.toInt().coerceAtMost(bands - 1)
        return (first + band) to (position - band)
    }

    /** The inverse of [splitAcrossBands], so a save names the place a restore returns to. */
    fun joinBands(unit: Int, within: Float): Pair<Int, Float> {
        val block = blockOfUnit.getOrNull(unit) ?: return unit to within
        val bands = bandCounts[block]
        if (bands <= 1) return block to within
        return block to (unit - firstUnitOfBlock[block] + within) / bands
    }

    /** The unit a find hit lives in: its block's first unit, or a table's band of the occurrence. */
    fun unitOfMatch(blocks: List<OfficeBlock>, block: Int, query: String, occurrence: Int?): Int {
        val first = firstUnitOfBlock.getOrNull(block) ?: return 0
        val table = blocks.getOrNull(block) as? OfficeBlock.Table
        if (table == null || occurrence == null) return first
        val row = rowOfOccurrence(table.rows, occurrence, query) ?: return first
        val band = bandBoundaries(table.rows, TABLE_BAND_ROWS).indexOfFirst { row in it }
        return first + band.coerceAtLeast(0)
    }
}

/** A table splits into band units; every other block is one unit, exactly as it was. */
private fun officeUnits(blocks: List<OfficeBlock>): OfficeUnits {
    val units = ArrayList<OfficeUnit>()
    val firstUnit = IntArray(blocks.size)
    val bandCounts = IntArray(blocks.size)
    blocks.forEachIndexed { blockIndex, block ->
        firstUnit[blockIndex] = units.size
        if (block is OfficeBlock.Table) {
            val bands = bandBoundaries(block.rows, TABLE_BAND_ROWS)
            bandCounts[blockIndex] = bands.size.coerceAtLeast(1)
            // An empty table draws nothing but still owns a unit, so every map stays total.
            if (bands.isEmpty()) {
                units += OfficeUnit(blockIndex, IntRange.EMPTY)
            } else {
                bands.forEach { units += OfficeUnit(blockIndex, it) }
            }
        } else {
            bandCounts[blockIndex] = 1
            units += OfficeUnit(blockIndex, null)
        }
    }
    return OfficeUnits(units, firstUnit, IntArray(units.size) { units[it].block }, bandCounts)
}

/**
 * The row of a table's [occurrence]-th match (0-based, reading order), counted the way the cells
 * number their matches for the highlight, or null past the last one.
 */
private fun rowOfOccurrence(rows: List<List<OfficeCell>>, occurrence: Int, query: String): Int? {
    var seen = 0
    rows.forEachIndexed { rowIndex, row ->
        for (cell in row) {
            val hits = countOccurrences(cell, query)
            if (occurrence < seen + hits) return rowIndex
            seen += hits
        }
    }
    return null
}

/** Scroll speed (dp/s) revealing the knob on text surfaces; the PDF viewer keeps its 880 default. */
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

/** Under the ~2^16 a layout dimension reaches once the other is tall; lines past it clip. */
private const val MAX_UNWRAPPED_WIDTH_PX = 60_000f
