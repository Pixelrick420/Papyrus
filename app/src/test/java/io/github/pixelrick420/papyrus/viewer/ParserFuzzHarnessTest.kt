package io.github.pixelrick420.papyrus.viewer

import io.github.pixelrick420.papyrus.viewer.pptx.CustomGeometry
import io.github.pixelrick420.papyrus.viewer.pptx.Guide
import io.github.pixelrick420.papyrus.viewer.pptx.OoxmlReader
import io.github.pixelrick420.papyrus.viewer.pptx.PathBuilder
import io.github.pixelrick420.papyrus.viewer.pptx.PptxLimits
import io.github.pixelrick420.papyrus.viewer.pptx.PptxPackage
import io.github.pixelrick420.papyrus.viewer.pptx.PresetCommand
import io.github.pixelrick420.papyrus.viewer.pptx.PresetFillMode
import io.github.pixelrick420.papyrus.viewer.pptx.PresetPath
import io.github.pixelrick420.papyrus.viewer.pptx.PresetShape
import io.github.pixelrick420.papyrus.viewer.pptx.PresetTextRect
import io.github.pixelrick420.papyrus.viewer.pptx.text.AutoNumbers
import io.github.pixelrick420.papyrus.viewer.pptx.text.Bidi
import io.github.pixelrick420.papyrus.viewer.pptx.text.BreakProvider
import io.github.pixelrick420.papyrus.viewer.pptx.text.LineBreaker
import io.github.pixelrick420.papyrus.viewer.pptx.text.TextParagraph
import io.github.pixelrick420.papyrus.viewer.pptx.text.TextParagraphProperties
import io.github.pixelrick420.papyrus.viewer.pptx.text.TextRun
import io.github.pixelrick420.papyrus.viewer.pptx.text.TextRunProperties
import io.github.pixelrick420.papyrus.viewer.pptx.text.TextShaper
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.xmlpull.v1.XmlPullParserException
import java.io.IOException
import java.util.Locale
import kotlin.random.Random

class ParserFuzzHarnessTest {

    private val limits = PptxLimits(
        maxEntries = 40,
        maxXmlPartBytes = 64 * 1024,
        maxXmlBytes = 512 * 1024,
        maxXmlElements = 2_000,
        maxXmlDepth = 40,
        maxShapes = 200,
        maxGuideEvaluations = 128,
        maxPaths = 16,
        maxPathCommands = 256,
    )

    private fun fuzz(body: (Int) -> Unit) {
        val deadline = if (DURATION_MILLIS > 0L) {
            System.nanoTime() + DURATION_MILLIS * 1_000_000L
        } else {
            Long.MAX_VALUE
        }
        var index = 0
        while (index < CASES_PER_ROUND * ROUNDS && System.nanoTime() < deadline) {
            body(index++)
        }
    }

    private fun survives(label: String, read: () -> Unit) {
        val started = System.nanoTime()
        val outcome = runCatching(read)
        val elapsed = System.nanoTime() - started
        assertTrue("$label took ${elapsed / 1_000_000} ms", elapsed < 2_000_000_000L)
        val thrown = outcome.exceptionOrNull()
        if (thrown != null && thrown !is IOException && thrown !is XmlPullParserException) {
            fail("$label threw ${thrown::class.java.name}: ${thrown.message}")
        }
    }

    private fun seedDeck(): ByteArray = pptx {
        layout("Blank")
        slide {
            title("Hello")
            shape(Preset.RECT, 0, 0, 100, 100)
        }
    }.toBytes()

    @Test
    fun `mutated ZIP archives are opened or refused without crashing`() {
        val seed = seedDeck()
        val random = Random(SEED)
        fuzz { index ->
            val mutated = mutate(random, seed)
            survives("zip case $index") {
                PptxPackage.read(OfficeFixtures.streamFactory(mutated), null, limits, OfficeFixtures::parser)
            }
        }
    }

    private fun mutate(random: Random, seed: ByteArray): ByteArray {
        val bytes = seed.copyOf()
        repeat(1 + random.nextInt(6)) {
            when (random.nextInt(4)) {
                0 -> bytes[random.nextInt(bytes.size)] = random.nextInt(256).toByte()
                1 -> return bytes.copyOf(random.nextInt(bytes.size + 1))
                2 -> bytes[random.nextInt(bytes.size)] = 0
                else -> bytes[random.nextInt(bytes.size)] = bytes[random.nextInt(bytes.size)]
            }
        }
        return bytes
    }

    @Test
    fun `random XML is read or refused without crashing`() {
        val random = Random(SEED + 1)
        fuzz { index ->
            val xml = randomXml(random).toByteArray()
            survives("xml case $index") {
                OoxmlReader(OfficeFixtures::parser, limits = limits).readTree(xml.inputStream(), "part")
            }
        }
    }

    private fun randomXml(random: Random): String {
        val tags = listOf(
            "a", "p:sld", "p:sp", "p:grpSp", "a:custGeom", "a:gd",
            "mc:AlternateContent", "mc:Choice", "mc:Fallback",
        )
        val attrs = listOf("name", "fmla", "Requires", "mc:Ignorable", "x", "cx", "w", "h", "prst")
        val values = listOf("", "0", "1", "NaN", "1e400", "-1", "a", "ss", "p14 x14ac", "true", "&#0;", "\"")
        return buildString {
            append("""<p:sld xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main" """)
            append("""xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" """)
            append("""xmlns:mc="http://schemas.openxmlformats.org/markup-compatibility/2006">""")
            repeat(random.nextInt(12)) {
                val tag = tags[random.nextInt(tags.size)]
                append("<").append(tag)
                repeat(random.nextInt(4)) {
                    append(" ").append(attrs[random.nextInt(attrs.size)])
                    append("=\"").append(values[random.nextInt(values.size)]).append("\"")
                }
                if (random.nextBoolean()) append("/>") else {
                    append(">text \u0001 &#0;</").append(tag).append(">")
                }
            }
            append("</p:sld>")
        }
    }

    @Test
    fun `random geometry and guide formulas are bounded and never crash`() {
        val random = Random(SEED + 2)
        fuzz { index ->
            val shape = randomShape(random)
            survives("geometry case $index") {
                PathBuilder(limits).build(shape, randomExtent(random), randomExtent(random), randomAdjusts(random))
                CustomGeometry.read(randomProperties(random), limits)
            }
        }
    }

    private fun randomExtent(random: Random): Double = when (random.nextInt(8)) {
        0 -> 0.0
        1 -> Double.NaN
        2 -> Double.POSITIVE_INFINITY
        3 -> Double.NEGATIVE_INFINITY
        else -> random.nextDouble(-1e12, 1e12)
    }

    private fun randomFormula(random: Random): String =
        (listOf(FORMULA_OPS[random.nextInt(FORMULA_OPS.size)]) +
            List(random.nextInt(5)) { OPERANDS[random.nextInt(OPERANDS.size)] })
            .joinToString(" ")

    private fun randomGuides(random: Random): List<Guide> =
        List(random.nextInt(10)) { Guide("g$it", randomFormula(random)) }

    private fun randomAdjusts(random: Random): Map<String, String> =
        (0 until random.nextInt(5)).associate { "g$it" to randomFormula(random) }

    private fun randomShape(random: Random): PresetShape = PresetShape(
        name = "fuzz",
        adjusts = randomGuides(random),
        guides = randomGuides(random),
        paths = List(random.nextInt(4)) {
            PresetPath(
                width = randomExtent(random).takeIf { it > 0 },
                height = randomExtent(random).takeIf { it > 0 },
                fill = PresetFillMode.NORM,
                stroke = true,
                commands = List(random.nextInt(12)) { randomCommand(random) },
            )
        },
        textRect = PresetTextRect("l", "t", "r", "b"),
    )

    private fun randomCommand(random: Random): PresetCommand {
        val token = { OPERANDS[random.nextInt(OPERANDS.size)] }
        return when (random.nextInt(6)) {
            0 -> PresetCommand.MoveTo(token(), token())
            1 -> PresetCommand.LineTo(token(), token())
            2 -> PresetCommand.QuadTo(token(), token(), token(), token())
            3 -> PresetCommand.CubicTo(token(), token(), token(), token(), token(), token())
            4 -> PresetCommand.ArcTo(token(), token(), token(), token())
            else -> PresetCommand.Close
        }
    }

    private fun randomProperties(random: Random) = OoxmlReader(OfficeFixtures::parser, limits = limits)
        .readTree(randomCustGeom(random).toByteArray().inputStream(), "part")

    private fun randomCustGeom(random: Random): String {
        val numbers = listOf("0", "1e400", "NaN", "-1", "Infinity", "ss")
        val commands = listOf("moveTo", "lnTo", "quadBezTo", "cubicBezTo", "arcTo", "close")
        return buildString {
            append("""<a:spPr xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main">""")
            append("<a:custGeom><a:avLst>")
            repeat(random.nextInt(4)) {
                append("""<a:gd name="g$it" fmla="val ${numbers[random.nextInt(numbers.size)]}"/>""")
            }
            append("</a:avLst><a:gdLst>")
            repeat(random.nextInt(4)) {
                append("""<a:gd name="h$it" fmla="val ${numbers[random.nextInt(numbers.size)]}"/>""")
            }
            append("</a:gdLst><a:pathLst>")
            repeat(random.nextInt(3)) {
                append("""<a:path w="${numbers[random.nextInt(numbers.size)]}" h="${numbers[random.nextInt(numbers.size)]}">""")
                repeat(random.nextInt(8)) {
                    val command = commands[random.nextInt(commands.size)]
                    append("<a:").append(command)
                    if (command == "arcTo") {
                        append(""" wR="${numbers[random.nextInt(numbers.size)]}" hR="1" stAng="0" swAng="1"/>""")
                    } else {
                        append("><a:pt x=\"0\" y=\"0\"/></a:").append(command).append(">")
                    }
                }
                append("</a:path>")
            }
            append("</a:pathLst></a:custGeom></a:spPr>")
        }
    }

    @Test
    fun `random text is laid out and numbered without crashing`() {
        val random = Random(SEED + 3)
        val alphabet = ("abc XYZ 0123 .,!? \t\n\u0000\u0001\u2028\u00A0" +
            "あ。い「」・ア أ ע क \uD800\uDC00 \uDC00\uD800 \uD83D\uDE00").toCharArray()
        val shaper = TextShaper()
        val numbers = AutoNumbers()
        val breaker = LineBreaker(BreakProvider { text, _ ->
            val found = ArrayList<Int>()
            repeat(text.length) { if (random.nextBoolean()) found += it }
            found
        })
        fuzz { index ->
            val text = buildString(random.nextInt(24)) {
                repeat(random.nextInt(40)) { append(alphabet[random.nextInt(alphabet.size)]) }
            }
            val value = listOf(Int.MIN_VALUE, Int.MAX_VALUE, -1, 0, 1, 9, 15, 3999, 4000, random.nextInt()).random(random)
            survives("text case $index") {
                Bidi.runs(text, random.nextBoolean())
                val paragraph = TextParagraph(runs = listOf(TextRun.Text(text, TextRunProperties())))
                val flat = shaper.flatten(paragraph)
                shaper.shape(flat, random.nextBoolean())
                breaker.breakBefore(text, TextParagraphProperties(), Locale.ROOT)
                numbers.format(SCHEMES[random.nextInt(SCHEMES.size)], value)
            }
        }
    }

    private companion object {
        const val SEED = 20_261_119
        const val CASES_PER_ROUND = 2_000
        val ROUNDS: Int = System.getenv("PAPYRUS_FUZZ_ROUNDS")?.toIntOrNull()
            ?: System.getProperty("papyrus.fuzz.rounds")?.toIntOrNull()
            ?: 1
        val DURATION_MILLIS: Long = System.getenv("PAPYRUS_FUZZ_MILLIS")?.toLongOrNull() ?: 0L
        val FORMULA_OPS = listOf("val", "abs", "sqrt", "min", "max", "+-", "*/", "+/", "?:", "pin", "mod", "sin", "cos", "tan", "at2", "cat2", "sat2", "bogus")
        val OPERANDS = listOf("0", "-0", "1", "-1", "1e400", "-1e400", "NaN", "Infinity", "1e-400", "ss", "w", "h", "cd4", "a")
        val SCHEMES = listOf("decimal", "decimalZero", "alphaLc", "alphaUc", "romanLc", "romanUc", "circleNumWdBlackPlain", "decimalEnclosedParen", "arabic1", "hindiNum", "thaiAlpha", "hebrew2Minus", "chineseCountingThousand", "ideographZodiac", "koreanDigital", "none")
    }
}
