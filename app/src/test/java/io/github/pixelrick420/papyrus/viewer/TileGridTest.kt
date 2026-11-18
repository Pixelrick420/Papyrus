package io.github.pixelrick420.papyrus.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.ceil

class TileGridTest {

    @Test
    fun `tiles stay within the shared pixel budget`() {
        val grid = TileGrid.forPage(widthPx = 8000, heightPx = 12000, maxPixels = 1_000_000)!!
        val tileWidth = ceil(8000.0 / grid.columns).toInt()
        val tileHeight = ceil(12000.0 / grid.rows).toInt()

        assertTrue(tileWidth * tileHeight <= 250_000)
        assertTrue(grid.columns * grid.rows > 1)
    }

    @Test
    fun `only tiles the visible rectangle touches are requested`() {
        val tiles = TileGrid(4, 5).covering(NormRect(0.26f, 0.1f, 0.75f, 0.6f))

        assertEquals(
            setOf(1, 2).flatMap { col -> (0..2).map { row -> col to row } }.toSet(),
            tiles.map { it.col to it.row }.toSet(),
        )
        assertEquals(6, tiles.size)
        assertTrue(tiles.all { it.rect.left >= 0.25f && it.rect.right <= 0.75f })
    }

    @Test
    fun `an empty view or a dead budget yields no tiles`() {
        assertEquals(emptyList<PageTile>(), TileGrid(4, 5).covering(NormRect(0.5f, 0.5f, 0.5f, 0.5f)))
        assertEquals(null, TileGrid.forPage(100, 100, 0))
    }
}
