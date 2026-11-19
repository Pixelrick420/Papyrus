package io.github.pixelrick420.papyrus.viewer.pptx.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FontSubstitutionTest {

    private val substitution = FontSubstitution()

    @Test
    fun appendix_b_rows_map_to_their_intended_font() {
        val rows = mapOf(
            "Calibri" to (BundledFont.CARLITO to true),
            "Calibri Light" to (BundledFont.CARLITO to false),
            "Cambria" to (BundledFont.CALADEA to true),
            "Arial" to (BundledFont.ARIMO to true),
            "Times New Roman" to (BundledFont.TINOS to true),
            "Courier New" to (BundledFont.COUSINE to true),
        )

        for ((requested, expected) in rows) {
            val choice = substitution.substitute(requested) as FontChoice.Bundled

            assertEquals(requested, expected.first, choice.font)
            assertEquals(requested, expected.second, choice.compatible)
            assertTrue("$requested was not flagged", choice.substituted)
        }
    }

    @Test
    fun rows_without_a_metric_compatible_font_are_flagged() {
        val rows = listOf(
            "Calibri Light",
            "Helvetica",
            "Helvetica Neue",
            "Arial Narrow",
            "Arial Black",
            "Impact",
            "Consolas",
            "Lucida Console",
            "Menlo",
            "Segoe UI",
            "Aptos",
            "Aptos Display",
            "Aptos Narrow",
            "Verdana",
            "Tahoma",
            "Trebuchet MS",
            "Georgia",
            "Garamond",
            "Palatino",
            "Book Antiqua",
        )

        for (requested in rows) {
            assertFalse(requested, substitution.substitute(requested).compatible)
        }
    }

    @Test
    fun the_sans_rows_take_the_system_sans_face() {
        for (requested in listOf("Segoe UI", "Aptos", "Aptos Display", "Aptos Narrow", "Verdana", "Tahoma")) {
            assertEquals(requested, FontSubstitution.SYSTEM_SANS, system(requested).family)
        }
    }

    @Test
    fun the_serif_rows_take_the_system_serif_face() {
        for (requested in listOf("Georgia", "Garamond", "Palatino", "Book Antiqua")) {
            assertEquals(requested, FontSubstitution.SYSTEM_SERIF, system(requested).family)
        }
    }

    @Test
    fun symbol_and_cjk_rows_use_the_platform_chain() {
        val rows = listOf(
            "Symbol", "Wingdings", "Wingdings 2", "Wingdings 3", "Webdings",
            "MS Gothic", "Yu Gothic", "Meiryo", "Malgun Gothic", "SimSun", "Microsoft YaHei",
        )

        for (requested in rows) {
            val choice = substitution.substitute(requested)

            assertTrue(requested, choice is FontChoice.Platform)
            assertTrue(requested, choice.substituted)
            assertFalse(requested, choice.compatible)
        }
    }

    @Test
    fun an_installed_family_is_used_before_any_fallback() {
        val withFonts = FontSubstitution { it.equals("Acme Sans", ignoreCase = true) }

        val choice = withFonts.substitute("Acme Sans") as FontChoice.System

        assertEquals("Acme Sans", choice.family)
        assertFalse(choice.substituted)
        assertTrue(choice.compatible)
    }

    @Test
    fun the_bundled_table_wins_over_an_installed_family() {
        val withFonts = FontSubstitution { it == "Calibri" }

        assertEquals(BundledFont.CARLITO, (withFonts.substitute("Calibri") as FontChoice.Bundled).font)
    }

    @Test
    fun an_unmapped_serif_panose_falls_back_to_the_system_serif() {
        assertEquals(FontSubstitution.SYSTEM_SERIF, system("Acme Text", panose = "02050B00000000000000").family)
    }

    @Test
    fun an_unmapped_sans_panose_falls_back_to_the_system_sans() {
        assertEquals(FontSubstitution.SYSTEM_SANS, system("Acme Text", panose = "020B0600000000000000").family)
    }

    @Test
    fun a_symbol_panose_uses_the_platform_chain() {
        assertTrue(substitution.substitute("Acme Symbols", panose = "05000000000000000000") is FontChoice.Platform)
    }

    @Test
    fun a_fixed_pitch_hint_takes_the_bundled_mono_face() {
        val choice = substitution.substitute("Acme Code", pitchFamily = "31") as FontChoice.Bundled

        assertEquals(BundledFont.COUSINE, choice.font)
    }

    @Test
    fun a_roman_pitch_hint_takes_the_system_serif() {
        assertEquals(FontSubstitution.SYSTEM_SERIF, system("Acme Text", pitchFamily = "11").family)
    }

    @Test
    fun a_swiss_pitch_hint_takes_the_system_sans() {
        assertEquals(FontSubstitution.SYSTEM_SANS, system("Acme Text", pitchFamily = "21").family)
    }

    @Test
    fun panose_outranks_the_pitch_hint() {
        assertEquals(
            FontSubstitution.SYSTEM_SERIF,
            system("Acme Text", panose = "02050B00000000000000", pitchFamily = "21").family,
        )
    }

    @Test
    fun names_match_regardless_of_case_and_spacing() {
        assertEquals(BundledFont.TINOS, (substitution.substitute("  times   new roman ") as FontChoice.Bundled).font)
    }

    @Test
    fun a_missing_family_is_not_a_substitution() {
        val choice = substitution.substitute(null) as FontChoice.System

        assertEquals(FontSubstitution.SYSTEM_SANS, choice.family)
        assertEquals(null, choice.requested)
        assertFalse(choice.substituted)
        assertTrue(choice.compatible)
    }

    @Test
    fun a_blank_family_is_not_a_substitution() {
        assertFalse(substitution.substitute("   ").substituted)
    }

    @Test
    fun an_unmapped_family_without_hints_takes_the_system_sans() {
        val choice = system("Acme Text")

        assertEquals(FontSubstitution.SYSTEM_SANS, choice.family)
        assertTrue(choice.substituted)
        assertFalse(choice.compatible)
    }

    @Test
    fun a_bundled_choice_keeps_the_requested_name_for_file_info() {
        assertEquals("Calibri Light", substitution.substitute("Calibri Light").requested)
    }

    private fun system(name: String, panose: String? = null, pitchFamily: String? = null): FontChoice.System =
        substitution.substitute(name, panose, pitchFamily) as FontChoice.System
}
