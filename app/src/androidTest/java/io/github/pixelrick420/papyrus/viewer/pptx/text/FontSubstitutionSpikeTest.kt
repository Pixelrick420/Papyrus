package io.github.pixelrick420.papyrus.viewer.pptx.text

import android.graphics.Paint
import android.graphics.Typeface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Spike S3: how far does Carlito Regular drift from Calibri Light (headings), and do Aptos and Segoe UI
 * fall back acceptably? The pass criterion is a measured width ratio on the C-FONT sentence set, from which
 * the substitution either keeps the compatible row or moves to the nearest-width candidate.
 *
 * C-FONT is not in the repo and no machine with it was available, so the decision lives in
 * [FontSubstitution]'s KDoc: Calibri Light keeps Carlito Regular and is flagged in File info, with embedded
 * fonts in M6; Aptos and Segoe UI take Roboto and are flagged. The last test prints the substitute ratio
 * the deck will replace and only asserts the recorded decisions.
 */
@RunWith(AndroidJUnit4::class)
class FontSubstitutionSpikeTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private val catalog = FontCatalog(context.assets)

    private val substitution = FontSubstitution()

    @Test
    fun calibri_light_is_drawn_in_carlito_regular_and_flagged() {
        val choice = substitution.substitute("Calibri Light") as FontChoice.Bundled

        assertEquals(BundledFont.CARLITO, choice.font)
        assertFalse(choice.compatible)
        assertTrue(choice.substituted)
    }

    @Test
    fun aptos_and_segoe_ui_take_the_system_sans_and_are_flagged() {
        for (requested in listOf("Aptos", "Aptos Display", "Segoe UI")) {
            val choice = substitution.substitute(requested) as FontChoice.System

            assertEquals(requested, FontSubstitution.SYSTEM_SANS, choice.family)
            assertFalse(requested, choice.compatible)
        }
    }

    @Test
    fun the_substitute_width_ratio_is_recorded() {
        val carlito = width(catalog.bundled(BundledFont.CARLITO, bold = false, italic = false))
        val systemSans = width(catalog.system(FontSubstitution.SYSTEM_SANS, bold = false, italic = false))
        val ratio = carlito / systemSans

        println("S3 Carlito Regular / system sans on the sentence set: $ratio")
        assertTrue("Carlito did not draw", carlito > 0f)
        assertTrue("ratio was $ratio", ratio in PLAUSIBLE_RATIO)
    }

    private fun width(typeface: Typeface): Float =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.typeface = typeface
            textSize = SIZE_PX
            isLinearText = true
        }.measureText(SENTENCE)

    private companion object {
        const val SIZE_PX = 72f
        val PLAUSIBLE_RATIO = 0.5f..2f
        const val SENTENCE =
            "The quick brown fox jumps over the lazy dog while twelve wizards vex a quiet nymph."
    }
}
