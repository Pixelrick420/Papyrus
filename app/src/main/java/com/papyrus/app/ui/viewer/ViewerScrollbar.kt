package com.papyrus.app.ui.viewer

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlin.math.abs

/** Scroll position of a scrollable surface, in pixels, as the scrollbar needs to see it. */
data class ScrollGeometry(val offset: Float, val viewport: Float, val content: Float) {
    val scrollable: Boolean get() = content > viewport + 1f
    val maxScroll: Float get() = (content - viewport).coerceAtLeast(0f)
}

/** Fraction-based, so variable-height pages need no per-item measurement, at the cost of drift. */
@Composable
fun rememberLazyScrollGeometry(state: LazyListState): State<ScrollGeometry> {
    val geometry = remember { mutableStateOf(ScrollGeometry(0f, 0f, 0f)) }
    LaunchedEffect(state) {
        snapshotFlow {
            val info = state.layoutInfo
            val viewport = info.viewportSize.height.toFloat()
            // Content height: mean visible item height x item count. O(visible), not O(all).
            val visible = info.visibleItemsInfo
            val average = if (visible.isEmpty()) {
                viewport
            } else {
                (visible.sumOf { it.size }.toFloat() / visible.size).coerceAtLeast(1f)
            }
            ScrollGeometry(
                offset = state.firstVisibleItemIndex * average + state.firstVisibleItemScrollOffset,
                viewport = viewport,
                content = (info.totalItemsCount * average).coerceAtLeast(viewport),
            )
        }.collect { geometry.value = it }
    }
    return geometry
}

/** Geometry for a plain [ScrollState] (Markdown), which knows its content height exactly. */
@Composable
fun rememberScrollGeometry(state: ScrollState): State<ScrollGeometry> {
    val geometry = remember { mutableStateOf(ScrollGeometry(0f, 0f, 0f)) }
    LaunchedEffect(state) {
        snapshotFlow {
            ScrollGeometry(
                offset = state.value.toFloat(),
                viewport = state.viewportSize.toFloat(),
                content = state.maxValue.toFloat() + state.viewportSize.toFloat(),
            )
        }.collect { geometry.value = it }
    }
    return geometry
}

/** Auto-hiding scrollbar overlay. The thumb is an absolute handle, not a relative indicator.
 *
 * @param onScrollBy suspend, so a fire-and-forget launch cannot drop or reorder drags.
 * @param color thumb colour; [Color.Unspecified] (the default) means `onSurfaceVariant`, so the
 *   thumb follows the theme instead of being a fixed grey.
 */
@OptIn(FlowPreview::class)
@Composable
fun Modifier.autoHideScrollbar(
    geometry: State<ScrollGeometry>,
    onScrollBy: suspend (Float) -> Unit,
    fadeAfterMillis: Long = 800,
    thickness: Dp = 6.dp,
    inset: Dp = 3.dp,
    color: Color = Color.Unspecified,
): Modifier {
    // Wrapped so the input and draw blocks cannot act on a stale `onScrollBy`.
    val currentGeometry by rememberUpdatedState(geometry.value)
    val currentOnScrollBy by rememberUpdatedState< suspend (Float) -> Unit>(onScrollBy)

    var visible by remember { mutableStateOf(false) }
    var dragging by remember { mutableStateOf(false) }
    var trackHeightPx by remember { mutableFloatStateOf(0f) }
    // Held during a drag so the fade timer cannot hide the thumb under the finger.
    val alpha by animateFloatAsState(if (visible || dragging) 1f else 0f, label = "scrollbarAlpha")

    LaunchedEffect(geometry) {
        if (!geometry.value.scrollable) {
            visible = false
            return@LaunchedEffect
        }
        visible = true
        snapshotFlow { currentGeometry.offset }
            .debounce(fadeAfterMillis)
            .collect { if (!dragging) visible = false }
    }

    val density = LocalDensity.current
    // Resolved here, not inside `drawWithCache`: that block is not composable, and a fixed grey here
    // would be the one accent in the viewer that ignored the theme.
    val thumbColor = if (color == Color.Unspecified) MaterialTheme.colorScheme.onSurfaceVariant else color
    val thicknessPx = with(density) { thickness.toPx() }
    val insetPx = with(density) { inset.toPx() }
    val minThumbPx = with(density) { MIN_THUMB_HEIGHT.toPx() }
    // Slop so a grab survives the thumb moving under the finger, and an off-hit still counts.
    val grabSlopPx = with(density) { GRAB_SLOP.toPx() }

    fun thumbHeightFor(trackHeight: Float, g: ScrollGeometry): Float {
        val fraction = (g.viewport / g.content.coerceAtLeast(1f)).coerceIn(0.08f, 1f)
        return (trackHeight * fraction).coerceAtLeast(minThumbPx)
    }

    return this
        .onSizeChanged { trackHeightPx = (it.height - insetPx * 2).coerceAtLeast(0f) }
        .drawWithCache {
            val g = currentGeometry
            val maxScroll = g.maxScroll.coerceAtLeast(1f)
            val thumbHeight = thumbHeightFor(size.height - insetPx * 2, g)
            val travel = ((size.height - insetPx * 2) - thumbHeight).coerceAtLeast(0f)
            val y = travel * (g.offset / maxScroll).coerceIn(0f, 1f)
            onDrawBehind {
                drawRoundRect(
                    color = thumbColor.copy(alpha = 0.55f * alpha),
                    topLeft = Offset(size.width - thicknessPx - insetPx, y + insetPx),
                    size = Size(thicknessPx, thumbHeight),
                    cornerRadius = CornerRadius(thicknessPx / 2f),
                )
            }
        }
        .pointerInput(Unit) {
            val scope = CoroutineScope(currentCoroutineContext())
            // Absolute targeting needs the thumb's drawn position at drag start.
            var startOffset: Float = 0f
            var travel: Float = 1f
            var lastTarget: Float = 0f

            detectDragGestures(
                onDragStart = { position ->
                    val g = currentGeometry
                    val track = trackHeightPx.coerceAtLeast(1f)
                    val thumbHeight = thumbHeightFor(track, g)
                    travel = (track - thumbHeight).coerceAtLeast(1f)
                    startOffset = g.offset
                    lastTarget = g.offset
                    dragging = true
                    // Any touch in the strip jumps to that position, as the platform scrollbar does.
                    if (abs(position.y - thumbTop(g, track, thumbHeight)) > grabSlopPx) {
                        val target = (position.y / travel * g.maxScroll).coerceIn(0f, g.maxScroll)
                        lastTarget = target
                        scope.launch { currentOnScrollBy(target - startOffset) }
                    }
                },
                onDrag = { change, dragAmount ->
                    change.consume()
                    val g = currentGeometry
                    if (g.maxScroll <= 0f) return@detectDragGestures
                    // Position maps linearly onto the range, so the thumb stays under the finger.
                    val target = (startOffset + dragAmount.y / travel * g.maxScroll)
                        .coerceIn(0f, g.maxScroll)
                    val delta = target - lastTarget
                    if (delta != 0f) {
                        lastTarget = target
                        scope.launch { currentOnScrollBy(delta) }
                    }
                },
                onDragEnd = { dragging = false },
                onDragCancel = { dragging = false },
            )
        }
}

/** Top edge of the thumb within the track, for hit-testing a touch that missed it. */
private fun thumbTop(g: ScrollGeometry, trackHeight: Float, thumbHeight: Float): Float {
    val travel = (trackHeight - thumbHeight).coerceAtLeast(0f)
    val maxScroll = g.maxScroll.coerceAtLeast(1f)
    return travel * (g.offset / maxScroll).coerceIn(0f, 1f)
}

private val MIN_THUMB_HEIGHT = 36.dp

/** Wider than the 6dp thumb on purpose: the thumb is a visual cue, this is the finger target. */
val ScrollbarTouchTarget = 28.dp

/** Extra slop, in dp, for treating a near-miss as a grab. */
private val GRAB_SLOP = 8.dp
