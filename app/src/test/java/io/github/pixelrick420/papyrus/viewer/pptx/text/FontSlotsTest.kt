package io.github.pixelrick420.papyrus.viewer.pptx.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class FontSlotsTest {

    private val slots = FontSlots()

    @Test
    fun `a character takes the slot its Unicode range belongs to`() {
        assertEquals(FontSlot.LATIN, FontSlots.slotOf('A'.code))
        assertEquals(FontSlot.EAST_ASIAN, FontSlots.slotOf('あ'.code))
        assertEquals(FontSlot.EAST_ASIAN, FontSlots.slotOf('한'.code))
        assertEquals(FontSlot.COMPLEX_SCRIPT, FontSlots.slotOf('ا'.code))
        assertEquals(FontSlot.COMPLEX_SCRIPT, FontSlots.slotOf('א'.code))
        assertEquals(FontSlot.SYMBOL, FontSlots.slotOf('→'.code))
        assertEquals(FontSlot.SYMBOL, FontSlots.slotOf(0xE000))
    }

    @Test
    fun `the latin slot resolves the latin typeface`() {
        val font = slots.resolve(TextRunProperties(latinTypeface = "Calibri"), 'A'.code)

        val choice = font.choice as FontChoice.Bundled
        assertEquals("Calibri", choice.requested)
        assertEquals("Pptx Humanist", choice.font.family)
    }

    @Test
    fun `a missing east Asian or complex script typeface falls back to the latin one`() {
        val properties = TextRunProperties(latinTypeface = "Arial", eastAsianTypeface = null)

        val latin = slots.resolve(properties, 'A'.code)
        val eastAsian = slots.resolve(properties, 'あ'.code)

        assertEquals((latin.choice as FontChoice.Bundled).font, (eastAsian.choice as FontChoice.Bundled).font)
    }

    @Test
    fun `a declared east Asian typeface outranks the latin one`() {
        val properties = TextRunProperties(
            latinTypeface = "Arial",
            eastAsianTypeface = "MS Gothic",
            complexScriptTypeface = "Arial",
        )

        assertEquals("MS Gothic", slots.resolve(properties, 'あ'.code).choice.requested)
        assertEquals("Arial", slots.resolve(properties, 'ا'.code).choice.requested)
    }

    @Test
    fun `weight and slant travel with the resolved font`() {
        val properties = TextRunProperties(latinTypeface = "Arial", bold = true, italic = true)

        val font = slots.resolve(properties, 'A'.code)

        assertTrue(font.bold)
        assertTrue(font.italic)
    }

    @Test
    fun `a repeat character resolves to the same instance`() {
        val properties = TextRunProperties(latinTypeface = "Arial")

        assertSame(slots.resolve(properties, 'A'.code), slots.resolve(properties, 'A'.code))
        assertSame(slots.resolve(properties, 'A'.code), slots.resolve(properties, 'B'.code))
    }
}
