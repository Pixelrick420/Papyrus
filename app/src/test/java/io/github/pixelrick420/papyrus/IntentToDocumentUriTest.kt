package io.github.pixelrick420.papyrus

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IntentToDocumentUriTest {

    @Test
    fun `view takes the data uri`() {
        assertEquals("content://host/doc.pdf", intentToDocumentUri(Intent.ACTION_VIEW, "content://host/doc.pdf", null))
    }

    @Test
    fun `send takes the stream instead of data`() {
        assertEquals(
            "content://host/doc.pdf",
            intentToDocumentUri(Intent.ACTION_SEND, "content://host/other.pdf", "content://host/doc.pdf"),
        )
    }

    @Test
    fun `file scheme is refused`() {
        assertNull(intentToDocumentUri(Intent.ACTION_VIEW, "file:///sdcard/doc.pdf", null))
        assertNull(intentToDocumentUri(Intent.ACTION_SEND, null, "file:///sdcard/doc.pdf"))
    }

    @Test
    fun `send without a stream is ignored`() {
        assertNull(intentToDocumentUri(Intent.ACTION_SEND, "content://host/doc.pdf", null))
    }

    @Test
    fun `unknown action is ignored`() {
        assertNull(intentToDocumentUri(Intent.ACTION_SEND_MULTIPLE, null, "content://host/doc.pdf"))
        assertNull(intentToDocumentUri(null, "content://host/doc.pdf", "content://host/doc.pdf"))
    }
}
