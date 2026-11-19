package io.github.pixelrick420.papyrus.viewer

import io.github.pixelrick420.papyrus.viewer.pptx.Guide
import io.github.pixelrick420.papyrus.viewer.pptx.GuideEvaluator
import io.github.pixelrick420.papyrus.viewer.pptx.PptxLimits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class GuideEvaluatorTest {

    private val evaluator = GuideEvaluator(width = 100.0, height = 50.0)

    @Test
    fun `val returns its operand`() {
        assertEquals(42.0, value("val 42"), TOLERANCE)
    }

    @Test
    fun `plus minus subtracts the third operand`() {
        assertEquals(12.0, value("+- 10 5 3"), TOLERANCE)
    }

    @Test
    fun `multiply divide multiplies then divides`() {
        assertEquals(1.5, value("*/ 2 3 4"), TOLERANCE)
    }

    @Test
    fun `plus divide adds then divides`() {
        assertEquals(3.0, value("+/ 2 4 2"), TOLERANCE)
    }

    @Test
    fun `conditional picks the second operand when the first is positive`() {
        assertEquals(2.0, value("?: 1 2 3"), TOLERANCE)
        assertEquals(3.0, value("?: 0 2 3"), TOLERANCE)
        assertEquals(3.0, value("?: -1 2 3"), TOLERANCE)
    }

    @Test
    fun `abs takes the absolute value`() {
        assertEquals(5.0, value("abs -5"), TOLERANCE)
    }

    @Test
    fun `sqrt takes the square root`() {
        assertEquals(3.0, value("sqrt 9"), TOLERANCE)
    }

    @Test
    fun `min and max pick a bound`() {
        assertEquals(2.0, value("min 4 2"), TOLERANCE)
        assertEquals(4.0, value("max 4 2"), TOLERANCE)
    }

    @Test
    fun `pin clamps the middle operand to its bounds`() {
        assertEquals(5.0, value("pin 0 5 10"), TOLERANCE)
        assertEquals(0.0, value("pin 0 -5 10"), TOLERANCE)
        assertEquals(10.0, value("pin 0 15 10"), TOLERANCE)
        assertEquals(5.0, value("pin 10 5 0"), TOLERANCE)
    }

    @Test
    fun `mod is the three dimensional magnitude`() {
        assertEquals(13.0, value("mod 3 4 12"), TOLERANCE)
    }

    @Test
    fun `sin cos and tan scale the first operand by an angle in 60000ths of a degree`() {
        assertEquals(2.0, value("sin 2 5400000"), TOLERANCE)
        assertEquals(-2.0, value("cos 2 10800000"), TOLERANCE)
        assertEquals(1.0, value("tan 1 2700000"), TOLERANCE)
    }

    @Test
    fun `at2 returns an angle in 60000ths of a degree`() {
        assertEquals(5_400_000.0, value("at2 0 1"), TOLERANCE)
        assertEquals(0.0, value("at2 1 0"), TOLERANCE)
        assertEquals(10_800_000.0, value("at2 -1 0"), TOLERANCE)
    }

    @Test
    fun `cat2 and sat2 project the first operand`() {
        assertEquals(0.0, value("cat2 2 0 1"), TOLERANCE)
        assertEquals(2.0, value("sat2 2 0 1"), TOLERANCE)
        assertEquals(2.0, value("cat2 2 1 0"), TOLERANCE)
        assertEquals(0.0, value("sat2 2 1 0"), TOLERANCE)
    }

    @Test
    fun `builtins expose the shape's edges and centre`() {
        assertEquals(0.0, evaluator.builtins.getValue("l"), TOLERANCE)
        assertEquals(0.0, evaluator.builtins.getValue("t"), TOLERANCE)
        assertEquals(100.0, evaluator.builtins.getValue("r"), TOLERANCE)
        assertEquals(50.0, evaluator.builtins.getValue("b"), TOLERANCE)
        assertEquals(100.0, evaluator.builtins.getValue("w"), TOLERANCE)
        assertEquals(50.0, evaluator.builtins.getValue("h"), TOLERANCE)
        assertEquals(50.0, evaluator.builtins.getValue("hc"), TOLERANCE)
        assertEquals(25.0, evaluator.builtins.getValue("vc"), TOLERANCE)
        assertEquals(50.0, evaluator.builtins.getValue("ss"), TOLERANCE)
        assertEquals(100.0, evaluator.builtins.getValue("ls"), TOLERANCE)
    }

    @Test
    fun `builtins expose the angle and unit fractions`() {
        assertEquals(21_600_000.0, evaluator.builtins.getValue("cd"), TOLERANCE)
        assertEquals(10_800_000.0, evaluator.builtins.getValue("cd2"), TOLERANCE)
        assertEquals(5_400_000.0, evaluator.builtins.getValue("cd4"), TOLERANCE)
        assertEquals(2_700_000.0, evaluator.builtins.getValue("cd8"), TOLERANCE)
        assertEquals(16_200_000.0, evaluator.builtins.getValue("3cd4"), TOLERANCE)
        assertEquals(50.0, evaluator.builtins.getValue("wd2"), TOLERANCE)
        assertEquals(25.0, evaluator.builtins.getValue("hd2"), TOLERANCE)
        assertEquals(3.125, evaluator.builtins.getValue("wd32"), TOLERANCE)
        assertEquals(50.0 / 6.0, evaluator.builtins.getValue("ssd6"), TOLERANCE)
        assertEquals(100.0 / 6.0, evaluator.builtins.getValue("lsd6"), TOLERANCE)
    }

    @Test
    fun `builtins resolve inside formulas`() {
        assertEquals(100.0, value("+/ w w 2"), TOLERANCE)
        assertEquals(25.0, value("*/ ss h 100"), TOLERANCE)
    }

    @Test
    fun `guides read earlier guides in document order`() {
        val result = evaluator.evaluate(listOf(Guide("a", "val 10"), Guide("b", "+- a 5 2")))
        assertEquals(10.0, result.getValue("a"), TOLERANCE)
        assertEquals(13.0, result.getValue("b"), TOLERANCE)
    }

    @Test
    fun `adjust values are in scope`() {
        val result = evaluator.evaluate(
            listOf(Guide("edge", "*/ adj1 w 100")),
            adjusts = mapOf("adj1" to 25.0),
        )
        assertEquals(25.0, result.getValue("edge"), TOLERANCE)
    }

    @Test
    fun `a malformed formula defines nothing and later references fail`() {
        val result = evaluator.evaluate(
            listOf(Guide("bad", "val 1 2"), Guide("later", "+- bad 1 0"), Guide("unknown", "foo 1")),
        )
        assertFalse(result.containsKey("bad"))
        assertFalse(result.containsKey("later"))
        assertFalse(result.containsKey("unknown"))
    }

    @Test
    fun `an unknown name is rejected`() {
        assertNull(evaluator.evaluate("val nope", emptyMap()))
    }

    @Test
    fun `a non finite result is rejected`() {
        assertNull(evaluator.evaluate("*/ 1 1 0", emptyMap()))
        assertNull(evaluator.evaluate("+/ 1 -1 0", emptyMap()))
        assertNull(evaluator.evaluate("sqrt -1", emptyMap()))
        assertNull(evaluator.evaluate("val 1e999", emptyMap()))
    }

    @Test
    fun `an empty formula is rejected`() {
        assertNull(evaluator.evaluate("   ", emptyMap()))
    }

    @Test
    fun `the evaluation cap bounds each batch`() {
        val capped = GuideEvaluator(width = 100.0, height = 50.0, limits = PptxLimits(maxGuideEvaluations = 2))
        val first = capped.evaluate(
            listOf(Guide("a", "val 1"), Guide("b", "val 2"), Guide("c", "val 3")),
        )
        assertEquals(1.0, first.getValue("a"), TOLERANCE)
        assertEquals(2.0, first.getValue("b"), TOLERANCE)
        assertFalse(first.containsKey("c"))

        val second = capped.evaluate(
            listOf(Guide("d", "val 4"), Guide("e", "val 5"), Guide("f", "val 6")),
        )
        assertEquals(4.0, second.getValue("d"), TOLERANCE)
        assertFalse(second.containsKey("f"))
    }

    private fun value(formula: String): Double =
        requireNotNull(evaluator.evaluate(formula, emptyMap()))

    private companion object {
        const val TOLERANCE = 1e-6
    }
}
