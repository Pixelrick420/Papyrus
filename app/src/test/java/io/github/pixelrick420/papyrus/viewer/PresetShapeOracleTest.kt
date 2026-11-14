package io.github.pixelrick420.papyrus.viewer

import io.github.pixelrick420.papyrus.viewer.pptx.GuideEvaluator
import io.github.pixelrick420.papyrus.viewer.pptx.PathBuilder
import io.github.pixelrick420.papyrus.viewer.pptx.PresetGeometry
import io.github.pixelrick420.papyrus.viewer.pptx.PresetOutline
import io.github.pixelrick420.papyrus.viewer.pptx.PresetPath
import io.github.pixelrick420.papyrus.viewer.pptx.PresetSegment
import io.github.pixelrick420.papyrus.viewer.pptx.PresetShape
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.kxml2.io.KXmlParser
import java.io.File
import kotlin.math.abs

class PresetShapeOracleTest {

    private val geometry = PresetGeometry(
        open = { File("src/main/assets/ooxml/presetShapeDefinitions.xml").inputStream() },
        newParserFactory = { KXmlParser() },
    )

    private val builder = PathBuilder()

    @Test
    fun `every preset matches the POI oracle`() {
        val root = Json(File("../tools/pptx/poi-oracle/oracle.json").readText()).parse() as Map<*, *>
        val tolerance = (root["tolerance"] as Number).toDouble()
        val cases = root["cases"] as List<*>
        var compared = 0
        var nonFinite = 0

        for (raw in cases) {
            val case = raw as Map<*, *>
            val name = case["shape"] as String
            val width = (case["width"] as Number).toDouble()
            val height = (case["height"] as Number).toDouble()
            val key = "$name|${case["width"]}x${case["height"]}|${case["adjust"]}"
            val shape = requireNotNull(geometry.shape(name)) { "missing preset $name" }
            val adjusts =
                if (case["adjust"] == "extreme") extremes(shape, width, height) else emptyMap()
            val outline = builder.build(shape, width, height, adjusts)

            if (case["nonFinite"] == true) {
                assertNull("$key should have no outline", outline)
                nonFinite++
                continue
            }

            assertNotNull("$key should have an outline", outline)
            compared++
            compare(key, outline!!, case["paths"] as List<*>, shape, width, height, tolerance)
        }

        assertEquals(1122, cases.size)
        assertEquals(1104, compared)
        assertEquals(18, nonFinite)
    }

    private fun extremes(shape: PresetShape, width: Double, height: Double): Map<String, String> {
        val defaults = GuideEvaluator(width, height).evaluate(shape.adjusts)
        return shape.adjusts.mapNotNull { guide ->
            val value = defaults[guide.name] ?: return@mapNotNull null
            if (value == 0.0) null else guide.name to "val ${value * 2.0}"
        }.toMap()
    }

    private fun compare(
        key: String,
        outline: PresetOutline,
        expectedPaths: List<*>,
        shape: PresetShape,
        width: Double,
        height: Double,
        tolerance: Double,
    ) {
        assertEquals("$key path count", expectedPaths.size, outline.paths.size)
        for (index in expectedPaths.indices) {
            val path = shape.paths[index]
            val expected = expectedPaths[index] as List<*>
            val actual = subpaths(outline.paths[index].segments)
            assertEquals("$key path $index subpath count", expected.size, actual.size)
            for (sub in expected.indices) {
                compareSubpath(
                    "$key path $index subpath $sub",
                    actual[sub],
                    expected[sub] as Map<*, *>,
                    path,
                    width,
                    height,
                    tolerance,
                )
            }
        }
    }

    @Suppress("LongParameterList")
    private fun compareSubpath(
        key: String,
        actual: List<PresetSegment>,
        expected: Map<*, *>,
        path: PresetPath,
        width: Double,
        height: Double,
        tolerance: Double,
    ) {
        val scaleX = if (path.width != null && path.width > 0) width / path.width else 1.0
        val scaleY = if (path.height != null && path.height > 0) height / path.height else 1.0
        val segments = expected["segments"] as List<*>
        assertEquals("$key segment count", segments.size, actual.size)

        var expectedX = 0.0
        var expectedY = 0.0
        var actualX = 0.0
        var actualY = 0.0

        for (index in segments.indices) {
            val raw = segments[index] as List<*>
            val code = raw[0] as String
            assertEquals("$key segment $index type", code, actual[index].code())
            when (code) {
                "M", "L" -> {
                    expectedX = (raw[1] as Number).toDouble() * scaleX
                    expectedY = (raw[2] as Number).toDouble() * scaleY
                    val (x, y) = actual[index].end()
                    assertClose("$key segment $index x", expectedX, x, tolerance)
                    assertClose("$key segment $index y", expectedY, y, tolerance)
                    actualX = x
                    actualY = y
                }
                "Q" -> {
                    val quad = actual[index] as PresetSegment.QuadTo
                    val expectedPoints = listOf(
                        expectedX to expectedY,
                        (raw[1] as Number).toDouble() * scaleX to (raw[2] as Number).toDouble() * scaleY,
                        (raw[3] as Number).toDouble() * scaleX to (raw[4] as Number).toDouble() * scaleY,
                    )
                    val actualPoints =
                        listOf(actualX to actualY, quad.controlX to quad.controlY, quad.x to quad.y)
                    sample(key, index, expectedPoints, actualPoints, tolerance)
                    expectedX = expectedPoints[2].first
                    expectedY = expectedPoints[2].second
                    actualX = quad.x
                    actualY = quad.y
                }
                "C" -> {
                    val cubic = actual[index] as PresetSegment.CubicTo
                    val expectedPoints = listOf(
                        expectedX to expectedY,
                        (raw[1] as Number).toDouble() * scaleX to (raw[2] as Number).toDouble() * scaleY,
                        (raw[3] as Number).toDouble() * scaleX to (raw[4] as Number).toDouble() * scaleY,
                        (raw[5] as Number).toDouble() * scaleX to (raw[6] as Number).toDouble() * scaleY,
                    )
                    val actualPoints = listOf(
                        actualX to actualY,
                        cubic.control1X to cubic.control1Y,
                        cubic.control2X to cubic.control2Y,
                        cubic.x to cubic.y,
                    )
                    sample(key, index, expectedPoints, actualPoints, tolerance)
                    expectedX = expectedPoints[3].first
                    expectedY = expectedPoints[3].second
                    actualX = cubic.x
                    actualY = cubic.y
                }
                "Z" -> Unit
                else -> throw IllegalArgumentException("$key segment $index: unknown $code")
            }
        }
    }

    private fun sample(
        key: String,
        index: Int,
        expected: List<Pair<Double, Double>>,
        actual: List<Pair<Double, Double>>,
        tolerance: Double,
    ) {
        for (step in 1..SAMPLES) {
            val t = step.toDouble() / SAMPLES
            val expectedPoint = bezier(expected, t)
            val actualPoint = bezier(actual, t)
            assertClose("$key segment $index t=$t x", expectedPoint.first, actualPoint.first, tolerance)
            assertClose("$key segment $index t=$t y", expectedPoint.second, actualPoint.second, tolerance)
        }
    }

    private fun assertClose(message: String, expected: Double, actual: Double, tolerance: Double) {
        assertTrue("$message: expected $expected but was $actual", abs(expected - actual) <= tolerance)
    }

    private fun bezier(points: List<Pair<Double, Double>>, t: Double): Pair<Double, Double> {
        var current = points
        while (current.size > 1) {
            current = current.zipWithNext { from, to ->
                (from.first + (to.first - from.first) * t) to
                    (from.second + (to.second - from.second) * t)
            }
        }
        return current.single()
    }

    private fun subpaths(segments: List<PresetSegment>): List<List<PresetSegment>> {
        val subpaths = mutableListOf<MutableList<PresetSegment>>()
        for (segment in segments) {
            if (segment is PresetSegment.MoveTo || subpaths.isEmpty()) {
                subpaths += mutableListOf(segment)
            } else {
                subpaths.last() += segment
            }
        }
        return subpaths
    }

    private fun PresetSegment.code(): String = when (this) {
        is PresetSegment.MoveTo -> "M"
        is PresetSegment.LineTo -> "L"
        is PresetSegment.QuadTo -> "Q"
        is PresetSegment.CubicTo -> "C"
        PresetSegment.Close -> "Z"
    }

    private fun PresetSegment.end(): Pair<Double, Double> = when (this) {
        is PresetSegment.MoveTo -> x to y
        is PresetSegment.LineTo -> x to y
        is PresetSegment.QuadTo -> x to y
        is PresetSegment.CubicTo -> x to y
        PresetSegment.Close -> error("a close has no endpoint")
    }

    private companion object {
        const val SAMPLES = 16
    }
}

/** Just enough JSON for the oracle file: objects, arrays, strings, numbers, booleans and null. */
private class Json(private val text: String) {

    private var index = 0

    fun parse(): Any? {
        val value = value()
        skip()
        require(index == text.length) { "trailing input at $index" }
        return value
    }

    private fun value(): Any? {
        skip()
        return when (text[index]) {
            '{' -> obj()
            '[' -> array()
            '"' -> string()
            't' -> literal("true", true)
            'f' -> literal("false", false)
            'n' -> literal("null", null)
            else -> number()
        }
    }

    private fun obj(): Map<String, Any?> {
        index++
        val map = LinkedHashMap<String, Any?>()
        skip()
        if (text[index] == '}') {
            index++
            return map
        }
        while (true) {
            skip()
            val key = string()
            skip()
            expect(':')
            map[key] = value()
            skip()
            if (text[index] == ',') index++ else {
                expect('}')
                return map
            }
        }
    }

    private fun array(): List<Any?> {
        index++
        val list = mutableListOf<Any?>()
        skip()
        if (text[index] == ']') {
            index++
            return list
        }
        while (true) {
            list += value()
            skip()
            if (text[index] == ',') index++ else {
                expect(']')
                return list
            }
        }
    }

    private fun string(): String {
        expect('"')
        val builder = StringBuilder()
        while (true) {
            val char = text[index++]
            when (char) {
                '"' -> return builder.toString()
                '\\' -> builder.append(escape())
                else -> builder.append(char)
            }
        }
    }

    private fun escape(): Char {
        val char = text[index++]
        return when (char) {
            'n' -> '\n'
            't' -> '\t'
            'r' -> '\r'
            'b' -> '\b'
            'f' -> '\u000C'
            'u' -> {
                index += 4
                text.substring(index - 4, index).toInt(16).toChar()
            }
            else -> char
        }
    }

    private fun number(): Double {
        val start = index
        while (index < text.length && text[index] in "+-0123456789.eE") index++
        return text.substring(start, index).toDouble()
    }

    private fun literal(token: String, value: Any?): Any? {
        require(text.startsWith(token, index)) { "expected $token at $index" }
        index += token.length
        return value
    }

    private fun expect(char: Char) {
        require(text[index] == char) { "expected $char at $index" }
        index++
    }

    private fun skip() {
        while (index < text.length && text[index].isWhitespace()) index++
    }
}
