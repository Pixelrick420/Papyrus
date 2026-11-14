package io.github.pixelrick420.papyrus.viewer.pptx.render

import android.graphics.Bitmap
import android.graphics.Color
import io.github.pixelrick420.papyrus.viewer.Crop
import io.github.pixelrick420.papyrus.viewer.Preset
import io.github.pixelrick420.papyrus.viewer.Type
import io.github.pixelrick420.papyrus.viewer.pptx
import java.io.ByteArrayOutputStream

internal class GoldenDeck(val name: String, val bytes: ByteArray)

/**
 * The decks the raster goldens compare against. Every construct here is one M2 paints; text is present only
 * so its shapes and reading direction are exercised, because the text pass arrives in 024.
 */
internal object GoldenDecks {

    private const val W = 12_192_000L
    private const val H = 6_858_000L
    private const val M = 457_200L

    val all: List<GoldenDeck> = listOf(
        GoldenDeck("shape-zoo", shapeZoo()),
        GoldenDeck("gradients-linear", linearGradients()),
        GoldenDeck("gradients-radial", radialGradients()),
        GoldenDeck("pictures-crop", pictures()),
        GoldenDeck("groups-rotate-flip", groups()),
        GoldenDeck("master-layout", masterLayout()),
        GoldenDeck("dark-theme", darkTheme()),
        GoldenDeck("theme-colours", themeColours()),
        GoldenDeck("strokes-dashes", strokes()),
        GoldenDeck("backgrounds", backgrounds()),
        GoldenDeck("preset-adjusts", presetAdjusts()),
        GoldenDeck("text-frames", textFrames()),
    )

    private fun shapeZoo(): ByteArray = pptx {
        slide {
            background(rgb = "FFFFFF")
            val presets = listOf(
                "rect" to "4472C4",
                "roundRect" to "ED7D31",
                "ellipse" to "A5A5A5",
                "triangle" to "FFC000",
                "rightArrow" to "5B9BD5",
                "upArrow" to "70AD47",
                "chevron" to "264478",
                "star5" to "9E480E",
                "hexagon" to "636363",
                "diamond" to "997300",
                "pentagon" to "255E91",
                "plus" to "43682B",
            )
            val columns = 4
            val cellW = 2_700_000L
            val cellH = 1_900_000L
            presets.forEachIndexed { index, (preset, colour) ->
                val x = M + (index % columns) * cellW
                val y = 600_000L + (index / columns) * cellH
                shape(preset, x, y, cellW - 200_000, cellH - 200_000) {
                    fillRgb(colour)
                    line(rgb = "404040", widthEmu = 12_700)
                }
            }
        }
    }.toBytes()

    private fun linearGradients(): ByteArray = pptx {
        slide {
            background(rgb = "FFFFFF")
            shape(Preset.RECT, M, 700_000, 5_600_000, 2_300_000) {
                fillRaw(linear(0, "FF0000", "0000FF"))
            }
            shape(Preset.RECT, 6_300_000, 700_000, 5_400_000, 2_300_000) {
                fillRaw(linear(45 * 60_000, "00B050", "FFFF00"))
            }
            shape(Preset.RECT, M, 3_600_000, 5_600_000, 2_300_000) {
                fillRaw(linear(90 * 60_000, "7030A0", "FFC000", "00B0F0"))
            }
            shape(Preset.ROUND_RECT, 6_300_000, 3_600_000, 5_400_000, 2_300_000) {
                fillRaw(linear(180 * 60_000, "C00000", "FFFFFF"))
                line(rgb = "7F7F7F", widthEmu = 25_400)
            }
        }
    }.toBytes()

    private fun radialGradients(): ByteArray = pptx {
        slide {
            background(rgb = "F7F7F7")
            shape(Preset.ELLIPSE, M, 700_000, 3_000_000, 3_000_000) {
                fillRaw(radial("FFD966", "7F6000", l = 50_000, t = 50_000, r = 50_000, b = 50_000))
            }
            shape(Preset.ELLIPSE, 4_100_000, 700_000, 3_000_000, 3_000_000) {
                fillRaw(radial("FFFFFF", "1F4E79", l = 25_000, t = 75_000, r = 75_000, b = 25_000))
                line(rgb = "1F4E79", widthEmu = 19_050)
            }
            shape(Preset.RECT, 7_200_000, 700_000, 3_500_000, 3_000_000) {
                fillRaw(radial("7030A0", "00B0F0", l = 0, t = 0, r = 100_000, b = 100_000))
            }
            shape(Preset.RECT, M, 4_300_000, 5_400_000, 1_800_000) { fillRgb("1F4E79") }
            shape(Preset.RECT, 6_300_000, 4_300_000, 4_400_000, 1_800_000) { fillRgb("FFC000") }
        }
    }.toBytes()

    private fun pictures(): ByteArray {
        val png = quadrants()
        return pptx {
            slide {
                background(rgb = "FFFFFF")
                picture(png, M, 600_000, 4_200_000, 2_800_000)
                picture(png, 4_900_000, 600_000, 2_800_000, 2_800_000, crop = Crop(left = 25_000))
                picture(png, 8_000_000, 600_000, 2_800_000, 2_800_000, crop = Crop(top = 25_000, bottom = 25_000))
                picture(png, M, 3_800_000, 3_500_000, 1_800_000, crop = Crop(left = 10_000, right = 20_000))
                picture(
                    png,
                    5_500_000,
                    3_800_000,
                    5_400_000,
                    1_800_000,
                    crop = Crop(left = 5_000, top = 5_000, right = 5_000, bottom = 5_000),
                )
            }
        }.toBytes()
    }

    private fun groups(): ByteArray = pptx {
        slide {
            background(rgb = "FFFFFF")
            group(M, 700_000, 4_000_000, 3_000_000, name = "rotated") {
                rotate(30 * 60_000)
                shape(Preset.RECT, 0, 0, 2_000_000, 1_200_000) { fillRgb("4472C4") }
                shape(Preset.ELLIPSE, 2_200_000, 1_300_000, 1_600_000, 1_600_000) { fillRgb("ED7D31") }
            }
            group(5_500_000, 700_000, 4_000_000, 3_000_000, name = "flipped") {
                flip(horizontal = true)
                shape(Preset.RECT, 0, 0, 2_000_000, 1_200_000) { fillRgb("70AD47") }
                shape(Preset.TRIANGLE, 2_200_000, 1_300_000, 1_600_000, 1_600_000) { fillRgb("FFC000") }
            }
            group(2_300_000, 4_000_000, 7_600_000, 1_800_000, name = "flipped-vertically") {
                flip(vertical = true)
                shape(Preset.CHEVRON, 0, 0, 2_400_000, 1_200_000) { fillRgb("C00000") }
                shape(Preset.STAR5, 3_000_000, 200_000, 1_600_000, 1_600_000) { fillRgb("5B9BD5") }
                shape(Preset.RIGHT_ARROW, 5_200_000, 300_000, 2_200_000, 1_200_000) { fillRgb("A5A5A5") }
            }
        }
    }.toBytes()

    private fun masterLayout(): ByteArray = pptx {
        theme { accent1("1F4E79") }
        master {
            background(rgb = "EAEFF5")
            shape(Preset.RECT, 0, 0, W, 500_000) { fillRgb("1F4E79") }
            placeholder(
                Type.TITLE,
                idx = 0,
                x = M,
                y = 700_000,
                w = W - 2 * M,
                h = 900_000,
                decoration = { fillRgb("FFD966") },
            )
        }
        layout("Golden") {
            shape(Preset.RECT, M, 1_900_000, W - 2 * M, 2_600_000) { fillRgb("D9E2F3") }
            placeholder(
                Type.BODY,
                idx = 1,
                x = M,
                y = 2_000_000,
                w = W - 2 * M,
                h = 2_000_000,
                decoration = { fillRgb("B4C6E7") },
            )
        }
        slide {
            shape(Preset.ELLIPSE, M, 4_900_000, 2_400_000, 1_400_000) { fillRgb("ED7D31") }
            shape(Preset.ROUND_RECT, 3_400_000, 4_900_000, W - 3_400_000 - M, 1_400_000) {
                fillRgb("70AD47")
                line(rgb = "375623", widthEmu = 19_050)
            }
        }
    }.toBytes()

    private fun darkTheme(): ByteArray = pptx {
        theme {
            dk1("0B0F14")
            lt1("F5F7FA")
        }
        master {
            clrMap("bg1" to "dk1", "tx1" to "lt1")
            backgroundRef()
            shape(Preset.RECT, 0, 0, W, 400_000) { fillRgb("17212B") }
        }
        slide {
            shape(Preset.RECT, M, 800_000, 5_200_000, 2_300_000) { fill("lt1") }
            shape(Preset.RECT, 6_100_000, 800_000, 5_600_000, 2_300_000) { fillRgb("1F6FEB") }
            shape(Preset.ELLIPSE, M, 3_700_000, 3_600_000, 2_300_000) { fillRgb("F85149") }
            shape(Preset.CHEVRON, 5_000_000, 3_700_000, 6_700_000, 2_300_000) { fillRgb("3FB950") }
        }
    }.toBytes()

    private fun themeColours(): ByteArray = pptx {
        theme {
            accent1("003F5C")
            accent2("58508D")
            accent3("BC5090")
            accent4("FF6361")
            accent5("FFA600")
            accent6("2F9E44")
        }
        slide {
            background(rgb = "FFFFFF")
            val accents = listOf("accent1", "accent2", "accent3", "accent4", "accent5", "accent6")
            accents.forEachIndexed { index, accent ->
                val x = M + (index % 3) * 3_900_000
                val y = 900_000L + (index / 3) * 2_300_000
                shape(Preset.RECT, x, y, 3_300_000, 1_800_000) { fill(accent) }
            }
            shape(Preset.RECT, M, 5_500_000, 3_600_000, 900_000) {
                fillRaw("""<a:solidFill><a:schemeClr val="accent1"><a:tint val="60000"/></a:schemeClr></a:solidFill>""")
            }
            shape(Preset.RECT, 4_300_000, 5_500_000, 3_600_000, 900_000) {
                fillRaw("""<a:solidFill><a:schemeClr val="accent4"><a:shade val="75000"/></a:schemeClr></a:solidFill>""")
            }
            shape(Preset.RECT, 8_200_000, 5_500_000, 3_500_000, 900_000) {
                style(fillRef = 2, lineRef = 2, scheme = "accent5")
            }
        }
    }.toBytes()

    private fun strokes(): ByteArray = pptx {
        slide {
            background(rgb = "FFFFFF")
            val dashes = listOf("solid", "dash", "lgDash", "dot", "sysDot", "dashDot")
            dashes.forEachIndexed { index, dash ->
                val y = 700_000L + index * 900_000
                shape("line", M, y, 8_500_000, 0) {
                    line(rgb = "1F4E79", widthEmu = 12_700 + index * 12_700, dash = dash)
                }
            }
            shape(Preset.ROUND_RECT, 9_200_000, 700_000, 2_300_000, 2_300_000) {
                noFill()
                line(rgb = "C00000", widthEmu = 63_500, attributes = """ cap="rnd" cmpd="dbl"""")
            }
            shape(Preset.ELLIPSE, 9_200_000, 3_400_000, 2_300_000, 2_300_000) {
                noFill()
                line(rgb = "70AD47", widthEmu = 0)
            }
        }
    }.toBytes()

    private fun backgrounds(): ByteArray = pptx {
        master {
            backgroundRef(idx = 1002, scheme = "bg2")
        }
        slide {
            shape(Preset.RECT, M, 700_000, 5_600_000, 5_200_000) { fillRgb("FFFFFF") }
            shape(Preset.RECT, 6_300_000, 700_000, 5_400_000, 5_200_000) {
                fillRaw(linear(90 * 60_000, "FFFFFF", "4472C4"))
            }
        }
    }.toBytes()

    private fun presetAdjusts(): ByteArray = pptx {
        slide {
            background(rgb = "FFFFFF")
            shape(Preset.ROUND_RECT, M, 700_000, 3_600_000, 2_200_000) {
                adjust("adj", 0)
                fillRgb("4472C4")
            }
            shape(Preset.ROUND_RECT, 4_300_000, 700_000, 3_600_000, 2_200_000) {
                adjust("adj", 50_000)
                fillRgb("ED7D31")
            }
            shape(Preset.STAR5, 8_200_000, 700_000, 3_500_000, 2_200_000) {
                adjust("adj", 30_000)
                fillRgb("FFC000")
            }
            shape(Preset.CHEVRON, M, 3_600_000, 3_600_000, 2_200_000) {
                adjust("adj", 25_000)
                fillRgb("70AD47")
            }
            shape(Preset.RIGHT_ARROW, 4_300_000, 3_600_000, 3_600_000, 2_200_000) {
                adjust("adj1", 25_000)
                adjust("adj2", 60_000)
                fillRgb("5B9BD5")
            }
            shape("trapezoid", 8_200_000, 3_600_000, 3_500_000, 2_200_000) {
                adjust("adj", 40_000)
                fillRgb("C00000")
            }
        }
    }.toBytes()

    private fun textFrames(): ByteArray = pptx {
        slide {
            background(rgb = "FFFFFF")
            shape(Preset.RECT, M, M, W - 2 * M, 1_800_000) { fillRgb("1F4E79") }
            textBox(M, M, W - 2 * M, 1_800_000) {
                anchor("ctr")
                rtl()
                para("مرحبا بالعالم", 0) { sz(3200); color(rgb = "FFFFFF") }
            }
            textBox(M, 2_700_000, 4_500_000, 3_000_000) {
                vertical("vert270")
                para("Vertical", 0) { sz(2800); color(rgb = "1F4E79") }
            }
            shape(Preset.ELLIPSE, 5_400_000, 2_700_000, 3_000_000, 3_000_000) { fillRgb("FFC000") }
            shape(Preset.ROUND_RECT, 9_000_000, 2_700_000, 2_700_000, 3_000_000) {
                fillRgb("70AD47")
                line(rgb = "375623", widthEmu = 25_400)
            }
        }
    }.toBytes()

    private fun linear(angleSixtyThousandths: Int, vararg colours: String): String =
        """<a:gradFill rotWithShape="1"><a:gsLst>${
            stops(colours)
        }</a:gsLst><a:lin ang="$angleSixtyThousandths" scaled="0"/></a:gradFill>"""

    private fun radial(vararg colours: String, l: Int, t: Int, r: Int, b: Int): String =
        """<a:gradFill><a:gsLst>${stops(colours)}</a:gsLst>""" +
            """<a:path path="circle"><a:fillToRect l="$l" t="$t" r="$r" b="$b"/></a:path></a:gradFill>"""

    private fun stops(colours: Array<out String>): String = colours.mapIndexed { index, colour ->
        val position = if (colours.size == 1) 0 else index * 100_000 / (colours.size - 1)
        """<a:gs pos="$position"><a:srgbClr val="$colour"/></a:gs>"""
    }.joinToString("")

    // Four quadrants plus a grid, so a crop's edge is visible in the reference.
    private fun quadrants(): ByteArray {
        val bitmap = Bitmap.createBitmap(96, 64, Bitmap.Config.ARGB_8888)
        for (y in 0 until 64) {
            for (x in 0 until 96) {
                val colour = when {
                    x % 16 == 0 || y % 16 == 0 -> Color.WHITE
                    x < 48 && y < 32 -> Color.RED
                    x >= 48 && y < 32 -> Color.GREEN
                    x < 48 -> Color.BLUE
                    else -> Color.YELLOW
                }
                bitmap.setPixel(x, y, colour)
            }
        }
        return ByteArrayOutputStream().use { output ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
            output.toByteArray()
        }
    }
}
