package io.github.pixelrick420.papyrus.viewer.pptx.text

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class EmbeddedFontDecoderTest {

    @Test
    fun a_plain_ttf_takes_its_family_and_style_from_its_name_table() {
        val bytes = File(ASSETS, "ooxml/fonts/humanist/PptxHumanist-Regular.ttf").readBytes()

        val font = EmbeddedFontDecoder.decode("ppt/fonts/font1.fntdata", bytes)

        assertEquals("Pptx Humanist", font?.family)
        assertEquals(FaceStyle.REGULAR, font?.style)
        assertArrayEquals(bytes, font?.bytes)
    }

    @Test
    fun a_bold_italic_face_is_recognised_from_its_subfamily() {
        val bytes = namedFont("Large Family", "Bold Italic")

        val font = EmbeddedFontDecoder.decode("font2.fntdata", bytes)

        assertEquals("Large Family", font?.family)
        assertEquals(FaceStyle.BOLD_ITALIC, font?.style)
    }

    @Test
    fun bytes_that_are_not_an_sfnt_are_ignored() {
        assertNull(EmbeddedFontDecoder.decode("font1.fntdata", "not a font".toByteArray()))
    }

    @Test
    fun an_eot_container_declares_its_family_and_style() {
        val font = EmbeddedFontDecoder.decode("font1.fntdata", eotOf(fontBytes(), "EOT Family", "Bold Italic", 0))

        assertEquals("EOT Family", font?.family)
        assertEquals(FaceStyle.BOLD_ITALIC, font?.style)
        assertArrayEquals(fontBytes(), font?.bytes)
    }

    @Test
    fun an_xor_obfuscated_eot_is_deobfuscated() {
        val bytes = eotOf(fontBytes(), "EOT Family", "Regular", 0x10000000)

        val font = EmbeddedFontDecoder.decode("font1.fntdata", bytes)

        assertArrayEquals(fontBytes(), font?.bytes)
    }

    @Test
    fun a_compressed_eot_is_left_for_the_mtx_decoder() {
        val bytes = eotOf(fontBytes(), "EOT Family", "Regular", 0x00000004)

        assertNull(EmbeddedFontDecoder.decode("font1.fntdata", bytes))
    }

    @Test
    fun an_embedded_face_wins_over_the_substitution_table() {
        val font = EmbeddedFont("font1.fntdata", "Calibri", FaceStyle.REGULAR, ByteArray(0))

        val choice = FontSubstitution(embedded = listOf(font)).substitute("Calibri") as FontChoice.Embedded

        assertEquals(font, choice.font)
        assertEquals("Calibri", choice.requested)
        org.junit.Assert.assertTrue(choice.compatible)
        org.junit.Assert.assertFalse(choice.substituted)
    }

    private fun namedFont(family: String, subfamily: String): ByteArray {
        val strings = (family + subfamily).toByteArray(Charsets.UTF_16BE)
        val records = 2 * 12
        val header = 6 + records
        val size = 12 + 16 + header + strings.size
        val out = ByteArray(size)
        fun put16(at: Int, value: Int) {
            out[at] = (value shr 8).toByte()
            out[at + 1] = value.toByte()
        }
        fun put32(at: Int, value: Int) {
            put16(at, value ushr 16)
            put16(at + 2, value and 0xFFFF)
        }
        put32(0, 0x00010000)
        put16(4, 1)
        put32(12, 0x6E616D65.toInt())
        put32(20, 28)
        put32(24, header + strings.size)
        put16(28, 0)
        put16(30, 2)
        put16(32, header)
        listOf(1 to family, 2 to subfamily).forEachIndexed { index, (id, text) ->
            val at = 34 + index * 12
            put16(at, 3)
            put16(at + 2, 1)
            put16(at + 4, 0x0409)
            put16(at + 6, id)
            put16(at + 8, text.length * 2)
            put16(at + 10, if (index == 0) 0 else family.length * 2)
        }
        System.arraycopy(strings, 0, out, 28 + header, strings.size)
        return out
    }

    private fun fontBytes(): ByteArray = byteArrayOf(11, 22, 33, 44, 55)

    private fun eotOf(fontData: ByteArray, family: String, style: String, flags: Int): ByteArray {
        val fixed = ByteArray(82)
        fun put16(at: Int, value: Int) {
            fixed[at] = (value and 0xFF).toByte()
            fixed[at + 1] = ((value shr 8) and 0xFF).toByte()
        }
        fun put32(at: Int, value: Int) {
            put16(at, value and 0xFFFF)
            put16(at + 2, (value ushr 16) and 0xFFFF)
        }
        val strings = ArrayList<Byte>()
        fun addString(text: String) {
            val bytes = text.toByteArray(Charsets.UTF_16LE)
            strings.add((bytes.size and 0xFF).toByte())
            strings.add(((bytes.size shr 8) and 0xFF).toByte())
            bytes.forEach(strings::add)
        }
        fun addPadding() {
            strings.add(0)
            strings.add(0)
        }
        addString(family)
        addPadding()
        addString(style)
        addPadding()
        addString("Version 1.0")
        addPadding()
        addString("$family $style")

        val data = if (flags and 0x10000000 != 0) {
            fontData.map { (it.toInt() xor 0x50).toByte() }.toByteArray()
        } else {
            fontData
        }
        put32(0, fixed.size + strings.size + data.size)
        put32(4, fontData.size)
        put32(8, 0x00010000)
        put32(12, flags)
        put16(34, 0x504C)

        return fixed + ByteArray(strings.size) { strings[it] } + data
    }

    private companion object {
        val ASSETS = File("src/main/assets")
    }
}
