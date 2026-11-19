package io.github.pixelrick420.papyrus.viewer.pptx.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SymbolFontsTest {

    private val fonts = SymbolFonts()

    @Test
    fun wingdings_bullets_take_their_encoded_glyph() {
        val rows = mapOf(
            'l' to "\u26AB",
            'n' to "\u25FC",
            'q' to "\u2751",
            'r' to "\u2752",
            'u' to "\u25C6",
            'v' to "\u2756",
            '§' to "\u25AA",
            '«' to "\u2605",
            'Ø' to "\u2BA4",
        )

        for ((char, expected) in rows) {
            assertEquals(char.toString(), expected, text(fonts, "Wingdings", char))
        }
    }

    @Test
    fun wingdings_two_and_three_bullets_take_their_encoded_glyph() {
        assertEquals("\u25A0", text(fonts, "Wingdings 2", '¢'))
        assertEquals("\u25C7", text(fonts, "Wingdings 2", '¯'))
        assertEquals("\u25CA", text(fonts, "Wingdings 2", '¸'))
        assertEquals("\u25B2", text(fonts, "Wingdings 3", 'o'))
        assertEquals("\u25BC", text(fonts, "Wingdings 3", 'p'))
    }

    @Test
    fun webdings_and_symbol_bullets_take_their_encoded_glyph() {
        assertEquals("\u2B1B", text(fonts, "Webdings", 'g'))
        assertEquals("\u2B24", text(fonts, "Webdings", 'n'))
        assertEquals("\u2022", text(fonts, "Symbol", '·'))
        assertEquals("\u22C5", text(fonts, "Symbol", '×'))
        assertEquals("\u25CA", text(fonts, "Symbol", 'à'))
    }

    @Test
    fun a_legacy_encoded_glyph_carries_vector_geometry_and_its_fallback() {
        val glyph = fonts.glyph("Wingdings", "m") as SymbolGlyph.Vector

        assertEquals("\u2BC3", glyph.fallback)
        assertTrue(glyph.shape.paths.isNotEmpty())
    }

    @Test
    fun the_vector_table_covers_rings_boxes_diamonds_and_stars() {
        val vectors = listOf('m', 'o', 'p', 's', 'w', '¢', '£', '¤', '¨', '©', 'ª', '¬', '®', '¯', '°', '±', '²', '³')

        for (char in vectors) {
            assertTrue(char.toString(), fonts.glyph("Wingdings", char.toString()) is SymbolGlyph.Vector)
        }
    }

    @Test
    fun wingdings_two_has_vector_buttons_targets_and_lozenges() {
        for (char in listOf('¦', '§', '¨', '©', '°', '±', '²', '³', '´')) {
            assertTrue(char.toString(), fonts.glyph("Wingdings 2", char.toString()) is SymbolGlyph.Vector)
        }
    }

    @Test
    fun every_vector_is_inside_the_unit_box() {
        val frames = listOf("Wingdings", "Wingdings 2")

        for (font in frames) {
            for (code in 0x21..0xFF) {
                val glyph = fonts.glyph(font, code.toChar().toString()) as? SymbolGlyph.Vector ?: continue
                for (path in glyph.shape.paths) {
                    for (segment in path.segments) {
                        for (point in pointsOf(segment)) {
                            assertTrue("$font ${code.toString(16)} $point", point in -0.05..1.05)
                        }
                    }
                }
            }
        }
    }

    @Test
    fun a_font_name_matches_regardless_of_case_and_spacing() {
        assertEquals("\u26AB", text(fonts, "  wingdings ", 'l'))
        assertEquals("\u25A0", text(fonts, "WINGDINGS 2", '¢'))
    }

    @Test
    fun a_non_symbol_family_is_not_mapped() {
        assertNull(fonts.glyph("Arial", "l"))
        assertNull(fonts.glyph(null, "l"))
        assertNull(fonts.glyph("Arial Narrow", "l"))
    }

    @Test
    fun an_unmapped_character_is_not_mapped() {
        assertNull(fonts.glyph("Wingdings", "A"))
        assertNull(fonts.glyph("Webdings", "l"))
        assertNull(fonts.glyph("Symbol", "a"))
    }

    @Test
    fun an_empty_character_is_not_mapped() {
        assertNull(fonts.glyph("Wingdings", ""))
    }

    private fun text(fonts: SymbolFonts, font: String, char: Char): String =
        (fonts.glyph(font, char.toString()) as SymbolGlyph.Text).text

    private fun pointsOf(segment: SymbolSegment): List<Double> = when (segment) {
        is SymbolSegment.MoveTo -> listOf(segment.x, segment.y)
        is SymbolSegment.LineTo -> listOf(segment.x, segment.y)
        is SymbolSegment.CubicTo -> listOf(
            segment.control1X,
            segment.control1Y,
            segment.control2X,
            segment.control2Y,
            segment.x,
            segment.y,
        )
        SymbolSegment.Close -> emptyList()
    }
}
