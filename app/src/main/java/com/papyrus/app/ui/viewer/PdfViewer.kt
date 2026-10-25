package com.papyrus.app.ui.viewer

import android.graphics.Bitmap
import android.view.accessibility.AccessibilityManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.papyrus.app.R
import com.papyrus.app.data.LocationKind
import com.papyrus.app.viewer.NormRect
import com.papyrus.app.viewer.PdfLinkTarget
import com.papyrus.app.viewer.PdfPageGeometry
import com.papyrus.app.viewer.PdfPageLink
import com.papyrus.app.viewer.PdfPageSource
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlin.math.roundToInt

private const val LOW_RES_DIVISOR = 4
private val PAGE_HIT_BORDER = 3.dp

/** Gap between pages, and the margin above the first one, at 100%. */
private val PAGE_GAP = 8.dp

/** Each drag frame's size change queues a rasterisation behind the source's mutex. */
private const val RESIZE_SETTLE_MILLIS = 150L

/** The keyboard animates in over a few hundred ms, resizing the viewport each frame. */
private const val VIEWPORT_SETTLE_MILLIS = 150L

/** Zoom feeds the cached render width; back is handled here. */
@Composable
internal fun PdfViewer(
    source: PdfPageSource,
    aspectRatios: List<Float>,
    zoom: ZoomState,
    selectionState: PdfSelectionState,
    modifier: Modifier = Modifier,
    nightMode: Boolean = false,
    pageHits: List<Int> = emptyList(),
    pageMatches: Map<Int, List<List<NormRect>>> = emptyMap(),
    /** The reader's own highlights, by page; unrelated to find's matches. */
    highlights: Map<Int, List<NormRect>> = emptyMap(),
    activePage: Int? = null,
    activeOccurrence: Int? = null,
    loadLinks: suspend (Int) -> List<PdfPageLink>? = { null },
    onLinkTarget: (PdfLinkTarget) -> Unit = {},
    position: PositionHooks = PositionHooks.None,
    speaking: TtsHighlight? = null,
    ttsLocked: Boolean = false,
    /** 1 at full strength, 0 gone: the animated fade of a search arrival's mark. */
    highlightAlpha: Float = 1f,
    /** Only the current match is painted: an arrival lights the one place it landed on. */
    onlyActive: Boolean = false,
) {
    val restoreTarget = rememberRestoredTarget(LocationKind.PAGE, source.pageCount, position)
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = restoreTarget?.unit ?: 0)
    val geometry = rememberLazyScrollGeometry(listState)
    val scrolling by remember { derivedStateOf { listState.isScrollInProgress } }
    // The page whose top edge has crossed the middle of the screen, not the one clipping the top.
    val centeredPage by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val center = info.viewportSize.height / 2
            info.visibleItemsInfo.lastOrNull { it.offset <= center }?.index
                ?: info.visibleItemsInfo.firstOrNull()?.index
                ?: listState.firstVisibleItemIndex
        }
    }
    val pageIndicatorVisible by rememberLingerVisible { centeredPage }
    // One state for the whole document; per-page state would reset the offset on every page change.
    val hScroll = rememberScrollState()

    // Hits repeat a page once per match; the page only needs to be known once.
    val hitPages = remember(pageHits) { pageHits.toSet() }

    // Whether a page should also carry its text for a screen reader; asked once for the document.
    val readsPages = accessibilityEnabled()

    BackHandler(enabled = selectionState.hasSelection) { selectionState.clear() }

    // A shrinking viewport (the keyboard) can leave the current match under it. Once the resize
    // settles, a shrink starts a new reveal; growing back needs none.
    var viewportShrinks by remember { mutableIntStateOf(0) }
    LaunchedEffect(listState) {
        var settledHeight = 0
        snapshotFlow { listState.layoutInfo.viewportSize.height }.collectLatest { height ->
            delay(VIEWPORT_SETTLE_MILLIS)
            if (settledHeight > 0 && height < settledHeight) viewportShrinks++
            settledHeight = height
        }
    }

    // A page can be taller than the screen, so a step to another match on it has to scroll to that
    // match. This brings the page in; the page then scrolls to its own current match, once per step.
    val reveal = remember(activePage, activeOccurrence, pageMatches, viewportShrinks) { RevealTicket() }
    LaunchedEffect(reveal) {
        activePage?.takeIf { it >= 0 && it < source.pageCount }
            ?.let { listState.scrollToItemUnlessVisible(it) }
    }

    // Read aloud, lock on: the page being read is brought in, and the page then scrolls to its own
    // line. Unlocked, the reader is left wherever they are.
    val spokenPage = speaking?.anchor
    LaunchedEffect(spokenPage, ttsLocked) {
        if (ttsLocked && spokenPage != null && spokenPage in 0 until source.pageCount) {
            listState.scrollToItemUnlessVisible(spokenPage)
        }
    }
    // Read when the line being read is placed, not while composing: the list's layout changes every frame.
    val viewportHeight = remember(listState) { { listState.layoutInfo.viewportSize.height } }

    // Each anchor reads the content under the pinch at the commit, while the layout is still the one
    // previewed; the list moves in that frame, and the rounding trim runs once per gesture.
    val listAnchor = remember(listState) { LazyZoomAnchor(listState, paddingScalesWithZoom = true) }
    ZoomAnchorEffect(zoom, listAnchor)
    LaunchedEffect(zoom.lastCommit) {
        zoom.lastCommit?.let { listAnchor.settle(zoom) }
    }

    // The horizontal axis is its own scroll state: a page wider than the screen is panned sideways.
    // It cannot scroll past the old extent until the new width is measured, so it is bridged.
    val hAnchor = remember(hScroll) { ScrollZoomAnchor(hScroll, horizontal = true) }
    ZoomAnchorEffect(zoom, hAnchor)
    LaunchedEffect(zoom.lastCommit) {
        zoom.lastCommit?.let { hAnchor.settle(zoom) }
    }

    LazyPositionEffects(listState, LocationKind.PAGE, source.pageCount, zoom, position, restoreTarget)

    BoxWithConstraints(modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant)) {
        val baseWidthPx = with(LocalDensity.current) { maxWidth.roundToPx() }
        val scale = zoom.committedScale
        // Committed scale only: widths are cache keys, so a live scale churns; exact, not stepped,
        // or releasing the fingers resizes every page by up to a step.
        val renderWidthPx = (baseWidthPx * scale).roundToInt().coerceAtLeast(1)
        // A page narrower than the viewport is centred, or zooming out slides the document sideways.
        val pageSlotWidth = with(LocalDensity.current) { maxOf(renderWidthPx, baseWidthPx).toDp() }
        // Scaled with the pages. Fixed gaps would put the layout at the new scale off the preview by
        // (ratio - 1) of a gap per page, which adds up across the pages that are in view.
        val pageGap = PAGE_GAP * scale
        // Lets a drag that leaves its page say which page it is over, from sizes alone.
        val pageGeometry = rememberUpdatedState(
            PdfPageGeometry(
                pageCount = source.pageCount,
                widthPx = renderWidthPx.toFloat(),
                gapPx = with(LocalDensity.current) { pageGap.toPx() },
                aspectOf = { aspectRatios.getOrElse(it) { DEFAULT_ASPECT } },
            ),
        )

        // A graphicsLayer scale does not clip itself, and the list's scroll clip scales with it (padded
        // past the edge for shadows), so this unscaled parent must clip or the page grows over the header.
        Box(Modifier.fillMaxSize().clipToBounds()) {
            LazyColumn(
                state = listState,
                // Both axes locked until every finger lifts, so a lingering finger cannot scroll. Sideways
                // stays free while the voice holds the page: it moves nothing the follow depends on.
                modifier = Modifier.fillMaxSize()
                    .horizontalScroll(hScroll, enabled = !zoom.isScrollLocked)
                    .zoomable(zoom)
                    .anchorBridge(hAnchor),
                // Locked to the voice, the reader cannot scroll; the list still moves for the follow.
                userScrollEnabled = !zoom.isScrollLocked && !ttsLocked,
                // Trailing padding clears the scrollbar; the bottom clears the page indicator.
                contentPadding = PaddingValues(start = 0.dp, top = pageGap, end = 20.dp, bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(pageGap),
            ) {
                items(source.pageCount, key = { it }) { index ->
                    Box(Modifier.width(pageSlotWidth), contentAlignment = Alignment.TopCenter) {
                        PdfPage(
                            source = source,
                            index = index,
                            widthPx = renderWidthPx,
                            aspect = aspectRatios.getOrElse(index) { DEFAULT_ASPECT },
                            scrolling = scrolling,
                            isHit = index in hitPages,
                            nightMode = nightMode,
                            // Only the active page knows which of its matches is current.
                            activeMatch = activeOccurrence.takeIf { index == activePage },
                            matches = pageMatches[index].orEmpty(),
                            highlights = highlights[index].orEmpty(),
                            matchAlpha = MATCH_ALPHA * highlightAlpha,
                            onlyActive = onlyActive,
                            reveal = reveal.takeIf { index == activePage },
                            selectionState = selectionState,
                            readsPages = readsPages,
                            pageGeometry = pageGeometry,
                            loadLinks = loadLinks,
                            onLinkTarget = onLinkTarget,
                            speaking = speaking?.takeIf { it.anchor == index },
                            followVoice = ttsLocked,
                            viewportHeight = viewportHeight,
                        )
                    }
                }
            }

            Box(
                Modifier
                    .align(Alignment.CenterEnd)
                    .width(ScrollbarTouchTarget)
                    .fillMaxSize()
                    // Named: the trailing lambda would bind to the last parameter, not onScrollBy.
                    .autoHideScrollbar(
                        geometry,
                        topClearance = ZoomBadgeTopClearance,
                        bottomClearance = PAGE_INDICATOR_CLEARANCE,
                        zoom = zoom,
                        onScrollBy = { delta -> if (!ttsLocked) listState.scrollBy(delta) },
                    ),
            )

            // Matches the scroll knob, alpha included. Shown on a page change, gone a beat after.
            ReadoutBadge(
                text = stringResource(R.string.viewer_page_indicator, centeredPage + 1, source.pageCount),
                visible = pageIndicatorVisible,
                transformOrigin = TransformOrigin(1f, 1f),
                textStyle = MaterialTheme.typography.labelMedium,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            )
        }
    }
}

private const val DEFAULT_ASPECT = 1.414f

/** The knob's bottom stop: the page indicator (~29dp label) plus font-scale headroom. */
private val PAGE_INDICATOR_CLEARANCE = 56.dp

/** [matches] is one rectangle list per match; [activeMatch] its index, or null if absent. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PdfPage(
    source: PdfPageSource,
    index: Int,
    widthPx: Int,
    aspect: Float,
    scrolling: Boolean,
    isHit: Boolean,
    nightMode: Boolean,
    activeMatch: Int?,
    matches: List<List<NormRect>>,
    highlights: List<NormRect>,
    matchAlpha: Float,
    onlyActive: Boolean,
    reveal: RevealTicket?,
    selectionState: PdfSelectionState,
    /** An accessibility service is on, so the page should have its text read for it. */
    readsPages: Boolean,
    pageGeometry: State<PdfPageGeometry>,
    loadLinks: suspend (Int) -> List<PdfPageLink>?,
    onLinkTarget: (PdfLinkTarget) -> Unit,
    speaking: TtsHighlight?,
    followVoice: Boolean,
    viewportHeight: () -> Int,
) {
    // Keyed on page identity only; keying on widthPx blanked the page on every quantisation step.
    var bitmap by remember(source, index) { mutableStateOf<Bitmap?>(source.cached(index, widthPx)) }

    // Width of the last rasterisation; until it settles a new width is scaled.
    var settledWidthPx by remember(source, index) { mutableIntStateOf(widthPx) }
    LaunchedEffect(source, index, widthPx) {
        if (widthPx == settledWidthPx) return@LaunchedEffect
        delay(RESIZE_SETTLE_MILLIS)
        settledWidthPx = widthPx
    }

    LaunchedEffect(source, index, settledWidthPx, scrolling) {
        if (scrolling) {
            // Flinging: render only a missing bitmap at a fraction of the width; a rescale decodes.
            if (bitmap == null) {
                source.render(index, (settledWidthPx / LOW_RES_DIVISOR).coerceAtLeast(1))?.let { bitmap = it }
            }
        } else {
            // `?.let` keeps the current bitmap when the source is closing, so a page never flashes.
            source.render(index, settledWidthPx)?.let { bitmap = it }
        }
    }

    // Measured, not derived from widthPx and aspect: the box is what a rectangle is relative to.
    var pageSize by remember { mutableStateOf(IntSize.Zero) }
    val requester = remember { BringIntoViewRequester() }
    val margin = with(LocalDensity.current) { RevealMargin.toPx() }
    val activeRect = activeMatch?.let { matches.getOrNull(it) }?.firstOrNull()
    LaunchedEffect(activeRect, reveal) {
        if (activeRect == null || reveal == null || reveal.consumed) return@LaunchedEffect
        val size = snapshotFlow { pageSize }.first { it != IntSize.Zero }
        reveal.consumed = true
        requester.bringIntoView(
            Rect(
                left = activeRect.left * size.width - margin,
                top = activeRect.top * size.height - margin,
                right = activeRect.right * size.width + margin,
                bottom = activeRect.bottom * size.height + margin,
            ),
        )
    }

    // A page a selection spans reads its own text as it scrolls in: only shown pages cost.
    val current = selectionState.selection
    val span = current?.spanOn(index)
    val pageText = selectionState.textOf(index)
    LaunchedEffect(selectionState, index, span != null) {
        if (span != null) selectionState.ensureText(index)
    }
    // With an accessibility service running, a shown page reads its text too: a rendered bitmap is
    // otherwise one bare "Page N" stop. The same cache feeds it, so a page listened to costs nothing
    // extra to select from.
    LaunchedEffect(selectionState, index, readsPages) {
        if (readsPages) selectionState.ensureText(index)
    }
    val selectionRects = remember(span, pageText) {
        if (span != null && pageText != null) pageText.selectionRects(span.from, span.to) else emptyList()
    }

    // Read aloud: the voice read this page's own text, so its glyph boxes place the sentence and word.
    // The text is read once for the page being read, through the same cache selection uses.
    LaunchedEffect(selectionState, index, speaking != null) {
        if (speaking != null) selectionState.ensureText(index)
    }
    val spokenRange = rememberSpokenRange(speaking, pageText?.text, followVoice, viewportHeight)
    val spokenRects = remember(spokenRange, pageText) {
        if (spokenRange != null && pageText != null) spokenRange.rectsOn(pageText) else null
    }
    // The line is followed when the voice reaches a new one, keyed on the line's top and not the word,
    // so a word that stays on its line never interrupts the scroll already under way. Until the page's
    // text arrives the page's top stands in for the line.
    val spokenLine = spokenRects?.let { it.word.firstOrNull() ?: it.sentence.firstOrNull() }
    val followMargin = with(LocalDensity.current) { FollowTopMargin.toPx() }
    LaunchedEffect(followVoice && speaking != null, spokenLine?.top) {
        if (!followVoice || speaking == null) return@LaunchedEffect
        val size = snapshotFlow { pageSize }.first { it != IntSize.Zero }
        val top = (spokenLine?.top ?: 0f) * size.height
        val bottom = (spokenLine?.bottom ?: 0f) * size.height
        val window = followWindow(bottom - top, viewportHeight().toFloat(), followMargin)
        requester.bringIntoView(Rect(0f, top - window.above, size.width.toFloat(), top + window.below))
    }

    // Links are per page and read only once it has settled, so a fling pays for none of them.
    var links by remember(source, index) { mutableStateOf<List<PdfPageLink>>(emptyList()) }
    LaunchedEffect(source, index, scrolling) {
        if (!scrolling) links = loadLinks(index).orEmpty()
    }
    val linkRects = remember(links) { links.map { it.rect } }
    val liveLinks = rememberUpdatedState(links)
    // Read through a state, so the gesture's long-lived pointer handler never holds a stale callback.
    val liveOnLink = rememberUpdatedState(onLinkTarget)

    val density = LocalDensity.current
    val handleRadius = with(density) { HandleRadius.toPx() }
    val handles = rememberUpdatedState(
        remember(current, selectionRects, pageSize, handleRadius) {
            pageHandles(current, index, selectionRects, pageSize, handleRadius)
        },
    )

    Box(
        Modifier
            // The exact render width, not a fraction of the slot, which would clamp if they differ.
            .width(with(density) { widthPx.toDp() })
            .aspectRatio(1f / aspect)
            // The paper follows the page: night mode inverts the page, so the paper is black too.
            .background(if (nightMode) Color.Black else Color.White)
            .onSizeChanged { pageSize = it }
            .bringIntoViewRequester(requester)
            .pdfSelectionGestures(
                state = selectionState,
                page = index,
                geometry = pageGeometry,
                handles = handles,
                links = liveLinks,
                pageSize = rememberUpdatedState(pageSize),
                onLink = { target -> liveOnLink.value(target) },
                haptics = LocalHapticFeedback.current,
                reachPx = with(density) { SelectionReach.toPx() },
                grabRadiusPx = with(density) { HandleGrabRadius.toPx() },
                linkSlopPx = with(density) { LinkTapSlop.toPx() },
            ),
    ) {
        bitmap?.let { bmp ->
            val spoken = pageText?.text?.takeIf { readsPages && it.isNotBlank() }
            Image(
                bitmap = remember(bmp) { bmp.asImageBitmap() },
                contentDescription = if (spoken == null) {
                    stringResource(R.string.cd_pdf_page, index + 1)
                } else {
                    stringResource(R.string.cd_pdf_page_with_text, index + 1, spoken)
                },
                contentScale = ContentScale.FillBounds,
                // Filtered as it is drawn, so the page is not re-rendered.
                colorFilter = if (nightMode) PdfNightMode.Invert else null,
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (highlights.isNotEmpty()) {
            // Right over the page and under everything else, so a match, a link or the selection reads through it.
            Canvas(Modifier.fillMaxSize()) { drawHighlights(highlights) }
        }
        if (linkRects.isNotEmpty()) {
            // Under the matches and the selection, so a link never hides a find highlight.
            val linkColor = MaterialTheme.colorScheme.primary
            Canvas(Modifier.fillMaxSize()) { drawLinks(linkRects, linkColor) }
        }
        if (spokenRects != null) {
            // Under the matches and the selection, so neither is hidden by the voice passing over it.
            val sentenceColor = spokenSentenceColor()
            val wordColor = spokenWordColor()
            val corner = with(LocalDensity.current) { 2.dp.toPx() }
            Canvas(Modifier.fillMaxSize()) {
                drawSpokenRects(spokenRects.sentence, sentenceColor, corner)
                drawSpokenRects(spokenRects.word, wordColor, corner)
            }
        }
        if (matches.isNotEmpty() && (!onlyActive || activeMatch != null)) {
            // Matched words in page-relative units, so they stay put at any width. Translucent.
            val otherColor = OtherMatchColor.copy(alpha = matchAlpha)
            val activeColor = ActiveMatchColor.copy(alpha = matchAlpha)
            val corner = with(LocalDensity.current) { 2.dp.toPx() }
            Canvas(Modifier.fillMaxSize()) {
                fun drawMatch(rects: List<NormRect>, color: Color) {
                    for (rect in rects) {
                        drawRoundRect(
                            color = color,
                            topLeft = Offset(rect.left * size.width, rect.top * size.height),
                            size = Size((rect.right - rect.left) * size.width, (rect.bottom - rect.top) * size.height),
                            cornerRadius = CornerRadius(corner),
                        )
                    }
                }
                if (!onlyActive) matches.forEachIndexed { i, rects -> if (i != activeMatch) drawMatch(rects, otherColor) }
                // Last, so the current match sits on top of any neighbour it touches.
                activeMatch?.let { matches.getOrNull(it) }?.let { drawMatch(it, activeColor) }
            }
        }
        if (selectionRects.isNotEmpty()) {
            val selectionColor = MaterialTheme.colorScheme.primary
            Canvas(Modifier.fillMaxSize()) {
                drawSelection(selectionRects, handles.value, selectionColor, handleRadius)
            }
        }
        // No rectangles to show (past the extractor's budget), so a border is the only signal. That
        // is the page when it has none at all, and the current match when only it is missing.
        val activeMissing = activeMatch != null && matches.getOrNull(activeMatch).isNullOrEmpty()
        val needsBorder = if (matches.isEmpty()) isHit else activeMissing
        if (needsBorder) {
            Surface(
                Modifier.fillMaxSize(),
                color = Color.Transparent,
                border = BorderStroke(
                    if (activeMatch != null) ACTIVE_HIT_BORDER else PAGE_HIT_BORDER,
                    if (activeMatch != null) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary,
                ),
            ) {}
        }
    }
}

private const val MATCH_ALPHA = 0.5f

private val ACTIVE_HIT_BORDER = 5.dp

/**
 * Whether a screen reader is running. Asked of the system service rather than Compose's own
 * accessibility manager, which exposes only timeout calculations. The answer is re-read whenever
 * the service turns on or off, so pages start carrying text without the document being reopened.
 */
@Composable
private fun accessibilityEnabled(): Boolean {
    val context = LocalContext.current
    val manager = remember(context) { context.getSystemService(AccessibilityManager::class.java) }
    var enabled by remember(manager) { mutableStateOf(manager?.isEnabled == true) }
    DisposableEffect(manager) {
        if (manager == null) return@DisposableEffect onDispose {}
        val listener = object : AccessibilityManager.AccessibilityStateChangeListener {
            override fun onAccessibilityStateChanged(isEnabled: Boolean) {
                enabled = isEnabled
            }
        }
        manager.addAccessibilityStateChangeListener(listener)
        enabled = manager.isEnabled
        onDispose { manager.removeAccessibilityStateChangeListener(listener) }
    }
    return enabled
}
