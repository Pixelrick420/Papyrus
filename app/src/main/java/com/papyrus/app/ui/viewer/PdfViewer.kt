package com.papyrus.app.ui.viewer

import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.papyrus.app.R
import com.papyrus.app.viewer.NormRect
import com.papyrus.app.viewer.PdfPageSource
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

private const val LOW_RES_DIVISOR = 4
private val PAGE_HIT_BORDER = 3.dp

/** Gap between pages, and the margin above and beside the first one, at 100%. */
private val PAGE_GAP = 8.dp

/** A drag emits a size change per frame, and each queues a rasterisation behind the source's mutex. */
private const val RESIZE_SETTLE_MILLIS = 150L

/** Zoom feeds the render width, which [PdfPageSource] caches by, so wider re-renders crisply. */
@Composable
fun PdfViewer(
    source: PdfPageSource,
    aspectRatios: List<Float>,
    zoom: ZoomState,
    modifier: Modifier = Modifier,
    pageHits: List<Int> = emptyList(),
    pageRects: Map<Int, List<NormRect>> = emptyMap(),
    activePage: Int? = null,
) {
    val listState = rememberLazyListState()
    val geometry = rememberLazyScrollGeometry(listState)
    val scrolling by remember { derivedStateOf { listState.isScrollInProgress } }
    val firstVisible by remember { derivedStateOf { listState.firstVisibleItemIndex } }
    // One state for the whole document; per-page state would reset the offset on every page change.
    val hScroll = rememberScrollState()

    LaunchedEffect(activePage) {
        activePage?.takeIf { it >= 0 && it < source.pageCount }
            ?.let { listState.scrollToItem(it) }
    }

    // Each anchor reads the content under the pinch at the commit itself, while the layout on screen
    // is still the one the pinch previewed. The list is moved in that same frame; only the trim
    // for rounding runs afterwards, keyed on the commit so it is one pass per gesture.
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

    BoxWithConstraints(modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant)) {
        val baseWidthPx = with(LocalDensity.current) { maxWidth.roundToPx() }
        val scale = zoom.committedScale
        // Committed scale only. Distinct widths are distinct cache keys, so a live scale churns.
        // Exact, not stepped to a grid: a layout at the committed scale has to be the size the pinch
        // was previewing, or releasing the fingers resizes every page by up to a step.
        val renderWidthPx = (baseWidthPx * scale).roundToInt().coerceAtLeast(1)
        // A page narrower than the viewport is centred, or zooming out slides the document sideways.
        val pageSlotWidth = with(LocalDensity.current) { maxOf(renderWidthPx, baseWidthPx).toDp() }
        // Scaled with the pages. Fixed gaps would put the layout at the new scale off the preview by
        // (ratio - 1) of a gap per page, which adds up across the pages that are in view.
        val pageGap = PAGE_GAP * scale

        Box(Modifier.fillMaxSize()) {
            LazyColumn(
                state = listState,
                // Both axes off from the first pinch until every finger is up, so the finger that lingers cannot scroll.
                modifier = Modifier.fillMaxSize()
                    .horizontalScroll(hScroll, enabled = !zoom.isScrollLocked)
                    .zoomable(zoom)
                    .anchorBridge(hAnchor),
                userScrollEnabled = !zoom.isScrollLocked,
                // Trailing padding clears the scrollbar; the bottom clears the page indicator.
                contentPadding = PaddingValues(start = pageGap, top = pageGap, end = 20.dp, bottom = 8.dp),
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
                            isHit = index in pageHits,
                            isActive = index == activePage,
                            matchRects = pageRects[index].orEmpty(),
                        )
                    }
                }
            }

            Box(
                Modifier
                    .align(Alignment.CenterEnd)
                    .width(ScrollbarTouchTarget)
                    .fillMaxSize()
                    // Named, not a trailing lambda: `fadeAfterMillis` is last, so a lambda binds there.
                    .autoHideScrollbar(geometry, onScrollBy = { delta -> listState.scrollBy(delta) }),
            )

            Surface(
                Modifier.align(Alignment.BottomEnd).padding(16.dp),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.85f),
            ) {
                Text(
                    stringResource(R.string.viewer_page_indicator, firstVisible + 1, source.pageCount),
                    color = MaterialTheme.colorScheme.inverseOnSurface,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }
    }
}

private const val DEFAULT_ASPECT = 1.414f

@Composable
private fun PdfPage(
    source: PdfPageSource,
    index: Int,
    widthPx: Int,
    aspect: Float,
    scrolling: Boolean,
    isHit: Boolean,
    isActive: Boolean,
    matchRects: List<NormRect>,
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

    Box(
        Modifier
            // The exact render width, not a fraction of the slot, which would clamp if they differ.
            .width(with(LocalDensity.current) { widthPx.toDp() })
            .aspectRatio(1f / aspect)
            .background(Color.White),
    ) {
        bitmap?.let { bmp ->
            Image(
                bitmap = remember(bmp) { bmp.asImageBitmap() },
                contentDescription = stringResource(R.string.cd_pdf_page, index + 1),
                contentScale = ContentScale.FillBounds,
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (matchRects.isNotEmpty()) {
            // Matched words in page-relative units, so they stay put at any width. Translucent.
            val color = (if (isActive) ActiveMatchColor else OtherMatchColor).copy(alpha = MATCH_ALPHA)
            val corner = with(LocalDensity.current) { 2.dp.toPx() }
            Canvas(Modifier.fillMaxSize()) {
                for (rect in matchRects) {
                    drawRoundRect(
                        color = color,
                        topLeft = Offset(rect.left * size.width, rect.top * size.height),
                        size = Size((rect.right - rect.left) * size.width, (rect.bottom - rect.top) * size.height),
                        cornerRadius = CornerRadius(corner),
                    )
                }
            }
        } else if (isHit || isActive) {
            // Matched but no rectangles (past the extractor's budget), so a border is the only signal.
            Surface(
                Modifier.fillMaxSize(),
                color = Color.Transparent,
                border = BorderStroke(
                    if (isActive) ACTIVE_HIT_BORDER else PAGE_HIT_BORDER,
                    if (isActive) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary,
                ),
            ) {}
        }
    }
}

private const val MATCH_ALPHA = 0.5f

private val ACTIVE_HIT_BORDER = 5.dp
