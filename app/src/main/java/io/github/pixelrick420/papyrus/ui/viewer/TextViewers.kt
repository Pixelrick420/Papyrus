package io.github.pixelrick420.papyrus.ui.viewer

import android.text.Spannable
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.style.BackgroundColorSpan
import android.text.style.CharacterStyle
import android.text.style.ForegroundColorSpan
import android.text.style.UpdateAppearance
import android.util.TypedValue
import android.widget.TextView
import io.github.pixelrick420.papyrus.viewer.LatexRange
import io.github.pixelrick420.papyrus.viewer.LatexSpanFactory
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
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
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.pixelrick420.papyrus.R
import io.github.pixelrick420.papyrus.data.LocationKind
import io.github.pixelrick420.papyrus.viewer.BlockDirection
import io.github.pixelrick420.papyrus.viewer.CodeLanguage
import io.github.pixelrick420.papyrus.viewer.LexStates
import io.github.pixelrick420.papyrus.viewer.MARKDOWN_BASE_TEXT_SP
import io.github.pixelrick420.papyrus.viewer.MarkdownFootnotes
import io.github.pixelrick420.papyrus.viewer.MarkdownStylePlugin
import io.github.pixelrick420.papyrus.viewer.OfficeBlock
import io.github.pixelrick420.papyrus.viewer.OfficeCell
import io.github.pixelrick420.papyrus.viewer.SyntaxHighlighter
import io.github.pixelrick420.papyrus.viewer.TEXT_LINES_PER_CHUNK
import io.github.pixelrick420.papyrus.viewer.numberedLines
import io.github.pixelrick420.papyrus.viewer.direction
import io.noties.markwon.Markwon
import io.noties.markwon.core.spans.LinkSpan
import io.noties.markwon.ext.tables.TableRowSpan
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

@Composable
private fun ZoomableScrollArea(
    geometry: State<ScrollGeometry>,
    onScrollBy: suspend (Float) -> Unit,
    zoom: ZoomState,
    modifier: Modifier = Modifier,
    scrubPreview: (@Composable BoxScope.(scrubbing: Boolean) -> Unit)? = null,
    content: @Composable (Modifier) -> Unit,
) {
    var scrubbing by remember { mutableStateOf(false) }
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
                    onGrab = { scrubbing = it },
                ),
        )
        scrubPreview?.invoke(this, scrubbing)
    }
}

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

@Composable
fun MarkdownViewer(
    markwon: Markwon,
    source: String,
    text: Spanned,
    findQuery: String,
    zoom: ZoomState,
    modifier: Modifier = Modifier,
    position: PositionHooks = PositionHooks.None,
    speaking: TtsHighlight? = null,
    ttsLocked: Boolean = false,
    arrival: ArrivalHighlight? = null,
    highlightAlpha: Float = 1f,
    latexFactory: LatexSpanFactory? = null,
) {
    val scroll = rememberScrollState()
    val geometry = rememberScrollGeometry(scroll)
    val textColor = MaterialTheme.colorScheme.onSurface.toArgb()
    val linkColor = MaterialTheme.colorScheme.primary.toArgb()
    // The same palette as a source file, picked the same way, so a fenced block matches the code viewer.
    val surface = MaterialTheme.colorScheme.surface
    val codeStyles = remember(surface) { SyntaxPalette.forSurface(surface).codeStyles() }
    val baseTextPx = with(LocalDensity.current) { MARKDOWN_BASE_SP.toPx() }
    // Pixels, not sp: `TextView.textSize` reads sp, so handing it `toPx()` scaled the body by the
    // density twice.
    val textPx = baseTextPx * zoom.committedScale
    val rendered = rememberRenderedMarkdown(markwon, source, text, textPx, textColor, linkColor, latexFactory)
    val highlighted = rememberHighlightedText(rendered, findQuery)
    val arrivalRange = remember(text, arrival?.term) {
        arrival?.term?.let { findMatchRanges(text, it).firstOrNull() }
    }
    val arrivalSpans = remember { ArrivalSpans() }
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
    val spoken = rememberSpokenRange(speaking, text, ttsLocked) { scroll.viewportSize }
    val spokenSpans = remember { SpokenSpans() }
    val sentenceColor = spokenSentenceColor().toArgb()
    val wordColor = spokenWordColor().toArgb()
    // Scroll only when the voice reaches a new line so same-line words don't cancel active scrolls.
    val followScope = rememberCoroutineScope()
    val followMemo = remember { MarkdownFollowMemo() }
    val followMarginPx = with(LocalDensity.current) { FollowTopMargin.toPx() }
    val spokenOffset = spoken?.let { it.word?.first ?: it.sentence?.first }
    LaunchedEffect(spokenOffset, ttsLocked) {
        if (spokenOffset == null || !ttsLocked) {
            // Unlocked, or nothing is being read: locking again starts from nothing and follows at once.
            followMemo.line = -1
            return@LaunchedEffect
        }
        snapshotFlow { scroll.maxValue }.first { it != Int.MAX_VALUE }
        val layout = textState.view?.layout ?: return@LaunchedEffect
        val line = layout.getLineForOffset(spokenOffset.coerceIn(0, layout.text.length))
        if (line == followMemo.line) return@LaunchedEffect
        followMemo.line = line
        val lineTop = layout.getLineTop(line)
        val viewport = scroll.viewportSize.toFloat()
        val window = followWindow((layout.getLineBottom(line) - lineTop).toFloat(), viewport, followMarginPx)
        val delta = followScrollDelta(contentTopPx + lineTop - scroll.value, window, viewport)
        if (delta != 0f) {
            val target = (scroll.value + delta).roundToInt().coerceIn(0, scroll.maxValue)
            followScope.launch { scroll.animateScrollTo(target, spring(stiffness = Spring.StiffnessMediumLow)) }
        }
    }
    val opener = LocalLinkOpener.current
    val movement = remember(opener, scroll, contentTopPx, followMarginPx) {
        GuardedLinkMovementMethod { url ->
            val offset = textState.text?.footnoteOffset(url)
            val layout = textState.view?.layout
            if (offset == null || layout == null) {
                opener(url)
            } else {
                val line = layout.getLineForOffset(offset.coerceIn(0, layout.text.length))
                val target = (contentTopPx + layout.getLineTop(line) - followMarginPx).roundToInt().coerceIn(0, scroll.maxValue)
                followScope.launch { scroll.animateScrollTo(target, spring(stiffness = Spring.StiffnessMediumLow)) }
            }
        }
    }

    // A commit re-wraps the text, so the anchor pins the paragraph at the commit; the text reaches its
    // new height a measure later, so the scroll follows and the bridge paints between.
    val anchor = remember(scroll) { ScrollZoomAnchor(scroll, horizontal = false) }
    ZoomAnchorEffect(zoom, anchor)
    LaunchedEffect(zoom.lastCommit) {
        zoom.lastCommit?.let { anchor.settle(zoom) }
    }

    ZoomableScrollArea(geometry, { if (!ttsLocked) scroll.scrollBy(it) }, zoom, modifier) { scrollModifier ->
        ScaledTypography(zoom.committedScale) {
            SelectionContainer {
                Column(
                    scrollModifier
                        .verticalScroll(scroll, enabled = !zoom.isScrollLocked && !ttsLocked)
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
                                exposeMarkdownSemantics()
                            }
                        },
                        update = { view ->
                            view.setTextColor(textColor)
                            view.setLinkTextColor(linkColor)
                            view.movementMethod = movement
                            // Committed scale, not live: the preview layer paints the in-flight gesture, and
                            // resizing per frame relaid out the whole document.
                            view.setTextSize(TypedValue.COMPLEX_UNIT_PX, textPx)
                            // Code colours follow the theme, which can change while the text stays.
                            markwon.getPlugin(MarkdownStylePlugin::class.java)?.applyTheme(view, codeStyles)
                            // Only on a change: setting text kills a copy in progress; the view too, since
                            // `ScaledTypography` composes differently at 1x.
                            if (textState.view !== view || textState.text !== highlighted) {
                                textState.view = view
                                textState.text = highlighted
                                markwon.setParsedMarkdown(view, highlighted)
                                view.refreshMarkdownSemantics()
                            }
                            // After any re-set of the text, which drops every span it had.
                            spokenSpans.show(view, spoken, sentenceColor, wordColor)
                            arrivalSpans.show(view, arrivalRange, highlightAlpha, textColor)
                        },
                    )
                }
            }
        }
    }

    val scope = rememberCoroutineScope()
    LaunchedEffect(latexFactory, highlighted, textPx, textColor) {
        val factory = latexFactory ?: return@LaunchedEffect
        if (factory.ranges.isEmpty()) return@LaunchedEffect

        val deferreds = factory.createSpans(scope, textPx, textColor)
        for (deferred in deferreds) {
            val (range, span) = deferred.await()
            val view = textState.view ?: continue
            // view.text may be an immutable SpannableString; build a mutable copy so we can swap
            // the placeholder for the RaTeXSpan, then set it back on the view.
            val viewText = SpannableStringBuilder(view.text as Spanned)
            val placeholders = viewText.getSpans(0, viewText.length, LatexPlaceholderSpan::class.java)
            var applied = false
            for (placeholder in placeholders) {
                if (placeholder.range == range) {
                    val start = viewText.getSpanStart(placeholder)
                    val end = viewText.getSpanEnd(placeholder)
                    viewText.removeSpan(placeholder)
                    if (span != null) {
                        viewText.setSpan(span, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                    applied = true
                    break
                }
            }
            if (applied) {
                view.setText(viewText)
                view.postInvalidate()
            }
        }
    }
}

private fun Spanned.footnoteOffset(url: String): Int? =
    MarkdownFootnotes.target(getSpans(0, length, LinkSpan::class.java).map { getSpanStart(it) to it.link }, url)

// Plain fields, not Compose state: the update block runs during layout, and state would recompose
// on every set.
private class MarkdownTextState {
    var view: TextView? = null
    var text: Spanned? = null
}

private class MarkdownFollowMemo {
    var line = -1
}

// Moved in place, so a new word costs a repaint and not the re-setting of the whole document, which would also drop a selection in progress.
private class SpokenSpans {
    private val sentence = SpokenSpan()
    private val word = SpokenSpan()
    private var shown = false

    fun show(view: TextView, range: SpokenRange?, sentenceColor: Int, wordColor: Int) {
        // Nothing read now and nothing drawn before: the common case costs nothing.
        if (range == null && !shown) return
        shown = range != null
        val text = view.text as? Spannable ?: return
        sentence.color = sentenceColor
        word.color = wordColor
        // Taken off and put back sentence first, so the word is always the span drawn on top.
        text.removeSpan(sentence)
        text.removeSpan(word)
        attach(text, sentence, range?.sentence)
        attach(text, word, range?.word)
        view.invalidate()
    }

    private fun attach(text: Spannable, span: SpokenSpan, range: IntRange?) {
        if (range == null) return
        val from = range.first.coerceIn(0, text.length)
        val to = (range.last + 1).coerceIn(from, text.length)
        if (to > from) text.setSpan(span, from, to, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
}

private class SpokenSpan : CharacterStyle(), UpdateAppearance {
    var color = 0

    override fun updateDrawState(ds: TextPaint) {
        ds.bgColor = color
    }
}

private class FadingMatchSpan : CharacterStyle(), UpdateAppearance {
    var background = 0
    var foreground = 0

    override fun updateDrawState(ds: TextPaint) {
        ds.bgColor = background
        ds.color = foreground
    }
}

// Moved in place and only its paint changes, so a fade costs a repaint rather than re-setting the document, which would drop a selection in progress.
private class ArrivalSpans {
    private val span = FadingMatchSpan()
    private var attachedTo: Spannable? = null

    fun show(view: TextView, range: IntRange?, alpha: Float, baseTextColor: Int) {
        val text = view.text as? Spannable
        // A re-set text replaces the Spannable and takes the old span with it; otherwise the span is
        // moved to the new range, or taken off when there is nothing left to mark.
        val hadSpan = attachedTo != null
        attachedTo?.removeSpan(span)
        attachedTo = null
        if (text == null || range == null) {
            if (hadSpan) view.invalidate()
            return
        }
        val faded = alpha.coerceIn(0f, 1f)
        val from = range.first.coerceIn(0, text.length)
        val to = (range.last + 1).coerceIn(from, text.length)
        if (to <= from || faded <= 0f) {
            view.invalidate()
            return
        }
        span.background = ActiveMatchColor.copy(alpha = faded).toArgb()
        span.foreground = lerp(MatchTextColor, Color(baseTextColor), 1f - faded).toArgb()
        text.setSpan(span, from, to, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        attachedTo = text
        view.invalidate()
    }
}

private class LatexPlaceholderSpan(val range: LatexRange)

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
    latexFactory: LatexSpanFactory?,
): SpannableStringBuilder {
    val hasTables = remember(parsed) { parsed.getSpans(0, parsed.length, TableRowSpan::class.java).isNotEmpty() }
    val base = if (hasTables) {
        remember(markwon, source, textPx, textColor, linkColor) { markwon.toMarkdown(source) }
    } else {
        parsed
    }
    // Scans rendered $$...$$ ranges because Markwon output offsets diverge from raw source offsets.
    val builder = SpannableStringBuilder(base)
    val renderedText = builder.toString()
    val renderedRanges = Regex("(?s)\\$\\$(.+?)\\$\\$").findAll(renderedText).map { match ->
        match.range.first to (match.range.last + 1)
    }.toList()
    val factoryRanges = latexFactory?.ranges ?: emptyList()
    // Zip in order; iterate in reverse so edits do not shift offsets of earlier ranges.
    renderedRanges.zip(factoryRanges).reversed().forEach { pair ->
        val (renderedPos, factoryRange) = pair
        val (renderedStart, renderedEnd) = renderedPos
        if (renderedEnd > builder.length) return@forEach
        builder.setSpan(
            LatexPlaceholderSpan(factoryRange),
            renderedStart,
            renderedEnd,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
    }
    return builder
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
    for (range in findMatchRanges(this, query)) {
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

// None of these change the chunk list, so a settings toggle redraws rows and re-measures nothing read from the file.
@Immutable
class PlainTextOptions(
    val wrapLines: Boolean = true,
    val hyphenate: Boolean = false,
    val showLineNumbers: Boolean = false,
    val language: CodeLanguage? = null,
    val lexStates: LexStates? = null,
    val lineCount: Int = 0,
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
    speaking: TtsHighlight? = null,
    ttsLocked: Boolean = false,
    highlightAlpha: Float = 1f,
    onlyActive: Boolean = false,
) {
    val restoreTarget = rememberRestoredTarget(LocationKind.CHUNK, chunks.size, position)
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = restoreTarget?.unit ?: 0)
    val geometry = rememberLazyScrollGeometry(listState)
    LazyPositionEffects(listState, LocationKind.CHUNK, chunks.size, zoom, position, restoreTarget)

    // A large file's chunks are decoded the first time they are asked for. Asking for the ones around the
    // screen here, off the main thread, means a scroll finds them already held.
    LaunchedEffect(chunks, listState) {
        snapshotFlow { listState.firstVisibleItemIndex }.collect { first ->
            withContext(Dispatchers.IO) {
                for (i in (first - PREFETCH_BEHIND).coerceAtLeast(0)..(first + PREFETCH_AHEAD).coerceAtMost(chunks.lastIndex)) chunks[i]
            }
        }
    }

    // A chunk is 40 lines, so it can be taller than the screen. This brings the chunk in; its own
    // `FindText` then scrolls to the current match inside it, once per step.
    val reveal = remember(activeHit, activeOccurrence, findQuery) { RevealTicket() }
    LaunchedEffect(reveal) {
        activeHit?.let { listState.scrollToItemUnlessVisible(it) }
    }

    // Read aloud, lock on: the chunk being read is brought in the way a find result is, and its own
    // text then scrolls to the line. Unlocked, the reader is left wherever they are.
    val spokenChunk = speaking?.anchor
    LaunchedEffect(spokenChunk, ttsLocked) {
        if (ttsLocked && spokenChunk != null && spokenChunk in chunks.indices) {
            listState.scrollToItemUnlessVisible(spokenChunk)
        }
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

    val numbered = remember(chunks, options.lineCount) {
        numberedLines(options.lineCount, chunks.lastOrNull()?.endsWith('\n') == true)
    }
    val scrubPreview: @Composable BoxScope.(Boolean) -> Unit = { scrubbing ->
        LineScrubPreview(listState, chunks, numbered, scrubbing)
    }
    ZoomableScrollArea(geometry, { if (!ttsLocked) listState.scrollBy(it) }, zoom, modifier, scrubPreview) { scrollModifier ->
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
                speaking = speaking,
                ttsLocked = ttsLocked,
                highlightAlpha = highlightAlpha,
                onlyActive = onlyActive,
                modifier = scrollModifier,
            )
        }
    }
}

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
    speaking: TtsHighlight?,
    ttsLocked: Boolean,
    highlightAlpha: Float,
    onlyActive: Boolean,
    modifier: Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)
    // Hyphenation needs the paragraph break strategy. Only the text gets it: the gutter's numbers never wrap.
    val bodyStyle = remember(textStyle, options.hyphenate) {
        if (options.hyphenate) textStyle.copy(lineBreak = LineBreak.Paragraph, hyphens = Hyphens.Auto) else textStyle
    }
    val measurer = rememberTextMeasurer(cacheSize = NUMBER_LAYOUT_CACHE)
    // Monospace, so one measured run gives the width of every column, in the pixels of the
    // current zoom.
    val columnPx = remember(measurer, textStyle) { measurer.measure(COLUMN_PROBE, textStyle).size.width / COLUMN_PROBE.length.toFloat() }

    // Remembered: asking a large file for its last chunk on every pass would decode it again.
    val endsWithNewline = remember(chunks) { chunks.lastOrNull()?.endsWith('\n') == true }
    val gutter = if (options.showLineNumbers) {
        val ruleGapPx = with(density) { GutterRuleGap.toPx() }
        val digits = gutterDigits(options.lineCount, endsWithNewline)
        val numberFieldPx = columnPx * digits
        val numberColor = colors.onSurfaceVariant.copy(alpha = GUTTER_ALPHA)
        val dividerColor = colors.outlineVariant
        // Remembered, so the chunks on screen are not all handed a new object, and recompose, on
        // every pass.
        remember(numberFieldPx, ruleGapPx, measurer, textStyle, numberColor, dividerColor) {
            Gutter(
                numberFieldPx = numberFieldPx,
                ruleGapPx = ruleGapPx,
                widthPx = numberFieldPx + ruleGapPx,
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
    // Read when the line being read is placed, not while composing: the list's layout changes every frame.
    val viewportHeight = remember(listState) { { listState.layoutInfo.viewportSize.height } }

    SelectionContainer {
        LazyColumn(
            if (options.wrapLines) modifier else modifier.horizontalScroll(sideways, enabled = !zoom.isScrollLocked),
            // The state the scrollbar, zoom anchor and find all read; without it the list
            // scrolls its own private state and they see an empty one.
            state = listState,
            // Locked to the voice, the reader cannot scroll; the list still moves for the follow.
            userScrollEnabled = !zoom.isScrollLocked && !ttsLocked,
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
                    highlight = findHighlightFor(
                        findQuery,
                        activeOccurrence = activeOccurrence.takeIf { isActive },
                        alpha = highlightAlpha,
                        onlyActive = onlyActive,
                    ),
                    reveal = reveal.takeIf { isActive },
                    style = bodyStyle,
                    options = options,
                    syntaxPalette = syntaxPalette,
                    gutter = gutter,
                    gutterWidth = gutterWidth,
                    width = unwrappedWidth,
                    speaking = speaking?.takeIf { it.anchor == index },
                    followVoice = ttsLocked,
                    viewportHeight = viewportHeight,
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
    speaking: TtsHighlight?,
    followVoice: Boolean,
    viewportHeight: () -> Int,
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
    // Precomputed once per layout to keep the draw pass free of text measurement allocations.
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
            // Only the chunk being read is handed the voice, so the others recompose for nothing.
            speaking = rememberSpokenRange(speaking, text, followVoice, viewportHeight),
        )
    }
}

@Immutable
private class Gutter(
    val numberFieldPx: Float,
    val ruleGapPx: Float,
    val widthPx: Float,
    val measurer: TextMeasurer,
    val style: TextStyle,
    val dividerColor: Color,
)

private class GutterMark(val row: Int, val number: TextLayoutResult)

private fun DrawScope.drawGutter(marks: List<GutterMark>, layout: TextLayoutResult, gutter: Gutter) {
    val top = ChunkVerticalPadding.toPx()
    for (mark in marks) {
        drawText(
            mark.number,
            topLeft = Offset(gutter.numberFieldPx - mark.number.size.width, top + layout.getLineTop(mark.row)),
        )
    }
    val dividerX = gutter.numberFieldPx + gutter.ruleGapPx / 2
    drawLine(gutter.dividerColor, Offset(dividerX, 0f), Offset(dividerX, size.height), strokeWidth = 1.dp.toPx())
}

private fun lineStartOffsets(text: String, dropEmptyTail: Boolean): IntArray {
    var count = 1
    for (c in text) if (c == '\n') count++
    val starts = IntArray(count)
    var next = 1
    for (i in text.indices) if (text[i] == '\n') starts[next++] = i + 1
    return if (dropEmptyTail && count > 1 && text.endsWith('\n')) starts.copyOf(count - 1) else starts
}

private class SidewaysZoomAnchor(private val scroll: ScrollState) : ZoomAnchor {

    private var target: Float? = null

    override fun onCommit(commit: ScaleCommit) {
        target = null
        val focal = commit.focal
        if (commit.ratio == 1f || focal == Offset.Unspecified) return
        target = anchoredScroll(scroll.value.toFloat(), focal.x, commit)
    }

    // The new width lands a measure after the commit, and `maxValue` lags it, so this chases it for
    // a few frames.
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
    position: PositionHooks = PositionHooks.None,
    speaking: TtsHighlight? = null,
    ttsLocked: Boolean = false,
    incomplete: Boolean = false,
    highlightAlpha: Float = 1f,
    onlyActive: Boolean = false,
    direction: BlockDirection = BlockDirection.LTR,
    pageStarts: Set<Int> = emptySet(),
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

    // Read aloud, lock on: the block being read is brought in the same way, unless it is already on
    // screen, and its own text then scrolls to the line. Unlocked, the reader is left where they are.
    val spokenBlock = speaking?.anchor
    LaunchedEffect(spokenBlock, ttsLocked) {
        if (ttsLocked && spokenBlock != null && spokenBlock in blocks.indices) {
            listState.scrollToItemUnlessVisible(units.firstUnitOfBlock[spokenBlock])
        }
    }
    // Read when the line being read is placed, not while composing: the list's layout changes every frame.
    val viewportHeight = remember(listState) { { listState.layoutInfo.viewportSize.height } }

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

    ZoomableScrollArea(geometry, { if (!ttsLocked) listState.scrollBy(it) }, zoom, modifier) { scrollModifier ->
        ScaledTypography(zoom.committedScale) {
            SelectionContainer {
                LazyColumn(
                    scrollModifier,
                    // The state the scrollbar, zoom anchor and find all read; without it the list
                    // scrolls its own private state and they see an empty one.
                    state = listState,
                    // Locked to the voice, the reader cannot scroll; the list still moves for the follow.
                    userScrollEnabled = !zoom.isScrollLocked && !ttsLocked,
                    contentPadding = PaddingValues(start = 16.dp, end = 20.dp, top = 16.dp, bottom = 16.dp),
                ) {
                    items(units.size, key = { it }) { unitIndex ->
                        val unit = units[unitIndex]
                        val block = blocks[unit.block]
                        val isActive = unit.block == activeHit
                        val highlight = findHighlightFor(
                            findQuery,
                            activeOccurrence = activeOccurrence.takeIf { isActive },
                            alpha = highlightAlpha,
                            onlyActive = onlyActive,
                        )
                        val band = unit.rows
                        // A page boundary is room above the page's first item, never an item of its own: the maps
                        // find, speech, bookmarks and a restore use to name blocks stay exactly as they were.
                        val pageBreak = if (unit.block in pageStarts && unitIndex == units.firstUnitOfBlock[unit.block]) {
                            Modifier.padding(top = PAGE_BREAK_SPACING)
                        } else {
                            Modifier
                        }
                        // The voice is in this block; a table's bands all belong to it, but only the first is followed.
                        val spokenHere = speaking?.takeIf { it.anchor == unit.block }
                        if (band != null && block is OfficeBlock.Table) {
                            SpokenBlock(
                                active = spokenHere != null,
                                follow = ttsLocked && unitIndex == units.firstUnitOfBlock[unit.block],
                                viewportHeight = viewportHeight,
                            ) {
                                OfficeTableView(
                                    block,
                                    firstRow = band.first,
                                    rowCount = band.last - band.first + 1,
                                    hScroll = tableScrolls[unit.block] ?: rememberScrollState(),
                                    modifier = pageBreak.fillMaxWidth().padding(vertical = 8.dp),
                                    highlight = highlight,
                                    reveal = reveal.takeIf { isActive },
                                )
                            }
                        } else if (block is OfficeBlock.Image) {
                            SpokenBlock(
                                active = spokenHere != null,
                                follow = ttsLocked,
                                viewportHeight = viewportHeight,
                            ) {
                                OfficeBlockView(
                                    block,
                                    pageBreak.fillMaxWidth(),
                                    highlight = highlight,
                                    reveal = reveal.takeIf { isActive },
                                )
                            }
                        } else {
                            val spokenText = rememberSpokenRange(
                                spokenHere,
                                block.spokenSource(),
                                ttsLocked,
                                viewportHeight,
                            )
                            OfficeBlockView(
                                block,
                                pageBreak.fillMaxWidth(),
                                highlight = highlight,
                                reveal = reveal.takeIf { isActive },
                                speaking = spokenText,
                                direction = block.direction ?: direction,
                            )
                        }
                    }
                    if (incomplete) {
                        item(key = INCOMPLETE_ITEM_KEY) {
                            Text(
                                text = stringResource(R.string.viewer_incomplete),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 12.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

internal class OfficeUnit(val block: Int, val rows: IntRange?)

internal class OfficeUnits(
    val units: List<OfficeUnit>,
    val firstUnitOfBlock: IntArray,
    val blockOfUnit: IntArray,
    val bandCounts: IntArray,
) {
    val size: Int get() = units.size

    operator fun get(index: Int): OfficeUnit = units[index]

    fun splitAcrossBands(block: Int, fraction: Float): Pair<Int, Float> {
        val first = firstUnitOfBlock.getOrNull(block) ?: return 0 to fraction
        val bands = bandCounts[block]
        if (bands <= 1) return first to fraction
        // The fraction rides the band index, so a save inside band b restores inside band b -
        // exact for any band sizes, because the divide-back undoes the multiply-forward.
        val position = fraction.coerceIn(0f, 1f) * bands
        val band = position.toInt().coerceAtMost(bands - 1)
        return (first + band) to (position - band)
    }

    fun joinBands(unit: Int, within: Float): Pair<Int, Float> {
        val block = blockOfUnit.getOrNull(unit) ?: return unit to within
        val bands = bandCounts[block]
        if (bands <= 1) return block to within
        return block to (unit - firstUnitOfBlock[block] + within) / bands
    }

    fun unitOfMatch(blocks: List<OfficeBlock>, block: Int, query: String, occurrence: Int?): Int {
        val first = firstUnitOfBlock.getOrNull(block) ?: return 0
        val table = blocks.getOrNull(block) as? OfficeBlock.Table
        if (table == null || occurrence == null) return first
        val row = rowOfOccurrence(table.rows, occurrence, query) ?: return first
        val band = bandBoundaries(table.rows, TABLE_BAND_ROWS).indexOfFirst { row in it }
        return first + band.coerceAtLeast(0)
    }
}

internal fun officeUnits(blocks: List<OfficeBlock>): OfficeUnits {
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
private val PAGE_BREAK_SPACING = 24.dp
private const val HIGHLIGHT_DEBOUNCE_MS = 150L
private val MARKDOWN_BASE_SP = MARKDOWN_BASE_TEXT_SP.sp
private const val LINE_SPACING_MULTIPLIER = 1.25f

private val NO_LINES = IntArray(0)
private val GutterRuleGap = 8.dp
private val ChunkVerticalPadding = 2.dp
private const val COLUMN_PROBE = "0000000000"
private const val GUTTER_ALPHA = 0.7f
private const val NUMBER_LAYOUT_CACHE = 64
private const val PREFETCH_BEHIND = 2
private const val PREFETCH_AHEAD = 8
private const val TRUNCATED_ITEM_KEY = "truncated"
private const val INCOMPLETE_ITEM_KEY = "incomplete"
private const val SIDEWAYS_SETTLE_FRAMES = 3

/** Under the ~2^16 a layout dimension reaches once the other is tall; lines past it clip. */
private const val MAX_UNWRAPPED_WIDTH_PX = 60_000f
