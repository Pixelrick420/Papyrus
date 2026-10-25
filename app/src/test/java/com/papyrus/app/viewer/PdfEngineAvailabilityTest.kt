package com.papyrus.app.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The engine matrix: which platform renderer each API level and PDF SDK extension pair gets, and
 * which capabilities come with it. Pure, so this covers releases no CI device runs.
 */
class PdfEngineAvailabilityTest {

    @Test
    fun `a device below the extension falls back to the legacy renderer`() {
        for (sdk in listOf(26, 30, 31, 34)) {
            assertEquals(PdfEngineKind.LEGACY, PdfEngineAvailability.kindOf(sdk, 0))
        }
        // 12 is the last extension before PdfRendererPreV's backport.
        assertEquals(PdfEngineKind.LEGACY, PdfEngineAvailability.kindOf(31, 12))
        assertEquals(PdfEngineKind.LEGACY, PdfEngineAvailability.kindOf(34, 12))
    }

    @Test
    fun `the pre-V renderer arrives with extension 13 before API 35`() {
        assertEquals(PdfEngineKind.PRE_V, PdfEngineAvailability.kindOf(31, 13))
        assertEquals(PdfEngineKind.PRE_V, PdfEngineAvailability.kindOf(33, 15))
        assertEquals(PdfEngineKind.PRE_V, PdfEngineAvailability.kindOf(34, 17))
    }

    @Test
    fun `API 35 and later use the platform renderer directly`() {
        assertEquals(PdfEngineKind.V35, PdfEngineAvailability.kindOf(35, 0))
        assertEquals(PdfEngineKind.V35, PdfEngineAvailability.kindOf(35, 13))
        assertEquals(PdfEngineKind.V35, PdfEngineAvailability.kindOf(36, 17))
        assertEquals(PdfEngineKind.V35, PdfEngineAvailability.kindOf(36, 18))
        assertEquals(PdfEngineKind.V35, PdfEngineAvailability.kindOf(37, 0))
    }

    @Test
    fun `the legacy renderer can only draw`() {
        val caps = PdfEngineAvailability.of(26, 0)

        assertEquals(PdfEngineKind.LEGACY, caps.kind)
        assertFalse(caps.text)
        assertFalse(caps.search)
        assertFalse(caps.selection)
        assertFalse(caps.forms)
        assertFalse(caps.annotations)
    }

    @Test
    fun `a platform engine brings text, search, selection and forms`() {
        for ((sdk, extension) in listOf(31 to 13, 34 to 17, 35 to 13, 36 to 17)) {
            val caps = PdfEngineAvailability.of(sdk, extension)

            assertTrue("text at $sdk/$extension", caps.text)
            assertTrue("search at $sdk/$extension", caps.search)
            assertTrue("selection at $sdk/$extension", caps.selection)
            assertTrue("forms at $sdk/$extension", caps.forms)
        }
    }

    @Test
    fun `page annotations need extension 18 or API 37`() {
        assertFalse(PdfEngineAvailability.of(34, 17).annotations)
        assertTrue(PdfEngineAvailability.of(34, 18).annotations)
        assertFalse(PdfEngineAvailability.of(36, 17).annotations)
        assertTrue(PdfEngineAvailability.of(36, 18).annotations)
        assertTrue(PdfEngineAvailability.of(37, 0).annotations)
        assertTrue(PdfEngineAvailability.of(37, 13).annotations)
    }

    @Test
    fun `the legacy fallback claims nothing the platform could not do`() {
        val caps = PdfCapabilities.legacy()

        assertEquals(PdfEngineKind.LEGACY, caps.kind)
        assertFalse(caps.text || caps.search || caps.selection || caps.forms || caps.annotations)
    }
}
