package com.papyrus.app.tts

import com.papyrus.app.data.DocumentFormat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

// Document identity and playback are published together so the bar can tell whether the document on screen is the one being spoken.
data class TtsState(
    val documentId: String = "",
    val documentTitle: String = "",
    val format: DocumentFormat? = null,
    val isPlaying: Boolean = false,
    val isPaused: Boolean = false,
    val hasError: Boolean = false,
    val errorMessage: String = "",
    val unitIndex: Int = -1,
    val anchor: Int = -1,
    val speed: Float = 1.0f,
    val progress: TtsProgress = TtsProgress(),
    val scrollLocked: Boolean = true,
) {
    // A failed read has nothing to follow and must never leave the reader unable to scroll.
    val followsVoice: Boolean get() = scrollLocked && !hasError
}

// App-scoped so it survives ViewModel death and rotation; controls are no-ops when no service is attached, since the bar shows only while something plays or is paused.
class TtsSession {

    private val _state = MutableStateFlow(TtsState())
    val state: StateFlow<TtsState> = _state.asStateFlow()

    private var controller: TtsController? = null
    private var pending: PendingRequest? = null

    data class PendingRequest(
        val documentId: String,
        val title: String,
        val format: DocumentFormat,
        val units: List<TtsUnit>,
        val startIndex: Int,
        val speed: Float,
    )

    fun prepare(
        documentId: String,
        title: String,
        format: DocumentFormat,
        units: List<TtsUnit>,
        startIndex: Int,
        speed: Float,
    ) {
        pending = PendingRequest(documentId, title, format, units, startIndex, speed)
        _state.value = TtsState(
            documentId = documentId,
            documentTitle = title,
            format = format,
            speed = speed,
        )
    }

    fun consumePending(): PendingRequest? = pending.also { pending = null }

    fun attachController(controller: TtsController) {
        this.controller = controller
    }

    fun detachController(controller: TtsController) {
        if (this.controller === controller) this.controller = null
    }

    fun play() {
        controller?.play()
    }

    fun pause() {
        controller?.pause()
    }

    fun stop() {
        controller?.stop()
        pending = null
        _state.value = TtsState()
    }

    // A failed session counts too: it has neither playing nor paused set, yet its bar belongs to that document's page.
    fun stopFor(documentId: String) {
        if (documentId.isNotEmpty() && state.value.documentId == documentId) stop()
    }

    fun skipForward() {
        controller?.skipForward()
    }

    fun skipBack() {
        controller?.skipBack()
    }

    fun setSpeed(rate: Float) {
        controller?.setSpeed(rate)
    }

    fun setScrollLocked(locked: Boolean) {
        _state.update { if (it.scrollLocked == locked) it else it.copy(scrollLocked = locked) }
    }

    fun toggleScrollLock() {
        _state.update { it.copy(scrollLocked = !it.scrollLocked) }
    }

    fun syncPlayback(playback: TtsPlayback) {
        _state.update {
            it.copy(
                isPlaying = playback.isPlaying,
                isPaused = playback.isPaused,
                hasError = playback.hasError,
                errorMessage = playback.errorMessage,
                unitIndex = playback.unitIndex,
                anchor = playback.anchor,
                speed = playback.speed,
                progress = playback.progress,
            )
        }
    }
}
