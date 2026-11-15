package io.github.pixelrick420.papyrus.viewer.pptx.text

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SfntMetricsTest {

    @Test
    fun reads_head_hhea_and_os2() {
        val metrics = SfntMetrics.parse(font())!!

        assertEquals(2048, metrics.unitsPerEm)
        assertEquals(1950, metrics.ascender)
        assertEquals(-550, metrics.descender)
        assertEquals(0, metrics.lineGap)
        assertEquals(1536, metrics.typoAscender)
        assertEquals(-512, metrics.typoDescender)
        assertEquals(452, metrics.typoLineGap)
        assertEquals(1950, metrics.winAscent)
        assertEquals(550, metrics.winDescent)
    }

    @Test
    fun win_metrics_apply_when_fsSelection_bit_7_is_clear() {
        val metrics = SfntMetrics.parse(font(selection = 0x40))!!

        assertFalse(metrics.useTypoMetrics)
        assertEquals(1950.0 / 2048.0, metrics.ascentEm, 1e-9)
        assertEquals(550.0 / 2048.0, metrics.descentEm, 1e-9)
        assertEquals((1950 + 550) / 2048.0, metrics.lineHeightEm, 1e-9)
    }

    @Test
    fun typo_metrics_apply_when_fsSelection_bit_7_is_set() {
        val metrics = SfntMetrics.parse(font(selection = 0x80))!!

        assertTrue(metrics.useTypoMetrics)
        assertEquals(1536.0 / 2048.0, metrics.ascentEm, 1e-9)
        assertEquals(512.0 / 2048.0, metrics.descentEm, 1e-9)
        assertEquals((1536 + 512 + 452) / 2048.0, metrics.lineHeightEm, 1e-9)
    }

    @Test
    fun bold_and_italic_come_from_fsSelection() {
        assertTrue(SfntMetrics.parse(font(selection = 0x21))!!.bold)
        assertTrue(SfntMetrics.parse(font(selection = 0x21))!!.italic)
        assertFalse(SfntMetrics.parse(font(selection = 0x40))!!.bold)
        assertFalse(SfntMetrics.parse(font(selection = 0x40))!!.italic)
    }

    @Test
    fun a_font_without_os2_falls_back_to_hhea() {
        val metrics = SfntMetrics.parse(font(os2 = false))!!

        assertNull(metrics.winAscent)
        assertNull(metrics.selection)
        assertFalse(metrics.useTypoMetrics)
        assertEquals(1950.0 / 2048.0, metrics.ascentEm, 1e-9)
        assertEquals(550.0 / 2048.0, metrics.descentEm, 1e-9)
        assertEquals(0.0, metrics.lineGapEm, 1e-9)
    }

    @Test
    fun a_cff_flavoured_font_is_read() {
        assertEquals(2048, SfntMetrics.parse(font(version = 0x4F54544F))!!.unitsPerEm)
    }

    @Test
    fun unreadable_bytes_are_rejected() {
        assertNull(SfntMetrics.parse(ByteArray(0)))
        assertNull(SfntMetrics.parse(font(version = 0x74727565)))
        assertNull(SfntMetrics.parse(font(unitsPerEm = 0)))
        assertNull(SfntMetrics.parse(font(head = false)))
        assertNull(SfntMetrics.parse(font(hhea = false)))
        assertNull(SfntMetrics.parse(font().copyOf(20)))
    }

    @Test
    fun a_table_past_the_end_of_the_file_is_ignored() {
        assertEquals(2048, SfntMetrics.parse(font(extraTableBeyondEof = true))!!.unitsPerEm)
    }

    @Test
    fun bundled_carlito_matches_its_published_metrics() {
        val metrics = SfntMetrics.parse(FONT.readBytes())!!

        assertEquals(2048, metrics.unitsPerEm)
        assertEquals(1950, metrics.winAscent)
        assertEquals(550, metrics.winDescent)
        assertEquals(452, metrics.typoLineGap)
        assertFalse(metrics.useTypoMetrics)
        assertFalse(metrics.bold)
        assertFalse(metrics.italic)
    }

    private fun font(
        version: Int = 0x00010000,
        unitsPerEm: Int = 2048,
        selection: Int = 0x40,
        os2: Boolean = true,
        head: Boolean = true,
        hhea: Boolean = true,
        extraTableBeyondEof: Boolean = false,
    ): ByteArray {
        val tables = linkedMapOf<String, ByteArray>()
        if (head) tables["head"] = ByteArray(HEAD_BYTES).apply { putU16(18, unitsPerEm) }
        if (hhea) {
            tables["hhea"] = ByteArray(HHEA_BYTES).apply {
                putI16(4, 1950)
                putI16(6, -550)
            }
        }
        if (os2) {
            tables["OS/2"] = ByteArray(OS2_BYTES).apply {
                putU16(62, selection)
                putI16(68, 1536)
                putI16(70, -512)
                putI16(72, 452)
                putU16(74, 1950)
                putU16(76, 550)
            }
        }
        tables["name"] = ByteArray(4)

        var cursor = align4(DIRECTORY_BYTES + RECORD_BYTES * (tables.size + if (extraTableBeyondEof) 1 else 0))
        val offsets = tables.keys.associateWith { tag ->
            val at = cursor
            cursor = align4(cursor + tables.getValue(tag).size)
            at
        }
        val bytes = ByteArray(cursor)
        bytes.putU32(0, version.toLong())
        bytes.putU16(4, tables.size + if (extraTableBeyondEof) 1 else 0)

        var record = DIRECTORY_BYTES
        for ((tag, data) in tables) {
            bytes.putTag(record, tag)
            bytes.putU32(record + 8, offsets.getValue(tag).toLong())
            bytes.putU32(record + 12, data.size.toLong())
            data.copyInto(bytes, offsets.getValue(tag))
            record += RECORD_BYTES
        }
        if (extraTableBeyondEof) {
            bytes.putTag(record, "junk")
            bytes.putU32(record + 8, bytes.size.toLong() + 8)
            bytes.putU32(record + 12, 4)
        }
        return bytes
    }

    private fun ByteArray.putU16(at: Int, value: Int) {
        this[at] = (value ushr 8).toByte()
        this[at + 1] = value.toByte()
    }

    private fun ByteArray.putI16(at: Int, value: Int) = putU16(at, value and 0xFFFF)

    private fun ByteArray.putU32(at: Int, value: Long) {
        putU16(at, (value ushr 16).toInt())
        putU16(at + 2, value.toInt())
    }

    private fun ByteArray.putTag(at: Int, tag: String) {
        tag.forEachIndexed { i, c -> this[at + i] = c.code.toByte() }
    }

    private fun align4(value: Int): Int = (value + 3) and 3.inv()

    private companion object {
        const val DIRECTORY_BYTES = 12
        const val RECORD_BYTES = 16
        const val HEAD_BYTES = 54
        const val HHEA_BYTES = 36
        const val OS2_BYTES = 78

        val FONT = File("src/main/assets/ooxml/fonts/humanist/PptxHumanist-Regular.ttf")
    }
}
