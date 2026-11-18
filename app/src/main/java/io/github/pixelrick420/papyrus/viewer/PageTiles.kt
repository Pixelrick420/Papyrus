package io.github.pixelrick420.papyrus.viewer

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.sqrt

data class PageTile(val col: Int, val row: Int, val rect: NormRect)

data class TileGrid(val columns: Int, val rows: Int) {

    fun covering(visible: NormRect): List<PageTile> {
        val left = visible.left.coerceIn(0f, 1f)
        val top = visible.top.coerceIn(0f, 1f)
        val right = visible.right.coerceIn(0f, 1f)
        val bottom = visible.bottom.coerceIn(0f, 1f)
        if (right <= left || bottom <= top || columns <= 0 || rows <= 0) return emptyList()
        val firstCol = floor(left * columns).toInt().coerceIn(0, columns - 1)
        val lastCol = ceil(right * columns).toInt().coerceIn(firstCol + 1, columns)
        val firstRow = floor(top * rows).toInt().coerceIn(0, rows - 1)
        val lastRow = ceil(bottom * rows).toInt().coerceIn(firstRow + 1, rows)
        return (firstCol until lastCol).flatMap { col ->
            (firstRow until lastRow).map { row ->
                PageTile(
                    col = col,
                    row = row,
                    rect = NormRect(
                        left = col.toFloat() / columns,
                        top = row.toFloat() / rows,
                        right = (col + 1).toFloat() / columns,
                        bottom = (row + 1).toFloat() / rows,
                    ),
                )
            }
        }
    }

    companion object {

        fun forPage(
            widthPx: Int,
            heightPx: Int,
            maxPixels: Int,
            tilesPerView: Int = TILES_PER_VIEW,
        ): TileGrid? {
            if (widthPx <= 0 || heightPx <= 0 || maxPixels <= 0 || tilesPerView <= 0) return null
            val edgePx = sqrt(maxPixels.toDouble() / tilesPerView).coerceAtLeast(1.0)
            return TileGrid(
                columns = ceil(widthPx / edgePx).toInt().coerceAtLeast(1),
                rows = ceil(heightPx / edgePx).toInt().coerceAtLeast(1),
            )
        }
    }
}

private const val TILES_PER_VIEW = 4
