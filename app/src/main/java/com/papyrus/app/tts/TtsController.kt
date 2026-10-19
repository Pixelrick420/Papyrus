package com.papyrus.app.tts

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/** The engine side of playback, abstracted so the controller runs under plain JUnit with a fake. */
interface SpeechEngine {
    /** Queue [text] to be spoken; [utteranceId] is reported back through the engine's progress listener. */
    fun speak(text: String, utteranceId: String)

    /** Stop the current utterance. Called before every jump and on pause. */
    fun stop()

    /** Apply a speaking rate to the next utterance, live, without rebuilding the queue. */
    fun setSpeechRate(rate: Float)

    /**
     * False only once a real engine has permanently failed. While it is still initialising, [speak]
     * must be tolerated and deferred: the adapter owns that wait, not the controller.
     */
    fun isAvailable(): Boolean
}

/** Playback-only state; the document's identity lives in [TtsSession]. */
data class TtsPlayback(
    val isPlaying: Boolean = false,
    val isPaused: Boolean = false,
    val hasError: Boolean = false,
    val errorMessage: String = "",
    /** Index into the unit list, or -1 before the first play and after a full stop. */
    val unitIndex: Int = -1,
    /** The follow anchor of [unitIndex], or -1 when there is none. */
    val anchor: Int = -1,
    val speed: Float = 1.0f,
)

/**
 * The playback brain: a queue of [TtsUnit]s advanced one at a time as each utterance finishes, with
 * play/pause, paragraph skip and live speed. It knows nothing about Android — it drives a
 * [SpeechEngine] and publishes [TtsPlayback] — so every branch is testable with a fake engine.
 *
 * The constructor is silent: nothing is spoken until an explicit [play] or [playFrom].
 */
class TtsController(
    private val engine: SpeechEngine,
    /** Shown when playback is attempted while the engine is permanently unavailable. */
    private val engineUnavailableMessage: String = "Speech is not available",
) {

    private val _playback = MutableStateFlow(TtsPlayback())
    val playback: StateFlow<TtsPlayback> = _playback

    private var units: List<TtsUnit> = emptyList()

    /** The utterance in flight, so a completion from one we already stopped is ignored. */
    private var currentUtteranceId: String? = null
    private var utteranceSeq = 0

    /**
     * The text in flight and how much of it the engine has already spoken. The engine latches the rate
     * when an utterance is queued, so a speed change re-queues only what is left of it.
     */
    private var currentText = ""
    private var spokenOffset = 0
    private var rangeReported = false

    /** Replace the queue. Stops any speech and resets to the start, keeping the chosen speed. */
    fun setUnits(newUnits: List<TtsUnit>) {
        engine.stop()
        units = newUnits
        resetPosition()
        _playback.value = TtsPlayback(speed = _playback.value.speed)
    }

    /** Start at [index], clamped into the document; speaks immediately. */
    fun playFrom(index: Int) {
        if (units.isEmpty()) return
        speakAt(index.coerceIn(0, units.lastIndex))
    }

    /** Resume the current unit, or start at the beginning; a paused unit is re-spoken, not skipped. */
    fun play() {
        if (units.isEmpty()) return
        val current = _playback.value.unitIndex
        speakAt(if (current in units.indices) current else 0)
    }

    /** Stop the engine and remember where we were, so [play] resumes the same unit. */
    fun pause() {
        if (!_playback.value.isPlaying) return
        engine.stop()
        _playback.update { it.copy(isPlaying = false, isPaused = true) }
    }

    /** Stop and forget the position. */
    fun stop() {
        engine.stop()
        resetPosition()
        _playback.update {
            it.copy(
                isPlaying = false,
                isPaused = false,
                hasError = false,
                errorMessage = "",
                unitIndex = -1,
                anchor = -1,
            )
        }
    }

    /** Next paragraph. Past the last unit, playback stops; while paused it only moves the remembered unit. */
    fun skipForward() {
        if (units.isEmpty()) return
        val state = _playback.value
        val next = state.unitIndex + 1
        if (next > units.lastIndex) {
            engine.stop()
            _playback.update { it.copy(isPlaying = false, isPaused = false) }
            return
        }
        if (state.isPaused) {
            _playback.update { it.copy(unitIndex = next, anchor = units[next].anchor) }
        } else {
            speakAt(next)
        }
    }

    /** Previous paragraph. At the first unit it stays put; while paused it only moves the remembered unit. */
    fun skipBack() {
        if (units.isEmpty()) return
        val state = _playback.value
        val previous = state.unitIndex - 1
        if (previous < 0) return
        if (state.isPaused) {
            _playback.update { it.copy(unitIndex = previous, anchor = units[previous].anchor) }
        } else {
            speakAt(previous)
        }
    }

    /**
     * Change the rate live. The engine latches the rate when an utterance is queued, so a change
     * mid-unit would otherwise wait for the whole paragraph; instead the rest of the unit is
     * re-queued at the new rate from the last word the engine reported. The unit, its anchor and the
     * position within it are untouched.
     */
    fun setSpeed(rate: Float) {
        val clamped = rate.coerceIn(MIN_SPEED, MAX_SPEED)
        _playback.update { it.copy(speed = clamped) }
        engine.setSpeechRate(clamped)
        if (_playback.value.isPlaying && rangeReported) reQueueRemainder()
    }

    /** Re-queue what is left of the unit in flight, at the rate just applied. */
    private fun reQueueRemainder() {
        val remaining = currentText.substring(spokenOffset.coerceIn(0, currentText.length))
        if (remaining.isBlank()) return
        engine.stop()
        currentText = remaining
        spokenOffset = 0
        rangeReported = false
        speak(remaining)
    }

    /** The engine finished a unit; advance and speak the next, or stop cleanly at the end. */
    fun onUtteranceComplete(utteranceId: String) {
        if (utteranceId != currentUtteranceId) return
        val state = _playback.value
        if (!state.isPlaying || state.hasError) return
        val next = state.unitIndex + 1
        if (next > units.lastIndex) {
            _playback.update { it.copy(isPlaying = false, isPaused = false) }
            return
        }
        speakAt(next)
    }

    /**
     * The engine is about to speak the range starting at [offset] characters into the unit in flight.
     * Only engines that supply timing report this; without it a speed change waits for the next unit.
     */
    fun onRangeStart(utteranceId: String, offset: Int) {
        if (utteranceId != currentUtteranceId || !_playback.value.isPlaying) return
        spokenOffset = offset
        rangeReported = true
    }

    /** Engine failure on the unit in flight: stop, keep the current unit, surface the error. */
    fun onUtteranceError(utteranceId: String, message: String) {
        if (utteranceId != currentUtteranceId) return
        onError(message)
    }

    /** Engine failure: stop, keep the current unit, surface the error, and speak nothing further. */
    fun onError(message: String = "Speech error") {
        engine.stop()
        _playback.update {
            it.copy(isPlaying = false, isPaused = false, hasError = true, errorMessage = message)
        }
    }

    private fun speakAt(index: Int) {
        if (index !in units.indices) return
        if (!engine.isAvailable()) {
            onError(engineUnavailableMessage)
            return
        }
        engine.setSpeechRate(_playback.value.speed)
        _playback.update {
            it.copy(
                isPlaying = true,
                isPaused = false,
                hasError = false,
                errorMessage = "",
                unitIndex = index,
                anchor = units[index].anchor,
            )
        }
        val text = units[index].text
        currentText = text
        spokenOffset = 0
        rangeReported = false
        speak(text)
    }

    /** Queue [text] under an id that is unique per call, so a replaced utterance cannot report for it. */
    private fun speak(text: String) {
        val utteranceId = "tts_${utteranceSeq++}"
        currentUtteranceId = utteranceId
        engine.speak(text, utteranceId)
    }

    /** Forget where we were in the unit in flight. */
    private fun resetPosition() {
        currentText = ""
        spokenOffset = 0
        rangeReported = false
    }

    companion object {
        const val MIN_SPEED = 0.5f
        const val MAX_SPEED = 2.0f

        /** The speeds the in-flow speed chip cycles through. */
        val SPEEDS = listOf(0.5f, 1.0f, 1.5f, 2.0f)

        /** The next speed after [current] in the chip's cycle. */
        fun nextSpeed(current: Float): Float {
            val at = SPEEDS.indexOfFirst { it == current }
            return SPEEDS[(at + 1).mod(SPEEDS.size)]
        }
    }
}
