package io.github.pixelrick420.papyrus.crash

import org.junit.Assert.assertEquals
import org.junit.Test

class LocalFileReportSenderRedactionTest {

    private val roots = listOf(
        "/data/user/0/io.github.pixelrick420.papyrus/files",
        "/data/user/0/io.github.pixelrick420.papyrus/cache",
    )

    @Test
    fun `redacts known roots even when the file name has spaces`() {
        assertEquals(
            "open [redacted]",
            LocalFileReportSender.redact("open /data/user/0/io.github.pixelrick420.papyrus/files/My Contract.pdf", roots),
        )
        assertEquals(
            "open [redacted]",
            LocalFileReportSender.redact(
                "open /data/user/0/io.github.pixelrick420.papyrus/cache/unlocked/slide deck.pptx",
                roots,
            ),
        )
    }

    @Test
    fun `redacts external storage paths`() {
        assertEquals(
            "failed on [redacted]",
            LocalFileReportSender.redact("failed on /storage/emulated/0/Documents/My Contract.pdf", roots),
        )
        assertEquals(
            "[redacted]",
            LocalFileReportSender.redact("/sdcard/Download/quarterly report.pdf", roots),
        )
        assertEquals(
            "[redacted]",
            LocalFileReportSender.redact("/mnt/sdcard/notes.txt", roots),
        )
    }

    @Test
    fun `redacts content and file URIs and import paths`() {
        assertEquals(
            "from [redacted]",
            LocalFileReportSender.redact("from content://com.android.providers.downloads/My%20Contract.pdf", roots),
        )
        assertEquals(
            "from [redacted]",
            LocalFileReportSender.redact("from file:///storage/emulated/0/My Contract.pdf", roots),
        )
        assertEquals(
            "from [redacted]",
            LocalFileReportSender.redact("from /data/imports/Report.pdf", roots),
        )
    }

    @Test
    fun `leaves unrelated text and relative paths intact`() {
        val text = "at com.example.Reader.open(Reader.kt:42) with docs/My Contract.pdf"
        assertEquals(text, LocalFileReportSender.redact(text, roots))
    }

    @Test
    fun `single-root overload still covers the original shapes`() {
        val root = "/data/user/0/io.github.pixelrick420.papyrus/files"
        assertEquals("[redacted]", LocalFileReportSender.redact("$root/My Contract.pdf", root))
        assertEquals("a [redacted] b", LocalFileReportSender.redact("a content://host/x b", root))
    }
}
