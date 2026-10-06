package com.papyrus.app.ui.viewer

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/** Not zoomed. Sits above [MIN_SCALE], so pulling in is always a deliberate move. */
const val REST_SCALE = 1f

/** The user can pull in far enough to fit a wide page on a narrow screen, or read it as an overview. */
const val MIN_SCALE = 0.5f

const val MAX_SCALE = 5f

private const val DOUBLE_TAP_SCALE = 2.5f
private const val EPSILON = 0.001f

/** Rubber-band overshoot past the bounds. 0.35 stays perceptible without flying off. */
private const val RUBBER_BAND = 0.35f

/** Frames per re-anchor: maxValue lags a re-measure, so one pass clamps wrong. */
private const val REANCHOR_FRAMES = 3

/** A residual smaller than this is rounding, and chasing it only makes the list shimmer. */
private const val SETTLE_TOLERANCE_PX = 1f

/** Scale change and its focal point. Scroll offsets move by the same growth. */
data class ScaleCommit(val from: Float, val to: Float, val focal: Offset) {
    val ratio: Float get() = if (from > 0f) to / from else 1f
}

/** Keeps the pixel under [focal] fixed: scroll + focal before, ratio * that after. */
fun anchoredScroll(scroll: Float, focal: Float, commit: ScaleCommit): Float =
    commit.ratio * (scroll + focal) - focal

/**
 * A scrollable surface that keeps the content under a pinch where the fingers left it.
 *
 * [onCommit] runs inside [ZoomState] just before the new scale is published, so the layout on
 * screen is still the one the pinch was previewing and the content under the focal point can be
 * read straight off it. An effect keyed on the commit runs a frame later, when the list is already
 * at the new size but still at the old offset: whatever sits under the finger is no longer what the
 * user pinched.
 */
interface ZoomAnchor {
    fun onCommit(commit: ScaleCommit)
}

/** Gesture scale paints a live graphicsLayer multiplier, committed scale drives layout. */
@Stable
class ZoomState(private val scope: CoroutineScope) {

    /** Drives layout. Only moves when a gesture ends or a control is tapped. */
    var committedScale by mutableFloatStateOf(REST_SCALE)
        private set

    /** What the fingers are doing right now, including any rubber-banded overshoot. */
    var gestureScale by mutableFloatStateOf(REST_SCALE)
        private set

    /** True from the first frame of a pinch until the snap-back animation has settled. */
    var isZooming by mutableStateOf(false)
        private set

    /** Narrower than [isZooming]: one finger also reports a transform, which would freeze scroll. */
    var isPinching by mutableStateOf(false)
        private set

    /**
     * True from the first frame of a pinch until every finger is up. Fingers never leave together,
     * so for a few ms after the pinch one is still down, and read as a drag it would scroll the
     * document out from under the commit. [isPinching] ends with the first finger, this does not.
     */
    var isScrollLocked by mutableStateOf(false)
        private set

    /** Pinned for the gesture. Moving it shifts the pivot and slides content the wrong way. */
    var focalPoint by mutableStateOf(Offset.Unspecified)
        private set

    /** The most recent commit, for the surface to re-anchor its scroll offsets against. */
    var lastCommit by mutableStateOf<ScaleCommit?>(null)
        private set

    private val anim = Animatable(REST_SCALE)
    private var settleJob: Job? = null
    private var snapping = false
    private val anchors = mutableListOf<ZoomAnchor>()

    /** The scale actually on screen, mid-gesture or at rest. */
    val effectiveScale: Float get() = if (isZooming) gestureScale else committedScale

    /** Extra multiplier during a gesture, exactly 1 when not zooming so the two never compound. */
    val previewFactor: Float
        get() = if (isZooming && committedScale > 0f) gestureScale / committedScale else 1f

    val isZoomed: Boolean get() = committedScale > REST_SCALE + EPSILON

    /** Pointer count decides scroll vs zoom. The centroid is read only when a pinch begins. */
    fun onPointersChanged(count: Int, centroid: Offset) {
        val pinching = count >= 2
        isPinching = pinching
        if (pinching) isScrollLocked = true else if (count == 0) isScrollLocked = false
        if (!pinching) {
            if (isZooming && !snapping) endGesture()
            return
        }
        if (!isZooming) {
            if (centroid != Offset.Unspecified) focalPoint = centroid
            beginGesture()
        }
    }

    private fun beginGesture() {
        // Clear `snapping` first, so the cancelled snap-back's cleanup cannot commit over this gesture.
        snapping = false
        settleJob?.cancel()
        settleJob = null
        isZooming = true
        gestureScale = committedScale
    }

    private fun endGesture() {
        snapping = true
        settleJob = scope.launch { settle() }
    }

    /** [zoomChange] is the fingers' spread now over a moment ago. Ignored without a running pinch. */
    fun applyGesture(zoomChange: Float) {
        if (!isZooming || snapping || zoomChange == 1f) return
        gestureScale = rubberBand(gestureScale * zoomChange)
    }

    private suspend fun settle() {
        val target = gestureScale.coerceIn(MIN_SCALE, MAX_SCALE)
        try {
            if (gestureScale != target) {
                anim.snapTo(gestureScale)
                anim.animateTo(
                    targetValue = target,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = Spring.StiffnessLow,
                    ),
                ) {
                    gestureScale = value
                }
            }
        } finally {
            // Skipped when a new gesture took over, so the band is not yanked back under the fingers.
            if (snapping) {
                settleJob = null
                commit(target)
                snapping = false
            }
        }
    }

    private fun commit(target: Float) {
        val from = committedScale
        val change = if (from != target) ScaleCommit(from, target, focalPoint) else null
        // Before anything is published: the layout is still the one the pinch was previewing, and
        // the surfaces' scroll writes land in the same frame as the new scale.
        if (change != null) for (anchor in anchors.toList()) anchor.onCommit(change)
        committedScale = target
        gestureScale = target
        isZooming = false
        if (change != null) lastCommit = change
    }

    /** Past the bound, only RUBBER_BAND of the excess passes, so resistance grows with distance. */
    private fun rubberBand(desired: Float): Float = when {
        desired > MAX_SCALE -> MAX_SCALE + (desired - MAX_SCALE) * RUBBER_BAND
        desired < MIN_SCALE -> MIN_SCALE - (MIN_SCALE - desired) * RUBBER_BAND
        else -> desired
    }

    fun addAnchor(anchor: ZoomAnchor) {
        if (anchor !in anchors) anchors += anchor
    }

    fun removeAnchor(anchor: ZoomAnchor) {
        anchors -= anchor
    }

    fun reset() = set(REST_SCALE)

    fun toggleZoom(focal: Offset = Offset.Unspecified) =
        if (isZoomed) set(REST_SCALE, focal) else set(DOUBLE_TAP_SCALE, focal)

    /** [focal] anchors the change. Unspecified focal means a plain re-layout, right for a button. */
    fun set(value: Float, focal: Offset = Offset.Unspecified) {
        val target = value.coerceIn(MIN_SCALE, MAX_SCALE)
        if (focal != Offset.Unspecified) focalPoint = focal
        snapping = false
        settleJob?.cancel()
        settleJob = null
        commit(target)
    }

    fun percentLabel(): String = "${(effectiveScale * 100).toInt()}%"
}

@Composable
fun rememberZoomState(): ZoomState {
    val scope = rememberCoroutineScope()
    return remember { ZoomState(scope) }
}

/** Same node as the scroll modifier, pinch claimed in the Initial pass, graphicsLayer applied last. */
fun Modifier.zoomable(state: ZoomState): Modifier = this
    .pointerInput(state) {
        awaitEachGesture {
            var pinched = false
            try {
                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                do {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val pressed = event.changes.filter { it.pressed }
                    if (pressed.size >= 2) {
                        // Averaged by hand: `calculateCentroid` skips a pointer that just went down.
                        val count = pressed.size
                        state.onPointersChanged(
                            count,
                            Offset(
                                x = pressed.sumOf { it.position.x.toDouble() }.toFloat() / count,
                                y = pressed.sumOf { it.position.y.toDouble() }.toFloat() / count,
                            ),
                        )
                        state.applyGesture(event.calculateZoom())
                        event.changes.forEach { it.consume() }
                        pinched = true
                    } else {
                        state.onPointersChanged(pressed.size, Offset.Unspecified)
                        // The finger that stays down a moment after a pinch is not a drag, a tap or a
                        // scrollbar grab: consumed here, nothing downstream can act on it.
                        if (pinched) event.changes.forEach { it.consume() }
                    }
                } while (event.changes.any { it.pressed })
            } finally {
                // In a `finally` so a torn-down gesture clears the locks and scrolling comes back on.
                state.onPointersChanged(0, Offset.Unspecified)
            }
        }
    }
    .pointerInput(state) {
        detectTapGestures(onDoubleTap = { position -> state.toggleZoom(position) })
    }
    .graphicsLayer {
        val preview = state.previewFactor
        scaleX = preview
        scaleY = preview
        val focal = state.focalPoint
        if (preview != 1f && focal != Offset.Unspecified && size.width > 0f && size.height > 0f) {
            transformOrigin = TransformOrigin(
                pivotFractionX = (focal.x / size.width).coerceIn(0f, 1f),
                pivotFractionY = (focal.y / size.height).coerceIn(0f, 1f),
            )
        }
    }

/**
 * `firstVisibleItemScrollOffset` that puts the point [fraction] of the way down an item back under
 * [focal] once the item is [newItemSize] tall. [before] is the list's start content padding: an
 * item at offset 0 sits [before] below the viewport's edge, and each px of scroll offset lifts it.
 */
fun lazyAnchorScrollOffset(focal: Float, fraction: Float, newItemSize: Float, before: Int): Int =
    (before - (focal - fraction * newItemSize)).roundToInt()

/**
 * Scroll distance that puts the point [fraction] of the way down an item back under [focal].
 * [itemTop] and [focal] are in the same space. Positive scrolls forward, moving content up, so an
 * item below where it should be needs a positive delta: current minus wanted.
 */
fun lazyAnchorDelta(focal: Float, fraction: Float, itemTop: Float, itemSize: Float): Float =
    itemTop - (focal - fraction * itemSize)

/**
 * Pins the list item under the focal point.
 *
 * The scroll position is requested inside [onCommit], against the size the item is about to have
 * (the old size times the ratio), so the very first frame at the new scale is already anchored --
 * including an item the new layout would push out of view, which a later correction cannot find.
 * [settle] then trims the difference between that prediction and the measurement: nothing for a page
 * that scales linearly, a few px for re-wrapped text.
 *
 * [paddingScalesWithZoom] says whether the list's start content padding grows with the scale (the
 * PDF list's does) or stays put (the text lists'); the offset is measured from the new padding.
 */
class LazyZoomAnchor(
    private val list: LazyListState,
    private val paddingScalesWithZoom: Boolean = false,
) : ZoomAnchor {

    private class Pin(val index: Int, val fraction: Float, val focalY: Float)

    private var pin: Pin? = null

    override fun onCommit(commit: ScaleCommit) {
        pin = null
        val focal = commit.focal
        if (commit.ratio == 1f || focal == Offset.Unspecified) return
        val info = list.layoutInfo
        // Item offsets start at the content origin, but the focal point is a viewport coordinate.
        val before = info.beforeContentPadding
        val items = info.visibleItemsInfo.filter { it.size > 0 }
        // A pinch can start in the gap between items, so fall back to the nearest one.
        val anchor = items.firstOrNull { focal.y >= it.offset + before && focal.y < it.offset + before + it.size }
            ?: items.minByOrNull {
                val top = (it.offset + before).toFloat()
                val bottom = top + it.size
                if (focal.y < top) top - focal.y else focal.y - bottom
            }
            ?: return
        val fraction = ((focal.y - (anchor.offset + before)) / anchor.size).coerceIn(0f, 1f)
        pin = Pin(anchor.index, fraction, focal.y)
        val beforeAfter = if (paddingScalesWithZoom) (before * commit.ratio).roundToInt() else before
        list.requestScrollToItem(
            anchor.index,
            lazyAnchorScrollOffset(focal.y, fraction, anchor.size * commit.ratio, beforeAfter),
        )
    }

    /** Holds the focal fraction of the item, measured each pass: text re-wraps, the ratio does not. */
    suspend fun settle(zoom: ZoomState) {
        val pinned = pin ?: return
        pin = null
        repeat(REANCHOR_FRAMES) {
            withFrameNanos {}
            if (zoom.isZooming || list.isScrollInProgress) return
            val info = list.layoutInfo
            val current = info.visibleItemsInfo.firstOrNull { it.index == pinned.index } ?: return
            val top = (current.offset + info.beforeContentPadding).toFloat()
            val delta = lazyAnchorDelta(pinned.focalY, pinned.fraction, top, current.size.toFloat())
            if (abs(delta) >= SETTLE_TOLERANCE_PX) list.scrollBy(delta)
        }
    }
}

/**
 * Pins the content under the focal point along one axis of a [ScrollState].
 *
 * A [ScrollState] cannot be moved past the extent it had at the last measure, so it reaches the
 * anchored position only once the new scale has been laid out. [onCommit] records what the position
 * has to be, [settle] scrolls there once there is room, and [pendingShift] is the part of the way
 * still to go. [anchorBridge] paints that part, so frames before the scroll lands look the same as
 * the ones after it rather than flashing the old offset at the new size.
 *
 * [horizontal] picks the formula: a page grows by exactly the ratio, so its x position is computed
 * once. Text reflows, so its y position is a fraction of the document, re-read from each measure.
 */
class ScrollZoomAnchor(private val scroll: ScrollState, val horizontal: Boolean) : ZoomAnchor {

    private class Goal(val target: () -> Float)

    private var goal by mutableStateOf<Goal?>(null)

    override fun onCommit(commit: ScaleCommit) {
        goal = null
        val focal = commit.focal
        if (commit.ratio == 1f || focal == Offset.Unspecified) return
        goal = if (horizontal) {
            // Focal is in content coordinates: screen x is `focal.x - value`. A page grows by exactly the ratio.
            val start = scroll.value
            val target = anchoredScroll(start.toFloat(), focal.x - start, commit)
            Goal { target }
        } else {
            // Held as a fraction, not an offset: text reflow grows the height by more than the ratio.
            val content = (scroll.maxValue.toFloat() + scroll.viewportSize).coerceAtLeast(1f)
            val fraction = (scroll.value + focal.y) / content
            Goal { fraction * (scroll.maxValue.toFloat() + scroll.viewportSize) - focal.y }
        }
    }

    private fun Goal.reachable(): Int = target().coerceIn(0f, scroll.maxValue.toFloat()).roundToInt()

    /** Px still to scroll forward to reach the anchored position, 0 once it is reached. */
    fun pendingShift(): Float {
        val g = goal ?: return 0f
        return (g.reachable() - scroll.value).toFloat()
    }

    suspend fun settle(zoom: ZoomState) {
        val mine = goal ?: return
        try {
            repeat(REANCHOR_FRAMES) {
                withFrameNanos {}
                if (zoom.isZooming || scroll.isScrollInProgress || goal !== mine) return
                val wanted = mine.reachable()
                if (wanted != scroll.value) scroll.scrollTo(wanted)
            }
        } finally {
            // Not when a newer commit replaced it: that one's bridge has not been paid yet.
            if (goal === mine) goal = null
        }
    }
}

/** Paints the scroll [anchor] still owes, as a shift of the content inside the scrolled node. */
fun Modifier.anchorBridge(anchor: ScrollZoomAnchor): Modifier = graphicsLayer {
    val shift = anchor.pendingShift()
    if (anchor.horizontal) translationX = -shift else translationY = -shift
}

/** Registers [anchor] with [zoom] for as long as the surface is composed. */
@Composable
fun ZoomAnchorEffect(zoom: ZoomState, anchor: ZoomAnchor) {
    DisposableEffect(zoom, anchor) {
        zoom.addAnchor(anchor)
        onDispose { zoom.removeAnchor(anchor) }
    }
}
