package com.papyrus.app.tts

import com.papyrus.app.data.DocumentFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure: which document a session is told to stop for, and which it leaves running. */
class TtsSessionTest {

    private fun sessionReading(documentId: String): TtsSession = TtsSession().apply {
        prepare(
            documentId = documentId,
            title = "Doc",
            format = DocumentFormat.TEXT,
            units = listOf(TtsUnit("hello", 0)),
            startIndex = 0,
            speed = 1f,
        )
    }

    @Test
    fun `stopFor clears the session reading that document`() {
        val session = sessionReading("7")
        session.stopFor("7")
        assertEquals("", session.state.value.documentId)
    }

    @Test
    fun `stopFor leaves another document's session alone`() {
        val session = sessionReading("7")
        session.stopFor("8")
        assertEquals("7", session.state.value.documentId)
    }

    @Test
    fun `stopFor an empty id stops nothing`() {
        val session = sessionReading("7")
        session.stopFor("")
        assertEquals("7", session.state.value.documentId)
    }

    @Test
    fun `a read starts with the scroll locked`() {
        val session = sessionReading("7")
        assertTrue(session.state.value.scrollLocked)
    }

    @Test
    fun `the lock toggles and holds while playback moves on`() {
        val session = sessionReading("7")
        session.toggleScrollLock()
        assertFalse(session.state.value.scrollLocked)

        session.syncPlayback(TtsPlayback(isPlaying = true, unitIndex = 0, anchor = 0))
        assertFalse(session.state.value.scrollLocked)

        session.setScrollLocked(true)
        assertTrue(session.state.value.scrollLocked)
    }

    @Test
    fun `a new read, or a stopped one, is locked again`() {
        val session = sessionReading("7")
        session.setScrollLocked(false)
        session.prepare("8", "Other", DocumentFormat.TEXT, listOf(TtsUnit("hi", 0)), 0, 1f)
        assertTrue(session.state.value.scrollLocked)

        session.setScrollLocked(false)
        session.stop()
        assertTrue(session.state.value.scrollLocked)
    }

    @Test
    fun `a failed read holds nothing, whatever the lock says`() {
        val session = sessionReading("7")
        assertTrue(session.state.value.followsVoice)

        session.syncPlayback(TtsPlayback(hasError = true, errorMessage = "gone"))
        assertTrue(session.state.value.scrollLocked)
        assertFalse(session.state.value.followsVoice)

        session.syncPlayback(TtsPlayback(isPlaying = true, unitIndex = 0, anchor = 0))
        session.setScrollLocked(false)
        assertFalse(session.state.value.followsVoice)
    }

    @Test
    fun `the sentence and word being read are published with the playback`() {
        val session = sessionReading("7")
        val progress = TtsProgress(text = "hello there", start = 3, sentence = 0..10, word = 6..10)

        session.syncPlayback(TtsPlayback(isPlaying = true, unitIndex = 0, anchor = 0, progress = progress))

        assertEquals(progress, session.state.value.progress)
    }

    @Test
    fun `stopFor clears a failed session too`() {
        // A failure leaves the bar up, so leaving its page must still clear it.
        val session = sessionReading("7")
        session.stopFor("7")
        assertEquals("", session.state.value.documentId)
    }
}
