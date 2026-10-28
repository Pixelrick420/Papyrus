package com.papyrus.app.ui.viewer

import com.papyrus.app.viewer.OfficeBlock
import com.papyrus.app.viewer.OfficeCell
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A page boundary is room above a block's item, so the block-to-item maps must not depend on it. */
class OfficePageUnitsTest {

    private fun table(rows: Int) = OfficeBlock.Table(List(rows) { listOf(OfficeCell("r$it")) })

    private fun blocks() = listOf<OfficeBlock>(
        OfficeBlock.Paragraph("first"),
        table(TABLE_BAND_ROWS * 2 + 5),
        OfficeBlock.Paragraph("middle"),
        table(3),
        OfficeBlock.Paragraph("last"),
    )

    @Test
    fun `the maps are total for a table at a page start, one at index 0 and one at the last block`() {
        for (list in listOf(blocks(), blocks().reversed(), listOf<OfficeBlock>(table(100)), listOf<OfficeBlock>(OfficeBlock.Paragraph("only")))) {
            val units = officeUnits(list)
            assertEquals(list.size, units.firstUnitOfBlock.size)
            assertEquals(units.size, units.blockOfUnit.size)
            list.indices.forEach { block ->
                val first = units.firstUnitOfBlock[block]
                assertEquals(block, units.blockOfUnit[first])
                assertEquals(block, units[first].block)
                assertTrue(first < units.size)
            }
            units.blockOfUnit.forEachIndexed { unit, block -> assertEquals(block, units[unit].block) }
        }
    }

    @Test
    fun `a table's first unit is its first band so a break cannot land mid table`() {
        val list = blocks()
        val units = officeUnits(list)
        val first = units.firstUnitOfBlock[1]
        assertEquals(0, units[first].rows!!.first)
        assertTrue(units.bandCounts[1] > 1)
        // The units after it belong to the same table until the next block begins.
        for (band in 0 until units.bandCounts[1]) assertEquals(1, units[first + band].block)
        assertEquals(first + units.bandCounts[1], units.firstUnitOfBlock[2])
    }

    @Test
    fun `page starts are not an input to the units so they cannot change them`() {
        // officeUnits takes the blocks and nothing else: a page start is applied where an item is drawn.
        val list = blocks()
        val before = officeUnits(list)
        val after = officeUnits(list.toList())
        assertEquals(before.size, after.size)
        assertEquals(before.firstUnitOfBlock.toList(), after.firstUnitOfBlock.toList())
        assertEquals(before.blockOfUnit.toList(), after.blockOfUnit.toList())
    }

    @Test
    fun `an empty table still owns a unit`() {
        val units = officeUnits(listOf(OfficeBlock.Table(emptyList()), OfficeBlock.Paragraph("after")))
        assertEquals(2, units.size)
        assertEquals(listOf(0, 1), units.firstUnitOfBlock.toList())
    }

    @Test
    fun `a stored position still splits and joins across bands`() {
        val units = officeUnits(blocks())
        val (unit, within) = units.splitAcrossBands(1, 0.5f)
        val (block, fraction) = units.joinBands(unit, within)
        assertEquals(1, block)
        assertEquals(0.5f, fraction, 0.0001f)
    }
}
