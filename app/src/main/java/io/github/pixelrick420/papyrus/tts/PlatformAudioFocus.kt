package io.github.pixelrick420.papyrus.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper

internal class PlatformAudioFocus(
    context: Context,
    attributes: AudioAttributes,
    onChange: (Int) -> Unit,
) : AudioFocus {

    private val manager = context.getSystemService(AudioManager::class.java)

    private val claim = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(attributes)
        // Speech is paused for a request to duck, not talked over, so the system must leave that to us.
        .setWillPauseWhenDucked(true)
        .setOnAudioFocusChangeListener(
            AudioManager.OnAudioFocusChangeListener { change -> onChange(change) },
            Handler(Looper.getMainLooper()),
        )
        .build()

    override fun request(): Boolean =
        manager.requestAudioFocus(claim) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED

    override fun abandon() {
        manager.abandonAudioFocusRequest(claim)
    }
}
