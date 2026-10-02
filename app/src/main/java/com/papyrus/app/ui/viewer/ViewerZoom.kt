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
import kotlin.math.roundToInt

/** Not zoomed. Sits above [MIN_SCALE] because 90% is a real, useful fit. */
const val REST_SCALE = 1f

/** The user can pull *in* slightly, to fit a wide page on a narrow screen. */
const val MIN_SCALE = 0.9f

const val MAX_SCALE = 5f

private const val STEP_FACTOR = 1.25f
private const val DOUBLE_TAP_SCALE = 2.5f
private const val EPSILON = 0.001f

/** Rubber-band overshoot past the bounds. 0.35 stays perceptible without flying off. */
private const val RUBBER_BAND = 0.35f

/** Frames per re-anchor: maxValue lags a re-measure, so one pass clamps wrong. */
private const val REANCHOR_FRAMES = 3

/** Scale change and its focal point. Scroll offsets move by the same growth. */
data class ScaleCommit(val from: Float, val to: Float, val focal: Offset) {
    val ratio: Float get() = if (from > 0f) to / from else 1f
}

/** Keeps the pixel under [focal] fixed: scroll + focal before, ratio * that after. */
fun anchoredScroll(scroll: Float, focal: Float, commit: ScaleCommit): Float =
    commit.ratio * (scroll + focal) - focal

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

    /** Pinned for the gesture. Moving it shifts the pivot and slides content the wrong way. */
    var focalPoint by mutableStateOf(Offset.Unspecified)
        private set

    /** The most recent commit, for the surface to re-anchor its scroll offsets against. */
    var lastCommit by mutableStateOf<ScaleCommit?>(null)
        private set

    private val anim = Animatable(REST_SCALE)
    private var settleJob: Job? = null
    private var snapping = false

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
        committedScale = target
        gestureScale = target
        isZooming = false
        if (from != target) {
            lastCommit = ScaleCommit(from, target, focalPoint)
        }
    }

    /** Past the bound, only RUBBER_BAND of the excess passes, so resistance grows with distance. */
    private fun rubberBand(desired: Float): Float = when {
        desired > MAX_SCALE -> MAX_SCALE + (desired - MAX_SCALE) * RUBBER_BAND
        desired < MIN_SCALE -> MIN_SCALE - (MIN_SCALE - desired) * RUBBER_BAND
        else -> desired
    }

    fun stepUp() = set(committedScale * STEP_FACTOR)

    fun stepDown() = set(committedScale / STEP_FACTOR)

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
                    } else {
                        state.onPointersChanged(pressed.size, Offset.Unspecified)
                    }
                } while (event.changes.any { it.pressed })
            } finally {
                // In a `finally` so a torn-down gesture clears `isPinching` and scrolling comes back on.
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

/** Holds the focal fraction, not the offset: text reflow grows the height by more than the ratio. */
suspend fun ScrollState.reanchorTo(commit: ScaleCommit) {
    val focal = commit.focal
    if (commit.ratio == 1f || focal == Offset.Unspecified || focal.y <= 0f) return
    val fraction = (value + focal.y) / (maxValue + viewportSize).toFloat().coerceAtLeast(1f)
    repeat(REANCHOR_FRAMES) {
        withFrameNanos {}
        val target = fraction * (maxValue + viewportSize) - focal.y
        scrollTo(target.roundToInt().coerceIn(0, maxValue))
    }
}

/** Focal is in content coordinates: screen x is `focal.x - value`. A page grows by exactly the ratio. */
suspend fun ScrollState.reanchorHorizontallyTo(commit: ScaleCommit) {
    val focal = commit.focal
    if (commit.ratio == 1f || focal == Offset.Unspecified) return
    val start = value
    val target = anchoredScroll(start.toFloat(), focal.x - start, commit)
    repeat(REANCHOR_FRAMES) {
        withFrameNanos {}
        scrollTo(target.roundToInt().coerceIn(0, maxValue))
    }
}

/** Holds the focal fraction of the item, measured each pass: text re-wraps, the ratio does not. */
suspend fun LazyListState.reanchorTo(commit: ScaleCommit) {
    val focal = commit.focal
    if (commit.ratio == 1f || focal == Offset.Unspecified) return
    val anchor = layoutInfo.visibleItemsInfo
        .firstOrNull { it.size > 0 && focal.y >= it.offset && focal.y < it.offset + it.size } ?: return
    val fraction = (focal.y - anchor.offset) / anchor.size
    repeat(REANCHOR_FRAMES) {
        withFrameNanos {}
        val current = layoutInfo.visibleItemsInfo.firstOrNull { it.index == anchor.index } ?: return
        val delta = (focal.y - fraction * current.size) - current.offset
        if (delta != 0f) scrollBy(delta)
    }
}
