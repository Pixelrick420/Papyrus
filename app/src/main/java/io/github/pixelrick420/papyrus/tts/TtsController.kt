package io.github.pixelrick420.papyrus.tts

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

interface SpeechEngine {
    fun speak(text: String, utteranceId: String)

    fun stop()

    fun setSpeechRate(rate: Float)

    // While a real engine is still initialising, [speak] is tolerated and deferred: the adapter owns that wait.
    fun isAvailable(): Boolean
}

data class TtsProgress(
    val text: String = "",
    val start: Int = 0,
    val sentence: IntRange? = null,
    val word: IntRange? = null,
)

data class TtsPlayback(
    val isPlaying: Boolean = false,
    val isPaused: Boolean = false,
    val hasError: Boolean = false,
    val errorMessage: String = "",
    val unitIndex: Int = -1,
    val anchor: Int = -1,
    val speed: Float = 1.0f,
    val progress: TtsProgress = TtsProgress(),
) {
    // The word moves several times a second, and neither the notification nor the media session cares.
    fun sameTransportAs(other: TtsPlayback): Boolean =
        isPlaying == other.isPlaying &&
            isPaused == other.isPaused &&
            hasError == other.hasError &&
            errorMessage == other.errorMessage &&
            speed == other.speed
}

// The constructor is silent: nothing is spoken until [play] or [playFrom].
class TtsController(
    private val engine: SpeechEngine,
    private val engineUnavailableMessage: String = "Speech is not available",
) {

    private val _playback = MutableStateFlow(TtsPlayback())
    val playback: StateFlow<TtsPlayback> = _playback

    private var units: List<TtsUnit> = emptyList()

    /** The utterance in flight, so a completion from one we already stopped is ignored. */
    private var currentUtteranceId: String? = null
    private var utteranceSeq = 0

    // The text in flight and how much of it the engine has already spoken. The engine latches the rate
    // when an utterance is queued, so a speed change re-queues only what is left of it.
    private var currentText = ""
    private var spokenOffset = 0
    private var rangeReported = false

    // Zero until a speed change re-queues the rest of a unit, after which the engine's offsets count from there.
    private var spokenBase = 0

    private var rangesSeen = false

    private var boundsOfText: String? = null
    private var bounds = IntArray(0)

    fun setUnits(newUnits: List<TtsUnit>) {
        engine.stop()
        units = newUnits
        resetPosition()
        _playback.value = TtsPlayback(speed = _playback.value.speed)
    }

    fun playFrom(index: Int) {
        if (units.isEmpty()) return
        speakAt(index.coerceIn(0, units.lastIndex))
    }

    fun play() {
        if (units.isEmpty()) return
        val state = _playback.value
        val current = state.unitIndex
        if (current !in units.indices) {
            if (!state.isPaused) speakAt(0)
            return
        }
        if (state.isPaused && rangeReported) {
            resumeRemainder()
            return
        }
        speakAt(current)
    }

    private fun resumeRemainder() {
        val spoken = spokenOffset.coerceIn(0, currentText.length)
        val remaining = currentText.substring(spoken)
        if (remaining.isBlank()) {
            val next = _playback.value.unitIndex + 1
            if (next > units.lastIndex) {
                _playback.update { it.copy(isPlaying = false, isPaused = false) }
            } else {
                speakAt(next)
            }
            return
        }
        engine.stop()
        spokenBase += spoken
        currentText = remaining
        spokenOffset = 0
        rangeReported = false
        _playback.update { it.copy(isPlaying = true, isPaused = false) }
        speak(remaining)
    }

    fun pause() {
        if (!_playback.value.isPlaying) return
        engine.stop()
        _playback.update { it.copy(isPlaying = false, isPaused = true) }
    }

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

    // The engine latches the rate when an utterance is queued, so the rest of the unit is re-queued at the new rate from the last word reported.
    fun setSpeed(rate: Float) {
        val clamped = rate.coerceIn(MIN_SPEED, MAX_SPEED)
        _playback.update { it.copy(speed = clamped) }
        engine.setSpeechRate(clamped)
        if (_playback.value.isPlaying && rangeReported) reQueueRemainder()
    }

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

    // An [end] not past [offset] means the engine gave none, and the word is read off the text. Without timing the viewer can only mark the whole unit.
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

    fun onUtteranceError(utteranceId: String, message: String) {
        if (utteranceId != currentUtteranceId) return
        onError(message)
    }

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

    private fun moveTo(index: Int) {
        val unit = units[index]
        val progress = progressAtStartOf(unit)
        // The old unit's remainder must not be resumed: play from here speaks this one from its start.
        resetPosition()
        _playback.update { it.copy(unitIndex = index, anchor = unit.anchor, progress = progress) }
    }

    // Once any utterance has reported words the first sentence is shaded straight away; before that the whole unit is, so an engine without timing never shows the wrong sentence.
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

    private fun resetPosition() {
        currentText = ""
        spokenOffset = 0
        spokenBase = 0
        rangeReported = false
    }

    companion object {
        const val MIN_SPEED = 0.5f
        const val MAX_SPEED = 2.0f

        val SPEEDS = listOf(0.5f, 1.0f, 1.5f, 2.0f)

        fun nextSpeed(current: Float): Float {
            val at = SPEEDS.indexOfFirst { it == current }
            return SPEEDS[(at + 1).mod(SPEEDS.size)]
        }
    }
}
