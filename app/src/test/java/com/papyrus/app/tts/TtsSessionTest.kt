package com.papyrus.app.tts

import com.papyrus.app.data.DocumentFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun `stopFor clears a failed session too`() {
        // A failure leaves the bar up, so leaving its page must still clear it — isActiveFor is false here.
        val session = sessionReading("7")
        assertFalse(session.isActiveFor("7"))
        session.stopFor("7")
        assertEquals("", session.state.value.documentId)
    }
}
