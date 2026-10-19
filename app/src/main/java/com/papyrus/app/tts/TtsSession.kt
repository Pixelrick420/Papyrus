package com.papyrus.app.tts

import com.papyrus.app.data.DocumentFormat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Everything the read-aloud UI needs to know: which document is being read, and how playback is going.
 * Document identity and playback are published together so the in-flow bar can tell whether the
 * document on screen is the one being spoken.
 */
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
)

/**
 * App-scoped read-aloud holder (lazy in [com.papyrus.app.MainApplication]). It survives ViewModel
 * death and rotation: the viewer collects [state], hands a document to the playback service through
 * [prepare], and the service publishes the controller's playback back through [syncPlayback].
 *
 * Controls go through here so the UI never holds a controller reference; when no service is attached
 * they are no-ops, which is exactly right — the bar is only shown while something is playing or paused.
 */
class TtsSession {

    private val _state = MutableStateFlow(TtsState())
    val state: StateFlow<TtsState> = _state.asStateFlow()

    private var controller: TtsController? = null
    private var pending: PendingRequest? = null

    /** A document waiting for the service to pick it up: the queue plus where to start. */
    data class PendingRequest(
        val documentId: String,
        val title: String,
        val format: DocumentFormat,
        val units: List<TtsUnit>,
        val startIndex: Int,
        val speed: Float,
    )

    /** Record the document to read and reset playback state; the service consumes it on start. */
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

    /** The document the service should load, taken exactly once. */
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

    /** Stop playback and clear the document, so the control bar disappears. */
    fun stop() {
        controller?.stop()
        pending = null
        _state.value = TtsState()
    }

    /**
     * Stop and clear the session when it is reading [documentId]; another document's playback is left
     * alone. This is [isActiveFor] widened to the failure case: an error session has neither playing nor
     * paused set, yet the bar showing it belongs to that document's page and goes with it.
     */
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

    /** Merge the controller's playback into the published state, keeping the document identity. */
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
            )
        }
    }

    /** Whether [documentId] is the document currently being read (playing or paused). */
    fun isActiveFor(documentId: String): Boolean =
        (state.value.isPlaying || state.value.isPaused) && state.value.documentId == documentId
}
