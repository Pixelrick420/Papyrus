package io.github.pixelrick420.papyrus.tts

import android.media.AudioManager

interface AudioFocus {
    fun request(): Boolean

    fun abandon()
}

// A short interruption (a call, a navigation prompt) is resumed once focus returns; a lasting one (another app plays) or a pulled plug is not: the reader decides when to go on.
class ReadAloudAudio(
    private val focus: AudioFocus,
    private val pause: () -> Unit,
    private val resume: () -> Unit,
) {
    private var speaking = false
    private var hasFocus = false

    private var resumeOnGain = false

    fun onPlaybackChanged(isSpeaking: Boolean) {
        if (isSpeaking == speaking) return
        speaking = isSpeaking
        if (isSpeaking) begin() else end()
    }

    fun onFocusChange(change: Int) {
        when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                hasFocus = true
                if (resumeOnGain) {
                    resumeOnGain = false
                    resume()
                }
            }
            // A request to duck is a pause too: speech talked over by other audio cannot be followed.
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                hasFocus = false
                if (speaking) {
                    resumeOnGain = true
                    pause()
                }
            }
            AudioManager.AUDIOFOCUS_LOSS -> {
                hasFocus = false
                resumeOnGain = false
                focus.abandon()
                if (speaking) pause()
            }
        }
    }

    fun onBecomingNoisy() {
        if (!speaking) return
        resumeOnGain = false
        pause()
    }

    fun release() {
        speaking = false
        hasFocus = false
        resumeOnGain = false
        focus.abandon()
    }

    private fun begin() {
        resumeOnGain = false
        if (hasFocus) return
        hasFocus = focus.request()
        // The pause reports back as a stop, which is where the claim is given up.
        if (!hasFocus) pause()
    }

    private fun end() {
        // Paused by a short interruption: keep the claim, or focus coming back could never be heard.
        if (resumeOnGain) return
        hasFocus = false
        focus.abandon()
    }
}
