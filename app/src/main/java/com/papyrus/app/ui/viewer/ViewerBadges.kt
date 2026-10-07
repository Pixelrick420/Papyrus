package com.papyrus.app.ui.viewer

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first

/** How long a readout stays once what it shows stops changing. Same linger as the scroll knob. */
const val READOUT_LINGER_MILLIS = 1000L

/** Quick in, so the readout is there while the eye is still on the change that caused it. */
private const val FADE_IN_MILLIS = 160

/** Slow out, so the readout drifts away instead of blinking off. */
private const val FADE_OUT_MILLIS = 320

/**
 * Size a readout starts from when it appears, and settles to when it leaves. Small enough to be
 * felt rather than seen; the scroll knob uses it too, so all three read as one system.
 */
const val READOUT_POP_SCALE = 0.92f

/** Shared by the knob's alpha and the badges, so they fade on the same curve. */
internal val ReadoutFadeIn: TweenSpec<Float> = tween(FADE_IN_MILLIS, easing = LinearOutSlowInEasing)
internal val ReadoutFadeOut: TweenSpec<Float> = tween(FADE_OUT_MILLIS, easing = FastOutSlowInEasing)

/**
 * A pill that is on screen only while [visible]: it fades in and grows slightly out of
 * [transformOrigin] (the corner it sits in), and leaves the same way, slower.
 *
 * Out of composition while hidden, so it costs nothing and cannot block a touch. The look is the
 * scroll knob's: black, white ring and text, all at [IDLE_ALPHA].
 */
@Composable
fun ReadoutBadge(
    text: String,
    visible: Boolean,
    transformOrigin: TransformOrigin,
    textStyle: TextStyle,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn(ReadoutFadeIn) + scaleIn(ReadoutFadeIn, READOUT_POP_SCALE, transformOrigin),
        exit = fadeOut(ReadoutFadeOut) + scaleOut(ReadoutFadeOut, READOUT_POP_SCALE, transformOrigin),
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = Color.Black.copy(alpha = IDLE_ALPHA),
            contentColor = Color.White.copy(alpha = IDLE_ALPHA),
            border = BorderStroke(1.dp, Color.White.copy(alpha = IDLE_ALPHA)),
        ) {
            Text(text, style = textStyle, modifier = Modifier.padding(contentPadding))
        }
    }
}

/**
 * True from the moment [signal] changes until [lingerMillis] after it last did. Every change
 * restarts the wait, so a stream of them (a fling, a pinch) holds the readout up and only the
 * last one starts the countdown.
 *
 * The value [signal] has when this enters composition is not a change, so nothing shows until it
 * moves. While [hold] is true the countdown does not start: a pinch whose fingers rest keeps its
 * readout, and the linger runs from the moment they lift.
 */
@Composable
fun <T> rememberLingerVisible(
    lingerMillis: Long = READOUT_LINGER_MILLIS,
    hold: () -> Boolean = { false },
    signal: () -> T,
): State<Boolean> {
    val visible = remember { mutableStateOf(false) }
    // Wrapped so the effect below, which never restarts, cannot act on stale lambdas.
    val currentHold by rememberUpdatedState(hold)
    val currentSignal by rememberUpdatedState(signal)
    LaunchedEffect(Unit) {
        snapshotFlow { currentSignal() }
            .drop(1)
            // A newer change cancels the wait of the one before it.
            .collectLatest {
                visible.value = true
                snapshotFlow { currentHold() }.first { !it }
                delay(lingerMillis)
                visible.value = false
            }
    }
    return visible
}
