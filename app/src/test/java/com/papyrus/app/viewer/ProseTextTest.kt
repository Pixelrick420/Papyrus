package com.papyrus.app.viewer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Hyphenation is for words: a wrong answer either splits a log line or leaves an essay ragged. */
class ProseTextTest {

    @Test
    fun `a txt or text file is prose`() {
        assertTrue(isProse("notes.txt", isCode = false))
        assertTrue(isProse("notes.text", isCode = false))
        assertTrue(isProse("README", isCode = false))
    }

    @Test
    fun `a log is not prose, whatever its case or whether it has a dot`() {
        assertFalse(isProse("server.log", isCode = false))
        assertFalse(isProse("SERVER.LOG", isCode = false))
        assertFalse(isProse("log", isCode = false))
    }

    @Test
    fun `only the final extension counts`() {
        // Same rule as format detection: report.log.txt is text, and notes.txt.log is a log.
        assertTrue(isProse("report.log.txt", isCode = false))
        assertFalse(isProse("notes.txt.log", isCode = false))
    }

    @Test
    fun `anything opened as code is never prose`() {
        assertFalse(isProse("main.kt", isCode = true))
        assertFalse(isProse("Makefile", isCode = true))
        // A sniffed file has no useful name, and code does not hyphenate whatever it is called.
        assertFalse(isProse("notes.txt", isCode = true))
    }

    @Test
    fun `a missing name is prose unless the file is code`() {
        assertTrue(isProse(null, isCode = false))
        assertFalse(isProse(null, isCode = true))
    }
}
