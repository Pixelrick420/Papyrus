package com.papyrus.app.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Form widgets in viewer terms: the platform's raw numbers become a kind, and a field decides for
 * itself whether the reader may change it. Pure, so the whole matrix needs no device.
 */
class PdfFormTest {

    private fun field(
        kind: PdfFieldKind,
        value: String = "",
        readOnly: Boolean = false,
    ) = PdfFormField(
        page = 2,
        widget = 7,
        kind = kind,
        bounds = null,
        label = null,
        value = value,
        readOnly = readOnly,
    )

    @Test
    fun `every platform widget type maps to its kind`() {
        val expected = mapOf(
            1 to PdfFieldKind.BUTTON,
            2 to PdfFieldKind.CHECKBOX,
            3 to PdfFieldKind.RADIO,
            4 to PdfFieldKind.CHOICE,
            5 to PdfFieldKind.CHOICE,
            6 to PdfFieldKind.TEXT,
            7 to PdfFieldKind.SIGNATURE,
        )
        for ((raw, kind) in expected) assertEquals("raw $raw", kind, PdfFieldKind.of(raw))
    }

    @Test
    fun `a widget type the platform does not define is unknown and never editable`() {
        for (raw in listOf(-1, 0, 8, 99)) {
            val kind = PdfFieldKind.of(raw)

            assertEquals("raw $raw", PdfFieldKind.UNKNOWN, kind)
            assertFalse(kind.editable)
            assertFalse(kind.toggles)
        }
    }

    @Test
    fun `only the value-bearing kinds are editable, and only a tick is a whole edit`() {
        assertEquals(
            listOf(PdfFieldKind.CHECKBOX, PdfFieldKind.RADIO, PdfFieldKind.CHOICE, PdfFieldKind.TEXT),
            PdfFieldKind.entries.filter { it.editable },
        )
        assertEquals(
            listOf(PdfFieldKind.CHECKBOX, PdfFieldKind.RADIO),
            PdfFieldKind.entries.filter { it.toggles },
        )
    }

    @Test
    fun `only an AcroForm is fillable`() {
        assertEquals(PdfFormType.NONE, PdfFormType.of(0))
        assertEquals(PdfFormType.ACRO_FORM, PdfFormType.of(1))
        assertEquals(PdfFormType.XFA_FULL, PdfFormType.of(2))
        assertEquals(PdfFormType.XFA_FOREGROUND, PdfFormType.of(3))
        assertEquals(PdfFormType.NONE, PdfFormType.of(4))
        assertEquals(PdfFormType.NONE, PdfFormType.of(-1))
        assertEquals(listOf(PdfFormType.ACRO_FORM), PdfFormType.entries.filter { it.fillable })
    }

    @Test
    fun `a read-only field or one with no edit is not writable`() {
        assertTrue(field(PdfFieldKind.TEXT).writable)
        assertFalse(field(PdfFieldKind.TEXT, readOnly = true).writable)
        assertFalse(field(PdfFieldKind.SIGNATURE).writable)
        assertFalse(field(PdfFieldKind.BUTTON).writable)
        assertFalse(field(PdfFieldKind.UNKNOWN).writable)
    }

    @Test
    fun `a tick is set unless the platform says Off, and a text field is never ticked`() {
        assertTrue(field(PdfFieldKind.CHECKBOX, value = "Yes").checked)
        assertTrue(field(PdfFieldKind.RADIO, value = "Choice1").checked)
        assertFalse(field(PdfFieldKind.CHECKBOX, value = "Off").checked)
        assertFalse(field(PdfFieldKind.CHECKBOX, value = "off").checked)
        assertFalse(field(PdfFieldKind.CHECKBOX, value = "").checked)
        assertFalse(field(PdfFieldKind.TEXT, value = "Yes").checked)
    }

    @Test
    fun `a field is identified by its page and widget, not by what it holds`() {
        assertEquals("2:7", field(PdfFieldKind.TEXT, value = "first").key)
        assertEquals("2:7", field(PdfFieldKind.CHOICE, value = "other").key)
    }
}
