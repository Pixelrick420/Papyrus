package com.papyrus.app.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A re-pick is accepted only for the same name and format; anything looser could re-point a row at the wrong file. */
class RegrantMatchTest {

    private fun matches(
        title: String,
        format: DocumentFormat,
        picked: String,
        pickedFormat: DocumentFormat = DocumentFormat.detect(picked, null),
    ) = matchesRepick(title, format, picked, pickedFormat)

    @Test
    fun `the same name and format matches`() {
        assertTrue(matches("report.pdf", DocumentFormat.PDF, "report.pdf"))
    }

    @Test
    fun `a name that merely starts with the title is rejected`() {
        // The old rule accepted all of these.
        assertFalse(matches("report.pdf", DocumentFormat.PDF, "report.pdf.bak"))
        assertFalse(matches("report", DocumentFormat.PDF, "report.pdf"))
        assertFalse(matches("notes", DocumentFormat.TEXT, "notes-final.txt"))
    }

    @Test
    fun `a longer or shorter name is rejected`() {
        assertFalse(matches("report.pdf", DocumentFormat.PDF, "report.pd"))
        assertFalse(matches("report.pdf", DocumentFormat.PDF, "my report.pdf"))
    }

    @Test
    fun `names are compared case-sensitively`() {
        assertFalse(matches("Report.pdf", DocumentFormat.PDF, "report.pdf"))
    }

    @Test
    fun `the same name with a different format is rejected`() {
        // A provider can report a type that disagrees with the stored one for an extension-less name.
        assertFalse(matches("data", DocumentFormat.PDF, "data", DocumentFormat.TEXT))
        assertFalse(matches("report.docx", DocumentFormat.DOCX, "report.docx", DocumentFormat.PDF))
    }

    @Test
    fun `a blank picked name falls back to untitled the way the entity builder did`() {
        assertTrue(matches(UNTITLED, DocumentFormat.CODE, "", DocumentFormat.CODE))
        assertTrue(matches(UNTITLED, DocumentFormat.CODE, "   ", DocumentFormat.CODE))
    }

    @Test
    fun `an untitled document is not matched by a pick that has a real name`() {
        assertFalse(matches(UNTITLED, DocumentFormat.CODE, "report.txt", DocumentFormat.CODE))
    }

    @Test
    fun `a named document is not matched by a nameless pick`() {
        assertFalse(matches("report.pdf", DocumentFormat.PDF, "", DocumentFormat.PDF))
    }

    @Test
    fun `a row stored as unknown matches the same name once it sniffs as code`() {
        assertTrue(matches("deploy", DocumentFormat.UNKNOWN, "deploy", DocumentFormat.CODE))
        assertTrue(matches("deploy", DocumentFormat.CODE, "deploy", DocumentFormat.UNKNOWN))
    }

    @Test
    fun `unknown and code are the only formats treated as one`() {
        assertFalse(matches("deploy", DocumentFormat.UNKNOWN, "deploy", DocumentFormat.PDF))
        assertFalse(matches("deploy", DocumentFormat.TEXT, "deploy", DocumentFormat.CODE))
    }
}
