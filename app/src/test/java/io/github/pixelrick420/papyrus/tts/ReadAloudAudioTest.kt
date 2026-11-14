package io.github.pixelrick420.papyrus.tts

import android.media.AudioManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadAloudAudioTest {

    private class FakeFocus(var grant: Boolean = true) : AudioFocus {
        var requests = 0
        var abandons = 0

        override fun request(): Boolean {
            requests++
            return grant
        }

        override fun abandon() {
            abandons++
        }
    }

    private val focus = FakeFocus()
    private var pauses = 0
    private var resumes = 0
    private val audio = ReadAloudAudio(focus, pause = { pauses++ }, resume = { resumes++ })

    @Test
    fun `starting to speak asks for focus once however often playback is reported`() {
        audio.onPlaybackChanged(true)
        audio.onPlaybackChanged(true)
        audio.onPlaybackChanged(true)

        assertEquals(1, focus.requests)
        assertEquals(0, pauses)
    }

    @Test
    fun `pausing gives focus back so the other app's audio can carry on`() {
        audio.onPlaybackChanged(true)
        audio.onPlaybackChanged(false)

        assertEquals(1, focus.abandons)
    }

    @Test
    fun `refused focus pauses at once and gives the claim up when the pause lands`() {
        focus.grant = false

        audio.onPlaybackChanged(true)
        assertEquals(1, pauses)

        audio.onPlaybackChanged(false)
        assertEquals(1, focus.abandons)
    }

    @Test
    fun `a short loss pauses and regaining focus resumes`() {
        audio.onPlaybackChanged(true)

        audio.onFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        audio.onPlaybackChanged(false)
        assertEquals(1, pauses)
        assertEquals("the claim is kept so the gain is heard", 0, focus.abandons)

        audio.onFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        assertEquals(1, resumes)
    }

    @Test
    fun `a request to duck pauses like a short loss`() {
        audio.onPlaybackChanged(true)

        audio.onFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)
        audio.onPlaybackChanged(false)
        audio.onFocusChange(AudioManager.AUDIOFOCUS_GAIN)

        assertEquals(1, pauses)
        assertEquals(1, resumes)
    }

    @Test
    fun `resuming after a short loss does not ask for focus again`() {
        audio.onPlaybackChanged(true)
        audio.onFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        audio.onPlaybackChanged(false)
        audio.onFocusChange(AudioManager.AUDIOFOCUS_GAIN)

        audio.onPlaybackChanged(true)

        assertEquals(1, focus.requests)
    }

    @Test
    fun `a lasting loss pauses, gives focus up and does not resume on a later gain`() {
        audio.onPlaybackChanged(true)

        audio.onFocusChange(AudioManager.AUDIOFOCUS_LOSS)
        audio.onPlaybackChanged(false)
        audio.onFocusChange(AudioManager.AUDIOFOCUS_GAIN)

        assertEquals(1, pauses)
        assertEquals(0, resumes)
        assertTrue(focus.abandons >= 1)
    }

    @Test
    fun `a lasting loss during a short one cancels the resume`() {
        audio.onPlaybackChanged(true)
        audio.onFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        audio.onPlaybackChanged(false)

        audio.onFocusChange(AudioManager.AUDIOFOCUS_LOSS)
        audio.onFocusChange(AudioManager.AUDIOFOCUS_GAIN)

        assertEquals(0, resumes)
        assertTrue(focus.abandons >= 1)
    }

    @Test
    fun `a loss while not speaking changes nothing`() {
        audio.onFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        audio.onFocusChange(AudioManager.AUDIOFOCUS_GAIN)

        assertEquals(0, pauses)
        assertEquals(0, resumes)
    }

    @Test
    fun `gaining focus with nothing waiting does not resume`() {
        audio.onPlaybackChanged(true)

        audio.onFocusChange(AudioManager.AUDIOFOCUS_GAIN)

        assertEquals(0, resumes)
    }

    @Test
    fun `stopping for good during an interruption cancels the resume and gives focus back`() {
        audio.onPlaybackChanged(true)
        audio.onFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        audio.onPlaybackChanged(false)

        audio.release()
        audio.onFocusChange(AudioManager.AUDIOFOCUS_GAIN)

        assertEquals(0, resumes)
        assertEquals(1, focus.abandons)
    }

    @Test
    fun `pressing play during a call is refused and pauses again`() {
        audio.onPlaybackChanged(true)
        audio.onFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        audio.onPlaybackChanged(false)
        focus.grant = false

        audio.onPlaybackChanged(true)

        assertEquals(2, pauses)
        assertEquals(2, focus.requests)
    }

    @Test
    fun `unplugging pauses and nothing resumes it afterwards`() {
        audio.onPlaybackChanged(true)

        audio.onBecomingNoisy()
        audio.onPlaybackChanged(false)
        audio.onFocusChange(AudioManager.AUDIOFOCUS_GAIN)

        assertEquals(1, pauses)
        assertEquals(0, resumes)
        assertEquals(1, focus.abandons)
    }

    @Test
    fun `unplugging while not speaking does nothing`() {
        audio.onBecomingNoisy()

        assertEquals(0, pauses)
    }
}
