package io.github.pixelrick420.papyrus.viewer.pptx

/** An 8x8 pattern tile; each row holds one bit per pixel, the high bit leftmost. */
internal class PatternTile(val rows: IntArray) {

    fun pixel(x: Int, y: Int): Boolean = rows[y] and (1 shl (SIZE - 1 - x)) != 0

    companion object {
        const val SIZE = 8
    }
}

/** The 48 DrawingML pattern presets, each an 8x8 bitmap the renderer tiles and tints. */
internal object PatternFills {

    fun tileOf(preset: String?): PatternTile? = PRESETS[preset]

    private fun generated(draw: (row: Int, column: Int) -> Boolean): PatternTile {
        val rows = IntArray(PatternTile.SIZE)
        for (row in 0 until PatternTile.SIZE) {
            for (column in 0 until PatternTile.SIZE) {
                if (draw(row, column)) rows[row] = rows[row] or (1 shl (PatternTile.SIZE - 1 - column))
            }
        }
        return PatternTile(rows)
    }

    private fun percent(value: Int): PatternTile = generated { row, column ->
        BAYER[row * PatternTile.SIZE + column] < (value * TOTAL + 50) / 100
    }

    private fun explicit(vararg lines: String): PatternTile = generated { row, column ->
        lines[row][column] == '#'
    }

    private const val TOTAL = PatternTile.SIZE * PatternTile.SIZE

    private val BAYER = intArrayOf(
        0, 32, 8, 40, 2, 34, 10, 42,
        48, 16, 56, 24, 50, 18, 58, 26,
        12, 44, 4, 36, 14, 46, 6, 38,
        60, 28, 52, 20, 62, 30, 54, 22,
        3, 35, 11, 43, 1, 33, 9, 41,
        51, 19, 59, 27, 49, 17, 57, 25,
        15, 47, 7, 39, 13, 45, 5, 37,
        63, 31, 55, 23, 61, 29, 53, 21,
    )

    private val PRESETS: Map<String, PatternTile> = linkedMapOf(
        "pct5" to percent(5),
        "pct10" to percent(10),
        "pct12_5" to percent(12),
        "pct20" to percent(20),
        "pct25" to percent(25),
        "pct30" to percent(30),
        "pct37_5" to percent(37),
        "pct40" to percent(40),
        "pct50" to percent(50),
        "pct60" to percent(60),
        "pct62_5" to percent(62),
        "pct70" to percent(70),
        "pct75" to percent(75),
        "pct80" to percent(80),
        "pct87_5" to percent(87),
        "pct90" to percent(90),
        "ltDnDiag" to generated { row, column -> (row - column + 8) % 8 == 0 },
        "ltUpDiag" to generated { row, column -> (row + column) % 8 == 0 },
        "dkDnDiag" to generated { row, column -> (row - column + 8) % 4 == 0 },
        "dkUpDiag" to generated { row, column -> (row + column) % 4 == 0 },
        "dnDiag" to generated { row, column -> (row - column + 8) % 4 == 2 },
        "upDiag" to generated { row, column -> (row + column) % 4 == 2 },
        "wdDnDiag" to generated { row, column -> (row - column + 8) % 3 == 0 },
        "wdUpDiag" to generated { row, column -> (row + column) % 3 == 0 },
        "cross" to generated { row, column ->
            (row % 4 == 2 && column % 4 in 1..3) || (column % 4 == 2 && row % 4 in 1..3)
        },
        "smCross" to generated { row, column ->
            (row % 2 == 1 && column % 2 == 0) || (column % 2 == 1 && row % 2 == 0)
        },
        "diagCross" to generated { row, column -> (row - column + 8) % 4 == 0 || (row + column) % 4 == 0 },
        "ltGrid" to generated { row, column -> row % 4 == 0 || column % 4 == 0 },
        "smGrid" to generated { row, column -> row % 2 == 0 || column % 2 == 0 },
        "grid" to generated { row, column -> row % 8 < 2 || column % 8 < 2 },
        "lgGrid" to generated { row, column -> row % 4 < 2 || column % 4 < 2 },
        "dot" to generated { row, column -> row % 4 == 2 && column % 4 == 2 },
        "dotGrid" to generated { row, column ->
            row % 4 == 0 || column % 4 == 0 || (row % 4 == 2 && column % 4 == 2)
        },
        "lgCheck" to generated { row, column -> (row / 4 + column / 4) % 2 == 0 },
        "smCheck" to generated { row, column -> (row / 2 + column / 2) % 2 == 0 },
        "check" to generated { row, column -> (row / 4 + column / 4) % 2 == 1 },
        "horizontal" to generated { row, _ -> row % 2 == 0 },
        "vertical" to generated { _, column -> column % 2 == 0 },
        "tri" to explicit(
            "#.......",
            "##......",
            "###.....",
            "####....",
            "#####...",
            "######..",
            "#######.",
            "########",
        ),
        "brick" to explicit(
            "########",
            "#...#...",
            "#...#...",
            "########",
            "...#...#",
            "...#...#",
            "########",
            "#...#...",
        ),
        "weave" to explicit(
            "###..###",
            "###..###",
            "...##...",
            "...##...",
            "###..###",
            "###..###",
            "...##...",
            "...##...",
        ),
        "plaid" to explicit(
            "##..##..",
            "##..##..",
            "........",
            "########",
            "########",
            "........",
            "##..##..",
            "##..##..",
        ),
        "divot" to explicit(
            "........",
            ".#....#.",
            "..#..#..",
            "...##...",
            "...##...",
            "..#..#..",
            ".#....#.",
            "........",
        ),
        "smConfetti" to explicit(
            "..#...#.",
            "....#...",
            ".#......",
            "......#.",
            "#.......",
            "...#....",
            ".....#..",
            ".#......",
        ),
        "lgConfetti" to explicit(
            "..##..#.",
            "....##..",
            ".##.....",
            "......##",
            "##......",
            "...##...",
            ".....##.",
            ".##.....",
        ),
        "pub10" to explicit(
            "#.......",
            "........",
            "....#...",
            "........",
            "..#.....",
            "........",
            "......#.",
            "........",
        ),
        "openDmnd" to explicit(
            "...##...",
            "..#..#..",
            ".#....#.",
            "#......#",
            ".#....#.",
            "..#..#..",
            "...##...",
            "........",
        ),
        "filledDmnd" to explicit(
            "...##...",
            "..####..",
            ".######.",
            "########",
            ".######.",
            "..####..",
            "...##...",
            "........",
        ),
    )
}
