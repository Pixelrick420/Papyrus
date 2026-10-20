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

/**
 * Where the engine is inside the unit being read, in the unit's own text, for the viewer to draw.
 * Only engines that report timing ([TtsController.onRangeStart]) move it past the unit itself.
 */
data class TtsProgress(
    /** The unit's text, which [sentence] and [word] index. Empty when nothing is being read. */
    val text: String = "",
    /** Where [text] begins in its anchor's own text: a chunk, a page, the rendered Markdown, a block. */
    val start: Int = 0,
    /** The sentence being read; null until the engine reports a word, when the whole unit stands in. */
    val sentence: IntRange? = null,
    /** The word being read; null until the engine reports one. */
    val word: IntRange? = null,
)

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
    /** The sentence and word being read inside [unitIndex]. */
    val progress: TtsProgress = TtsProgress(),
) {
    /**
     * Whether the notification and the media session would look the same for [other]. The word
     * moves several times a second, and neither of them has anything to say about it.
     */
    fun sameTransportAs(other: TtsPlayback): Boolean =
        isPlaying == other.isPlaying &&
            isPaused == other.isPaused &&
            hasError == other.hasError &&
            errorMessage == other.errorMessage &&
            speed == other.speed
}

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

    /**
     * Where [currentText] begins in the unit's own text: zero, until a speed change re-queues the rest
     * of a unit, after which the engine's offsets count from there.
     */
    private var spokenBase = 0

    /** Whether any utterance has reported a range, so the next unit can start on its first sentence. */
    private var rangesSeen = false

    /** The sentence starts of the unit last asked about, found once for it, by identity of its text. */
    private var boundsOfText: String? = null
    private var bounds = IntArray(0)

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
                progress = TtsProgress(),
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
            moveTo(next)
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
            moveTo(previous)
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
        val spoken = spokenOffset.coerceIn(0, currentText.length)
        val remaining = currentText.substring(spoken)
        if (remaining.isBlank()) return
        engine.stop()
        spokenBase += spoken
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
     * The engine is about to speak the range from [offset] to [end] characters into the utterance in
     * flight; an [end] that is not past [offset] means the engine gave none, and the word is read off
     * the text. Only engines that supply timing report this; without it a speed change waits for the
     * next unit and the viewer can only mark the whole unit.
     */
    fun onRangeStart(utteranceId: String, offset: Int, end: Int = -1) {
        if (utteranceId != currentUtteranceId || !_playback.value.isPlaying) return
        spokenOffset = offset
        rangeReported = true
        rangesSeen = true
        val text = units.getOrNull(_playback.value.unitIndex)?.text ?: return
        // The engine counts from where the last re-queue began; the viewer counts from the unit's start.
        val start = spokenBase + offset
        if (start !in text.indices) return
        val wordEnd = if (end > offset) spokenBase + end else TtsAlignment.wordEnd(text, start)
        val word = start until wordEnd.coerceIn(start + 1, text.length)
        val sentence = TtsAlignment.sentenceAt(text, boundsOf(text), start)
        _playback.update { it.copy(progress = it.progress.copy(sentence = sentence, word = word)) }
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
        val unit = units[index]
        val progress = progressAtStartOf(unit)
        _playback.update {
            it.copy(
                isPlaying = true,
                isPaused = false,
                hasError = false,
                errorMessage = "",
                unitIndex = index,
                anchor = unit.anchor,
                progress = progress,
            )
        }
        resetPosition()
        currentText = unit.text
        speak(unit.text)
    }

    /** Only the remembered unit moves: nothing is spoken, so nothing but where the reader is changes. */
    private fun moveTo(index: Int) {
        val unit = units[index]
        val progress = progressAtStartOf(unit)
        _playback.update { it.copy(unitIndex = index, anchor = unit.anchor, progress = progress) }
    }

    /**
     * A unit before its first word is reported. Once any utterance has reported words, the engine is
     * known to, and the first sentence is shaded straight away; before that the whole unit is, so an
     * engine without timing is never shown reading the wrong sentence.
     */
    private fun progressAtStartOf(unit: TtsUnit): TtsProgress = TtsProgress(
        text = unit.text,
        start = unit.start,
        sentence = if (rangesSeen) TtsAlignment.sentenceAt(unit.text, boundsOf(unit.text), 0) else null,
    )

    private fun boundsOf(text: String): IntArray {
        if (boundsOfText !== text) {
            bounds = TtsAlignment.sentenceBounds(text)
            boundsOfText = text
        }
        return bounds
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
        spokenBase = 0
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
