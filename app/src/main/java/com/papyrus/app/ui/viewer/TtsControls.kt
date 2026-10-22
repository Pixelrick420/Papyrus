package com.papyrus.app.ui.viewer

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import com.papyrus.app.R
import com.papyrus.app.tts.TtsController
import com.papyrus.app.tts.TtsState
import com.papyrus.app.ui.components.ControlCorner
import com.papyrus.app.ui.components.ControlHeight
import com.papyrus.app.ui.icons.Pause
import com.papyrus.app.ui.icons.SkipNext
import com.papyrus.app.ui.icons.SkipPrevious

/**
 * The in-flow read-aloud bar: play/pause, skip a paragraph either way, lock the page to the line being
 * read, cycle the speed, close. Shown while a document is being spoken and while it is paused, and —
 * after an engine failure — kept up to show the reason without losing the reading position.
 *
 * Drawn as the find bar's sibling: the transport sits in an outlined pill with the same height, corner
 * radius and outline as the search pill, and the close button sits outside it on the trailing edge, the
 * same slot find puts its own close in, so the two bars read as one family.
 */
@Composable
fun TtsControls(
    state: TtsState,
    onPlayPause: () -> Unit,
    onSkipBack: () -> Unit,
    onSkipForward: () -> Unit,
    onClose: () -> Unit,
    onSpeedChange: (Float) -> Unit,
    onToggleScrollLock: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!state.isPlaying && !state.isPaused && !state.hasError) return
    // A failure takes the service down with it, and the controller was its field: skip and speed
    // have nothing to talk to. Play stays, because the viewer rebuilds the queue for it.
    val engineGone = state.hasError && !state.isPlaying && !state.isPaused
    val speedValue = stringResource(R.string.tts_speed_value, formatSpeed(state.speed))
    val speedLabel = stringResource(R.string.tts_speed) + " " + speedValue
    // Shown as the page really is: a failed read cannot hold it, whatever was chosen before.
    val locked = state.followsVoice
    // Labelled with what pressing does, as play/pause is.
    val lockLabel = stringResource(if (locked) R.string.tts_scroll_unlock else R.string.tts_scroll_lock)
    val playPauseIcon = if (state.isPlaying) Pause else Icons.Default.PlayArrow
    val playPauseLabel = if (state.isPlaying) {
        stringResource(R.string.tts_pause)
    } else {
        stringResource(R.string.tts_play)
    }
    // Read off the same source the search pill uses, so the two outlines stay one width and colour.
    val restingBorder = ButtonDefaults.outlinedButtonBorder(enabled = true)

    Column(modifier = modifier.fillMaxWidth()) {
        if (state.hasError && state.errorMessage.isNotEmpty()) {
            Text(
                text = state.errorMessage,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(start = 16.dp, bottom = 4.dp),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = ControlHeight),
                shape = RoundedCornerShape(ControlCorner),
                color = Color.Transparent,
                border = BorderStroke(restingBorder.width, MaterialTheme.colorScheme.outline),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        // The pill's own horizontal inset, matching the search pill's leading glyph.
                        .padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ViewerBarIconButton(
                        icon = SkipPrevious,
                        contentDescription = stringResource(R.string.tts_skip_back),
                        onClick = onSkipBack,
                        enabled = !engineGone,
                    )
                    ViewerBarIconButton(
                        icon = playPauseIcon,
                        contentDescription = playPauseLabel,
                        onClick = onPlayPause,
                    )
                    ViewerBarIconButton(
                        icon = SkipNext,
                        contentDescription = stringResource(R.string.tts_skip_forward),
                        onClick = onSkipForward,
                        enabled = !engineGone,
                    )
                    Spacer(Modifier.weight(1f))
                    ViewerBarIconButton(
                        painter = painterResource(if (locked) R.drawable.ic_lock else R.drawable.ic_lock_open),
                        contentDescription = lockLabel,
                        onClick = onToggleScrollLock,
                        enabled = !engineGone,
                        activeTint = if (locked) MaterialTheme.colorScheme.onPrimaryContainer else null,
                    )
                    // "1×" on its own means nothing spoken, so the button reads as a speed control.
                    TextButton(
                        onClick = { onSpeedChange(TtsController.nextSpeed(state.speed)) },
                        enabled = !engineGone,
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                    ) {
                        Text(
                            text = speedValue,
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.clearAndSetSemantics { contentDescription = speedLabel },
                        )
                    }
                }
            }
            ViewerBarIconButton(
                icon = Icons.Default.Close,
                contentDescription = stringResource(R.string.viewer_read_aloud_stop),
                onClick = onClose,
                // Same 20dp close glyph as the find bar's.
                iconSize = 20.dp,
            )
        }
    }
}

/** "1×", "1.5×", "0.5×": a whole rate drops its decimal so the chip stays narrow. */
private fun formatSpeed(speed: Float): String =
    if (speed == speed.toInt().toFloat()) speed.toInt().toString() else speed.toString()
