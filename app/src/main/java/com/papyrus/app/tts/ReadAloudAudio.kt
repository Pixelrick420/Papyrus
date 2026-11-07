package com.papyrus.app.tts

import android.media.AudioManager

/** The platform's audio focus, abstracted so [ReadAloudAudio] runs under plain JUnit with a fake. */
interface AudioFocus {
    /** Asks to speak; true only when focus was granted. */
    fun request(): Boolean

    /** Gives focus back, so music another app paused can carry on. Harmless when none is held. */
    fun abandon()
}

/**
 * What reading aloud owes the rest of the device's audio: hold focus while speaking, give way to a call or
 * to another app's audio, and go quiet when the headphones come out. Every one of those is a pause, made
 * through [pause]; the speech engine belongs to the service and is never touched here. A short interruption
 * (a call, a navigation prompt) is picked up again through [resume] once focus comes back. A lasting one
 * (another app starts playing) or a plug pulled out is not: the reader decides when to go on.
 */
class ReadAloudAudio(
    private val focus: AudioFocus,
    private val pause: () -> Unit,
    private val resume: () -> Unit,
) {
    /** Whether the controller is speaking, as last reported; only changes in [onPlaybackChanged]. */
    private var speaking = false
    private var hasFocus = false

    /** Set while a short interruption has paused the speech, so focus coming back is a reason to go on. */
    private var resumeOnGain = false

    /** The controller's playback changed. Only the moment speech starts or stops matters, so repeats are ignored. */
    fun onPlaybackChanged(isSpeaking: Boolean) {
        if (isSpeaking == speaking) return
        speaking = isSpeaking
        if (isSpeaking) begin() else end()
    }

    /** One of the `AudioManager.AUDIOFOCUS_*` changes, as the platform reports it. */
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

    /** The output is about to switch to the speaker: headphones unplugged, a Bluetooth link dropped. */
    fun onBecomingNoisy() {
        if (!speaking) return
        resumeOnGain = false
        pause()
    }

    /** Reading is over for good: give focus back and forget an interruption that was waiting to be undone. */
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
        // A call, or another app that will not share: nothing may be spoken. The pause reports back as a
        // stop, which is where the claim is given up.
        if (!hasFocus) pause()
    }

    private fun end() {
        // Paused by a short interruption: keep the claim, or focus coming back could never be heard.
        if (resumeOnGain) return
        hasFocus = false
        focus.abandon()
    }
}
