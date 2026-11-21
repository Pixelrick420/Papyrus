package io.github.pixelrick420.papyrus.ui.viewer

import android.graphics.Bitmap
import android.view.accessibility.AccessibilityManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.annotation.StringRes
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import io.github.pixelrick420.papyrus.R
import io.github.pixelrick420.papyrus.data.Highlight
import io.github.pixelrick420.papyrus.data.HighlightKind
import io.github.pixelrick420.papyrus.data.LocationKind
import io.github.pixelrick420.papyrus.viewer.DEFAULT_ASPECT
import io.github.pixelrick420.papyrus.viewer.NormRect
import io.github.pixelrick420.papyrus.viewer.PageBitmapSource
import io.github.pixelrick420.papyrus.viewer.PdfLinkTarget
import io.github.pixelrick420.papyrus.viewer.PdfPageGeometry
import io.github.pixelrick420.papyrus.viewer.PdfPageLink
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlin.math.roundToInt
import kotlin.math.sqrt

private const val LOW_RES_DIVISOR = 4

// Never below [floorWidthPx], the page at 100%, which has to be sharp whatever the budget says.
internal fun renderWidthFor(displayWidthPx: Int, aspect: Float, maxPixels: Int, floorWidthPx: Int): Int {
    if (aspect <= 0f || maxPixels <= 0) return displayWidthPx.coerceAtLeast(1)
    val budgetWidth = sqrt(maxPixels / aspect.toDouble()).toInt()
    return minOf(displayWidthPx, maxOf(budgetWidth, floorWidthPx)).coerceAtLeast(1)
}

/** One focus stop per slide: its number, the title as a heading, then the reading-order text and alt text. */
internal fun slideSemanticDescription(
    prefix: String,
    title: String,
    text: String?,
    altText: String?,
    hidden: Boolean,
    hiddenLabel: String,
    headingLabel: String = "(heading)",
): String {
    val sentences = mutableListOf<String>()
    if (title.isNotBlank()) sentences += "${title.trim()} $headingLabel"
    text?.trim()?.takeIf { it.isNotEmpty() }?.let(sentences::add)
    altText?.trim()?.takeIf { it.isNotEmpty() }?.let(sentences::add)
    if (hidden) sentences += hiddenLabel
    if (sentences.isEmpty()) return prefix
    return "$prefix. ${sentences.joinToString(" ", transform = ::asSentence)}"
}

private const val SENTENCE_ENDS = ".!?:;"

private fun asSentence(value: String): String =
    if (value.last() in SENTENCE_ENDS) value else "$value."

private const val CACHED_PAGES_AROUND_VIEW = 3

private val PAGE_HIT_BORDER = 3.dp

private val PAGE_GAP = 8.dp

/** Each drag frame's size change queues a rasterisation behind the source's mutex. */
private const val RESIZE_SETTLE_MILLIS = 150L

/** The keyboard animates in over a few hundred ms, resizing the viewport each frame. */
private const val VIEWPORT_SETTLE_MILLIS = 150L

@Composable
internal fun PdfViewer(
    source: PageBitmapSource,
    aspectRatios: List<Float>,
    zoom: ZoomState,
    selectionState: PdfSelectionState,
    modifier: Modifier = Modifier,
    nightMode: Boolean = false,
    pageHits: List<Int> = emptyList(),
    pageMatches: Map<Int, List<List<NormRect>>> = emptyMap(),
    highlights: Map<Int, List<Highlight>> = emptyMap(),
    activePage: Int? = null,
    activeOccurrence: Int? = null,
    loadLinks: suspend (Int) -> List<PdfPageLink>? = { null },
    onLinkTarget: (PdfLinkTarget) -> Unit = {},
    onMark: (Highlight) -> Unit = {},
    position: PositionHooks = PositionHooks.None,
    speaking: TtsHighlight? = null,
    ttsLocked: Boolean = false,
    highlightAlpha: Float = 1f,
    onlyActive: Boolean = false,
    @StringRes pageIndicatorRes: Int = R.string.viewer_page_indicator,
    @StringRes pageDescriptionRes: Int = R.string.cd_pdf_page,
    @StringRes pageWithTextDescriptionRes: Int = R.string.cd_pdf_page_with_text,
    pageTitles: List<String> = emptyList(),
    hiddenPages: Set<Int> = emptySet(),
    pageSemanticBodies: List<String> = emptyList(),
    slideSemantics: Boolean = false,
) {
    val renderIncomplete by source.incomplete.collectAsState(false)
    val restoreTarget = rememberRestoredTarget(LocationKind.PAGE, source.pageCount, position)
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = restoreTarget?.unit ?: 0)
    // A page height arriving later moves the list; hold the page the reader was already looking at.
    val scrollAnchor = rememberUpdatedState(listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset)
    var ratiosSettled by remember { mutableStateOf(false) }
    LaunchedEffect(aspectRatios) {
        if (!ratiosSettled) {
            ratiosSettled = true
            return@LaunchedEffect
        }
        val (index, offset) = scrollAnchor.value
        listState.scrollToItem(index, offset)
    }
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

    val hitPages = remember(pageHits) { pageHits.toSet() }

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

    LaunchedEffect(listState, source) {
        snapshotFlow {
            val first = listState.firstVisibleItemIndex
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: first
            (first - CACHED_PAGES_AROUND_VIEW)..(last + CACHED_PAGES_AROUND_VIEW)
        }.collect { keep -> source.evictOutside(keep) }
    }

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
                userScrollEnabled = !zoom.isScrollLocked && !ttsLocked,
                contentPadding = PaddingValues(start = 0.dp, top = pageGap, end = 20.dp, bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(pageGap),
            ) {
                items(source.pageCount, key = { it }) { index ->
                    Box(Modifier.width(pageSlotWidth), contentAlignment = Alignment.TopCenter) {
                        PdfPage(
                            source = source,
                            index = index,
                            widthPx = renderWidthPx,
                            baseWidthPx = baseWidthPx,
                            aspect = aspectRatios.getOrElse(index) { DEFAULT_ASPECT },
                            scrolling = scrolling,
                            isHit = index in hitPages,
                            nightMode = nightMode,
                            activeMatch = activeOccurrence.takeIf { index == activePage },
                            matches = pageMatches[index].orEmpty(),
                            highlights = highlights[index].orEmpty(),
                            onMark = onMark,
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
                            pageDescriptionRes = pageDescriptionRes,
                            pageWithTextDescriptionRes = pageWithTextDescriptionRes,
                            pageTitle = pageTitles.getOrNull(index).orEmpty(),
                            isHidden = index in hiddenPages,
                            semanticBody = pageSemanticBodies.getOrNull(index).orEmpty(),
                            isSlide = slideSemantics,
                            pageCount = source.pageCount,
                            visibleRect = {
                                val info = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }
                                if (info == null) null else {
                                    val pageWidth = renderWidthPx.toFloat().coerceAtLeast(1f)
                                    val pageHeight = pageGeometry.value.heightOf(index)
                                    val left = hScroll.value.toFloat().coerceIn(0f, pageWidth)
                                    val right = (left + listState.layoutInfo.viewportSize.width).coerceAtMost(pageWidth)
                                    val top = (-info.offset.toFloat()).coerceIn(0f, pageHeight)
                                    val bottom = (listState.layoutInfo.viewportSize.height - info.offset).toFloat().coerceIn(top, pageHeight)
                                    if (right > left && bottom > top) {
                                        NormRect(left / pageWidth, top / pageHeight, right / pageWidth, bottom / pageHeight)
                                    } else {
                                        null
                                    }
                                }
                            },
                        )
                    }
                }
                if (renderIncomplete) {
                    item(key = "incomplete") {
                        Text(
                            text = stringResource(R.string.viewer_incomplete),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 12.dp),
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

            ReadoutBadge(
                text = stringResource(pageIndicatorRes, centeredPage + 1, source.pageCount),
                visible = pageIndicatorVisible,
                transformOrigin = TransformOrigin(1f, 1f),
                textStyle = MaterialTheme.typography.labelMedium,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            )
        }
    }
}

private data class TileImage(val rect: NormRect, val bitmap: ImageBitmap)

private val PAGE_INDICATOR_CLEARANCE = 56.dp

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PdfPage(
    source: PageBitmapSource,
    index: Int,
    widthPx: Int,
    baseWidthPx: Int,
    aspect: Float,
    scrolling: Boolean,
    isHit: Boolean,
    nightMode: Boolean,
    activeMatch: Int?,
    matches: List<List<NormRect>>,
    highlights: List<Highlight>,
    matchAlpha: Float,
    onlyActive: Boolean,
    reveal: RevealTicket?,
    selectionState: PdfSelectionState,
    readsPages: Boolean,
    pageGeometry: State<PdfPageGeometry>,
    loadLinks: suspend (Int) -> List<PdfPageLink>?,
    onLinkTarget: (PdfLinkTarget) -> Unit,
    onMark: (Highlight) -> Unit,
    speaking: TtsHighlight?,
    followVoice: Boolean,
    viewportHeight: () -> Int,
    @StringRes pageDescriptionRes: Int,
    @StringRes pageWithTextDescriptionRes: Int,
    pageTitle: String,
    isHidden: Boolean,
    semanticBody: String = "",
    isSlide: Boolean = false,
    pageCount: Int = 0,
    visibleRect: () -> NormRect? = { null },
) {
    // What is shown at the committed zoom, within the pixel budget: the box below keeps the shown width,
    // and the bitmap is scaled to it, so a capped page is softer rather than missing.
    val rasterWidthPx = renderWidthFor(widthPx, aspect, source.maxRenderPixels, baseWidthPx)

    // Keyed on page identity only; keying on the width blanked the page on every quantisation step.
    var bitmap by remember(source, index) { mutableStateOf<Bitmap?>(source.cached(index, rasterWidthPx)) }

    // Width of the last rasterisation; until it settles the bitmap already shown is scaled, so a zoom
    // never blanks the page and the sharper render only replaces it once the width has stopped changing.
    var settledWidthPx by remember(source, index) { mutableIntStateOf(rasterWidthPx) }
    LaunchedEffect(source, index, rasterWidthPx) {
        if (rasterWidthPx == settledWidthPx) return@LaunchedEffect
        source.cached(index, rasterWidthPx)?.let {
            bitmap = it
            settledWidthPx = rasterWidthPx
            return@LaunchedEffect
        }
        delay(RESIZE_SETTLE_MILLIS)
        settledWidthPx = rasterWidthPx
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

    val grid = remember(source, index, widthPx) {
        if (widthPx > rasterWidthPx) source.tileGrid(index, widthPx) else null
    }
    var tiles by remember(source, index) { mutableStateOf<List<TileImage>>(emptyList()) }
    val visible = visibleRect()
    // Key the effect on the *set* of covering tiles, which is unchanged across most scroll frames.
    // Keying on the raw rect restarted a render on every frame even when the same tiles still covered it.
    val covering = remember(grid, visible) {
        if (grid == null || visible == null) emptyList() else grid.covering(visible)
    }
    LaunchedEffect(source, index, widthPx, covering) {
        if (covering.isEmpty()) {
            tiles = emptyList()
            return@LaunchedEffect
        }
        tiles = covering.mapNotNull { tile ->
            val bitmap = source.cachedTile(index, widthPx, tile.rect) ?: source.renderTile(index, widthPx, tile.rect)
            bitmap?.let { TileImage(tile.rect, it.asImageBitmap()) }
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

    val current = selectionState.selection
    val span = current?.spanOn(index)
    val pageText = selectionState.textOf(index)
    LaunchedEffect(selectionState, index, span != null) {
        if (span != null) selectionState.ensureText(index)
    }
    // A slide's text is already in memory, so it is read for semantics even without a screen reader.
    LaunchedEffect(selectionState, index, readsPages, isSlide) {
        if (readsPages || isSlide) selectionState.ensureText(index)
    }
    val selectionRects = remember(span, pageText) {
        if (span != null && pageText != null) pageText.selectionRects(span.from, span.to) else emptyList()
    }

    LaunchedEffect(selectionState, index, speaking != null) {
        if (speaking != null) selectionState.ensureText(index)
    }
    val spokenRange = rememberSpokenRange(speaking, pageText?.text, followVoice, viewportHeight)
    val spokenRects = remember(spokenRange, pageText) {
        if (spokenRange != null && pageText != null) spokenRange.rectsOn(pageText) else null
    }
    // Keyed on line top rather than word to prevent same-line speech from interrupting active scrolls.
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
    val liveMarks = rememberUpdatedState(highlights)
    // Read through a state, so the gesture's long-lived pointer handler never holds a stale callback.
    val liveOnLink = rememberUpdatedState(onLinkTarget)
    val liveOnMark = rememberUpdatedState(onMark)

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
                marks = liveMarks,
                pageSize = rememberUpdatedState(pageSize),
                onLink = { target -> liveOnLink.value(target) },
                onMark = { mark -> liveOnMark.value(mark) },
                haptics = LocalHapticFeedback.current,
                reachPx = with(density) { SelectionReach.toPx() },
                grabRadiusPx = with(density) { HandleGrabRadius.toPx() },
                linkSlopPx = with(density) { LinkTapSlop.toPx() },
                markSlopPx = with(density) { MarkTapSlop.toPx() },
            ),
    ) {
        bitmap?.let { bmp ->
            val spoken = pageText?.text?.takeIf { it.isNotBlank() }?.trim()
            val description = if (isSlide) {
                slideSemanticDescription(
                    prefix = stringResource(R.string.cd_slide_semantics, index + 1, pageCount),
                    title = pageTitle,
                    text = spoken,
                    altText = semanticBody,
                    hidden = isHidden,
                    hiddenLabel = stringResource(R.string.viewer_slide_hidden),
                    headingLabel = stringResource(R.string.viewer_slide_heading_label),
                )
            } else {
                val label = pageTitle.takeIf { it.isNotBlank() }?.trim()
                val spokenText = spoken?.takeIf { readsPages }
                when {
                    label != null && spokenText != null -> stringResource(pageWithTextDescriptionRes, index + 1, "$label. $spokenText")
                    label != null -> stringResource(pageDescriptionRes, index + 1) + ", " + label
                    spokenText != null -> stringResource(pageWithTextDescriptionRes, index + 1, spokenText)
                    else -> stringResource(pageDescriptionRes, index + 1)
                }
            }
            Image(
                bitmap = remember(bmp) { bmp.asImageBitmap() },
                contentDescription = description,
                contentScale = ContentScale.FillBounds,
                colorFilter = if (nightMode) PdfNightMode.Invert else null,
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (tiles.isNotEmpty()) {
            Canvas(Modifier.fillMaxSize()) {
                for (tile in tiles) {
                    drawImage(
                        image = tile.bitmap,
                        srcOffset = IntOffset.Zero,
                        srcSize = IntSize(tile.bitmap.width, tile.bitmap.height),
                        dstOffset = IntOffset(
                            (tile.rect.left * size.width).roundToInt(),
                            (tile.rect.top * size.height).roundToInt(),
                        ),
                        dstSize = IntSize(
                            ((tile.rect.right - tile.rect.left) * size.width).roundToInt(),
                            ((tile.rect.bottom - tile.rect.top) * size.height).roundToInt(),
                        ),
                        colorFilter = if (nightMode) PdfNightMode.Invert else null,
                    )
                }
            }
        }
        if (highlights.isNotEmpty()) {
            Canvas(Modifier.fillMaxSize()) { drawHighlights(highlights) }
            // A note's dot is its own stop, so a screen reader can reach the note a stroke cannot describe.
            for (mark in highlights) {
                if (mark.kind == HighlightKind.NOTE) NoteStop(mark, pageSize, onMark)
            }
        }
        if (linkRects.isNotEmpty() && !isSlide) {
            // Under the matches and the selection, so a link never hides a find highlight.
            val linkColor = MaterialTheme.colorScheme.primary
            Canvas(Modifier.fillMaxSize()) { drawLinks(linkRects, linkColor) }
            for (link in links) LinkStop(link, pageSize, onLinkTarget)
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
                activeMatch?.let { matches.getOrNull(it) }?.let { drawMatch(it, activeColor) }
            }
        }
        if (selectionRects.isNotEmpty()) {
            val selectionColor = MaterialTheme.colorScheme.primary
            Canvas(Modifier.fillMaxSize()) {
                drawSelection(selectionRects, handles.value, selectionColor, handleRadius)
            }
        }
        if (isHidden) {
            Surface(
                modifier = Modifier.padding(8.dp),
                shape = RoundedCornerShape(4.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ) {
                Text(
                    text = stringResource(R.string.viewer_slide_hidden),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                )
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

// A Canvas cannot be focused or described, so this box covers the note's run as its own touch and screen-reader stop: a fingertip target has to be the passage, not the dot.
@Composable
private fun NoteStop(mark: Highlight, pageSize: IntSize, onMark: (Highlight) -> Unit) {
    if (mark.rects.isEmpty() || pageSize == IntSize.Zero) return
    val left = mark.rects.minOf { it.left } * pageSize.width
    val top = mark.rects.minOf { it.top } * pageSize.height
    val right = mark.rects.maxOf { it.right } * pageSize.width
    val bottom = mark.rects.maxOf { it.bottom } * pageSize.height
    val density = LocalDensity.current
    val width = with(density) { (right - left).toDp().coerceAtLeast(1.dp) }
    val height = with(density) { (bottom - top).toDp().coerceAtLeast(1.dp) }
    val description = stringResource(R.string.viewer_annotation_mark, mark.note.orEmpty())
    val editLabel = stringResource(R.string.viewer_annotation_edit)

    Box(
        Modifier
            .offset { IntOffset(left.roundToInt(), top.roundToInt()) }
            .size(width, height)
            .clickable(onClickLabel = editLabel, onClick = { onMark(mark) })
            .semantics { contentDescription = description },
    )
}

// A Canvas cannot be focused or described, so each link gets a box TalkBack can reach and activate.
@Composable
private fun LinkStop(link: PdfPageLink, pageSize: IntSize, onLink: (PdfLinkTarget) -> Unit) {
    if (pageSize == IntSize.Zero) return
    val rect = link.rect
    val left = rect.left * pageSize.width
    val top = rect.top * pageSize.height
    val right = rect.right * pageSize.width
    val bottom = rect.bottom * pageSize.height
    val density = LocalDensity.current
    val width = with(density) { (right - left).toDp().coerceAtLeast(1.dp) }
    val height = with(density) { (bottom - top).toDp().coerceAtLeast(1.dp) }
    val description = stringResource(R.string.viewer_link)
    val openLabel = stringResource(R.string.viewer_link_open)

    Box(
        Modifier
            .offset { IntOffset(left.roundToInt(), top.roundToInt()) }
            .size(width, height)
            .clickable(onClickLabel = openLabel, onClick = { onLink(link.target) })
            .semantics { contentDescription = description },
    )
}

// Asked of the system service rather than Compose's accessibility manager, which exposes only timeout calculations; re-read when the service toggles.
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
