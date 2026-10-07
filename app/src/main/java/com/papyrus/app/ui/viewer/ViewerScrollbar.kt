package com.papyrus.app.ui.viewer

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.hypot

/** Scroll position of a scrollable surface, in pixels, as the scrollbar needs to see it. */
data class ScrollGeometry(val offset: Float, val viewport: Float, val content: Float) {
    val scrollable: Boolean get() = content > viewport + 1f
    val maxScroll: Float get() = (content - viewport).coerceAtLeast(0f)
}

/**
 * Height records persist per index so a visible-only mean cannot shift (and teleport the thumb)
 * as items enter; a size mismatch means zoom or reflow, so every record is retaken.
 */
@Composable
fun rememberLazyScrollGeometry(state: LazyListState): State<ScrollGeometry> {
    val geometry = remember { mutableStateOf(ScrollGeometry(0f, 0f, 0f)) }
    LaunchedEffect(state) {
        val measured = mutableMapOf<Int, Int>()
        snapshotFlow {
            val info = state.layoutInfo
            val viewport = info.viewportSize.height.toFloat()
            val visible = info.visibleItemsInfo
            if (visible.any { item -> measured[item.index]?.let { abs(it - item.size) > 1 } == true }) {
                measured.clear()
            }
            visible.forEach { measured[it.index] = it.size }
            val knownSum = measured.values.sum()
            val average = if (measured.isEmpty()) viewport else knownSum.toFloat() / measured.size
            var offset = 0f
            for (i in 0 until state.firstVisibleItemIndex) offset += measured[i]?.toFloat() ?: average
            offset += state.firstVisibleItemScrollOffset
            ScrollGeometry(
                offset = offset,
                viewport = viewport,
                content = (knownSum + average * (info.totalItemsCount - measured.size).coerceAtLeast(0))
                    .coerceAtLeast(viewport),
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

/**
 * Fast-scroll knob, Google-Files style: hidden at rest, shown once [showVelocity] is exceeded,
 * pinned on screen while dragged. While hidden the strip has no touch node, so the whole screen
 * scrolls normally; while shown, only the knob and the rightmost half-button-wide band respond.
 *
 * @param onScrollBy drained by a single consumer, so drag frames cannot land out of order.
 * @param showVelocity scroll speed in dp/s that reveals the knob.
 * @param hideAfterMillis linger once the speed drops below [showVelocity].
 * @param topClearance reserves the track's top below a host overlay (the zoom badge).
 * @param bottomClearance reserves the track's bottom above a host overlay (the page indicator).
 * @param zoom held hidden while it zooms and for a beat after: a zoom's reflow reads as scroll
 *   speed and would teleport the knob.
 */
@Composable
fun Modifier.autoHideScrollbar(
    geometry: State<ScrollGeometry>,
    onScrollBy: suspend (Float) -> Unit,
    showVelocity: Dp = 880.dp,
    hideAfterMillis: Long = 1000,
    topClearance: Dp = 0.dp,
    bottomClearance: Dp = 0.dp,
    zoom: ZoomState? = null,
): Modifier {
    // Wrapped so the input and draw blocks cannot act on a stale `onScrollBy`.
    val currentGeometry by rememberUpdatedState(geometry.value)
    val currentOnScrollBy by rememberUpdatedState<suspend (Float) -> Unit>(onScrollBy)

    var visible by remember { mutableStateOf(false) }
    var dragging by remember { mutableStateOf(false) }
    var trackHeightPx by remember { mutableFloatStateOf(0f) }
    // Shared by the fade loop and the drag handler.
    var lastFastNs by remember { mutableLongStateOf(0L) }
    val alphaTarget = when {
        dragging -> DRAG_ALPHA
        visible -> IDLE_ALPHA
        else -> 0f
    }
    // The badges' timing: quick in, slow out.
    val alpha by animateFloatAsState(
        targetValue = alphaTarget,
        animationSpec = if (alphaTarget == 0f) ReadoutFadeOut else ReadoutFadeIn,
        label = "scrollbarAlpha",
    )

    val density = LocalDensity.current

    LaunchedEffect(geometry) {
        val thresholdPx = with(density) { showVelocity.toPx() }
        val hideNanos = hideAfterMillis * 1_000_000L
        var lastOffset = currentGeometry.offset
        var lastScale = zoom?.committedScale ?: 0f
        var quietUntil = 0L
        while (true) {
            delay(VELOCITY_TICK_MILLIS)
            val now = System.nanoTime()
            val g = currentGeometry
            val scale = zoom?.committedScale ?: 0f
            if (zoom != null && (zoom.isZooming || scale != lastScale)) {
                quietUntil = now + ZOOM_QUIET_NANOS
            }
            lastScale = scale
            val velocity = abs(g.offset - lastOffset) / (VELOCITY_TICK_MILLIS / 1000f)
            lastOffset = g.offset
            if (now < quietUntil) {
                if (!dragging) visible = false
                continue
            }
            if (!dragging && g.scrollable && velocity >= thresholdPx) {
                lastFastNs = now
                visible = true
            }
            // A jump counts as speed by design; the knob is how the reader sees it happened.
            if (visible && !dragging && (!g.scrollable || now - lastFastNs > hideNanos)) {
                visible = false
            }
        }
    }

    val deltas = remember { Channel<Float>(Channel.UNLIMITED) }
    LaunchedEffect(deltas) {
        for (delta in deltas) currentOnScrollBy(delta)
    }

    val knobInsetPx = with(density) { KNOB_INSET.toPx() }
    val knobPx = with(density) { KNOB_DIAMETER.toPx() }
    val clearanceTopPx = with(density) { topClearance.toPx() }
    val clearanceBottomPx = with(density) { bottomClearance.toPx() }
    // The edge inset is the floor; a larger clearance takes over.
    val trackTopPx = maxOf(knobInsetPx, clearanceTopPx)
    val trackBottomPx = maxOf(knobInsetPx, clearanceBottomPx)
    val grabSlopPx = with(density) { GRAB_SLOP.toPx() }

    return this
        .onSizeChanged { trackHeightPx = (it.height - trackTopPx - trackBottomPx).coerceAtLeast(0f) }
        .drawWithCache {
            val g = currentGeometry
            val maxScroll = g.maxScroll.coerceAtLeast(1f)
            val travel = ((size.height - trackTopPx - trackBottomPx) - knobPx).coerceAtLeast(0f)
            val y = travel * (g.offset / maxScroll).coerceIn(0f, 1f)
            // Hugs the right edge, overlapping the page margin.
            val radius = knobPx / 2f
            val center = Offset(size.width - knobInsetPx - radius, y + trackTopPx + radius)
            val border = with(density) { KNOB_BORDER.toPx() }
            val arrowScale = knobPx / SVG_VIEWBOX
            onDrawBehind {
                if (alpha >= 0.01f) {
                    // Grows out of its own centre as it fades in, like the badges. Dragging only
                    // raises alpha past IDLE_ALPHA, which the coerce keeps from enlarging it.
                    val pop = lerp(READOUT_POP_SCALE, 1f, (alpha / IDLE_ALPHA).coerceIn(0f, 1f))
                    withTransform({ scale(pop, pop, pivot = center) }) {
                        drawCircle(color = Color.White.copy(alpha = alpha), radius = radius, center = center)
                        drawCircle(color = Color.Black.copy(alpha = alpha), radius = radius - border, center = center)
                        withTransform({
                            translate(
                                center.x - SVG_VIEWBOX / 2f * arrowScale,
                                center.y - SVG_VIEWBOX / 2f * arrowScale,
                            )
                            scale(arrowScale, arrowScale, pivot = Offset.Zero)
                        }) {
                            drawPath(KnobArrows, Color.White.copy(alpha = alpha))
                        }
                    }
                }
            }
        }
        // Hidden: no touch node, so the hit test falls through to the list and the screen scrolls.
        .then(
            if (visible || dragging) {
                Modifier.pointerInput(Unit) {
                    // Absolute targeting needs the knob's drawn position at drag start.
                    var startOffset: Float = 0f
                    var travel: Float = 1f
                    var lastTarget: Float = 0f
                    var totalDragY: Float = 0f
                    var engaged = false

                    fun endGesture() {
                        if (engaged) {
                            dragging = false
                            lastFastNs = System.nanoTime()
                        }
                        engaged = false
                    }

                    fun jumpTo(position: Offset) {
                        val g = currentGeometry
                        if (!(visible || dragging) || g.maxScroll <= 0f) return
                        val thumbHeight = knobPx
                        val track = trackHeightPx.coerceAtLeast(1f)
                        val jumpTravel = (track - thumbHeight).coerceAtLeast(1f)
                        val knobCenterY = thumbTop(g, track, thumbHeight, trackTopPx) + thumbHeight / 2f
                        val knobCenterX = size.width - knobInsetPx - thumbHeight / 2f
                        // On the knob a tap means drag; only the right band jumps.
                        val onKnob = hypot(position.x - knobCenterX, position.y - knobCenterY) <=
                            thumbHeight / 2f + grabSlopPx
                        if (onKnob || position.x < size.width - thumbHeight / 2f) return
                        val target = ((position.y - trackTopPx - thumbHeight / 2f) / jumpTravel * g.maxScroll)
                            .coerceIn(0f, g.maxScroll)
                        deltas.trySend(target - g.offset)
                    }

                    coroutineScope {
                        launch { detectTapGestures { position -> jumpTo(position) } }
                        detectDragGestures(
                            onDragStart = { position ->
                                engaged = visible || dragging
                                val g = currentGeometry
                                if (!engaged || g.maxScroll <= 0f) {
                                    engaged = false
                                    return@detectDragGestures
                                }
                                val track = trackHeightPx.coerceAtLeast(1f)
                                val thumbHeight = knobPx
                                travel = (track - thumbHeight).coerceAtLeast(1f)
                                startOffset = g.offset
                                lastTarget = g.offset
                                totalDragY = 0f
                                val knobCenterY = thumbTop(g, track, thumbHeight, trackTopPx) + thumbHeight / 2f
                                val knobCenterX = size.width - knobInsetPx - thumbHeight / 2f
                                val onKnob = hypot(position.x - knobCenterX, position.y - knobCenterY) <=
                                    thumbHeight / 2f + grabSlopPx
                                val inRightBand = position.x >= size.width - thumbHeight / 2f
                                if (!onKnob && !inRightBand) {
                                    engaged = false
                                    return@detectDragGestures
                                }
                                dragging = true
                                if (!onKnob) {
                                    // Jump re-centres the knob under the finger before the drag continues.
                                    val target = ((position.y - trackTopPx - thumbHeight / 2f) / travel * g.maxScroll)
                                        .coerceIn(0f, g.maxScroll)
                                    // Rebased: the next move would measure from the old offset and yank back.
                                    startOffset = target
                                    lastTarget = target
                                    deltas.trySend(target - g.offset)
                                }
                            },
                            onDrag = { change, dragAmount ->
                                if (!engaged) return@detectDragGestures
                                change.consume()
                                val g = currentGeometry
                                if (g.maxScroll <= 0f) return@detectDragGestures
                                // dragAmount is per-event, not cumulative: without the running total the
                                // knob stops under the finger and a slowing finger reads as reverse scroll.
                                totalDragY += dragAmount.y
                                val target = (startOffset + totalDragY / travel * g.maxScroll)
                                    .coerceIn(0f, g.maxScroll)
                                val delta = target - lastTarget
                                if (delta != 0f) {
                                    lastTarget = target
                                    deltas.trySend(delta)
                                }
                            },
                            onDragEnd = { endGesture() },
                            onDragCancel = { endGesture() },
                        )
                    }
                }
            } else {
                Modifier
            },
        )
}

/** Top edge of the knob within the track, for hit-testing a touch that missed it. */
private fun thumbTop(g: ScrollGeometry, trackHeight: Float, thumbHeight: Float, topMargin: Float): Float {
    val travel = (trackHeight - thumbHeight).coerceAtLeast(0f)
    val maxScroll = g.maxScroll.coerceAtLeast(1f)
    return travel * (g.offset / maxScroll).coerceIn(0f, 1f) + topMargin
}

private const val VELOCITY_TICK_MILLIS = 50L
private const val ZOOM_QUIET_NANOS = 250_000_000L
private const val DRAG_ALPHA = 1f

/** Knob opacity at rest; the badges share it so they read as the same material. */
const val IDLE_ALPHA = 0.8f

/** Fixed diameter: a scrubber, not a proportional bar. */
private val KNOB_DIAMETER = 46.dp

/** Gap between the knob and the strip edges; the floor under any clearance. */
private val KNOB_INSET = 6.dp

/** White ring that keeps the black knob outlined wherever it lands. */
private val KNOB_BORDER = 1.dp

private const val SVG_VIEWBOX = 48f

/** Up/down arrows from the Google knob SVG, in 48-unit viewBox coordinates. */
private val KnobArrows: Path = Path().apply {
    moveTo(24f, 14f)
    lineTo(28f, 18f)
    lineTo(29.4f, 16.6f)
    lineTo(24f, 11.2f)
    lineTo(18.6f, 16.6f)
    lineTo(20f, 18f)
    close()
    moveTo(24f, 34f)
    lineTo(20f, 30f)
    lineTo(18.6f, 31.4f)
    lineTo(24f, 36.8f)
    lineTo(29.4f, 31.4f)
    lineTo(28f, 30f)
    close()
}

/** Touch strip: the knob diameter plus both insets. */
val ScrollbarTouchTarget = KNOB_DIAMETER + KNOB_INSET * 2

/** Reserved at the top of the strip for ViewerScreen's zoom badge, so the knob stops below it. */
val ZoomBadgeTopClearance = 46.dp

/** Around-knob tolerance that makes a touch a grab rather than a jump. */
private val GRAB_SLOP = 8.dp
