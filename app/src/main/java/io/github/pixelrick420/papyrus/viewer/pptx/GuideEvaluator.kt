package io.github.pixelrick420.papyrus.viewer.pptx

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/** One `a:gd`: a name and the formula that defines it. */
internal data class Guide(val name: String, val formula: String)

/**
 * Evaluates DrawingML guide formulas in document order for a shape of [width] by [height] EMU. Built-ins, the
 * caller's adjust values and every earlier guide are in scope; a formula that is malformed or produces a
 * non-finite number defines nothing.
 */
internal class GuideEvaluator(
    private val width: Double,
    private val height: Double,
    private val limits: PptxLimits = PptxLimits(),
) {

    private var evaluations = 0

    val builtins: Map<String, Double> = buildMap {
        put("l", 0.0)
        put("t", 0.0)
        put("r", width)
        put("b", height)
        put("w", width)
        put("h", height)
        put("hc", width / 2.0)
        put("vc", height / 2.0)
        val short = min(width, height)
        val long = max(width, height)
        put("ss", short)
        put("ls", long)
        put("cd", HALF_TURN * 2.0)
        put("cd2", HALF_TURN)
        put("cd4", HALF_TURN / 2.0)
        put("cd8", HALF_TURN / 4.0)
        put("3cd4", HALF_TURN * 3.0 / 2.0)
        put("3cd8", HALF_TURN * 3.0 / 4.0)
        put("5cd8", HALF_TURN * 5.0 / 4.0)
        put("7cd8", HALF_TURN * 7.0 / 4.0)
        for (divisor in LINEAR_DIVISORS) {
            put("wd$divisor", width / divisor)
            put("hd$divisor", height / divisor)
        }
        for (divisor in SHORT_DIVISORS) {
            put("ssd$divisor", short / divisor)
            put("lsd$divisor", long / divisor)
        }
    }

    fun evaluate(guides: List<Guide>, adjusts: Map<String, Double> = emptyMap()): Map<String, Double> {
        evaluations = 0
        val scope = HashMap<String, Double>(builtins).apply { putAll(adjusts) }
        for (guide in guides) {
            if (evaluations >= limits.maxGuideEvaluations) break
            evaluate(guide.formula, scope)?.let { scope[guide.name] = it }
        }
        return scope
    }

    fun evaluate(formula: String, scope: Map<String, Double>): Double? {
        if (evaluations >= limits.maxGuideEvaluations) return null
        val trimmed = formula.trim()
        if (trimmed.isEmpty()) return null
        val tokens = trimmed.split(WHITESPACE)
        evaluations++
        val result = when (tokens[0]) {
            "val" -> unary(tokens, scope) { it }
            "abs" -> unary(tokens, scope) { abs(it) }
            "sqrt" -> unary(tokens, scope) { sqrt(it) }
            "min" -> binary(tokens, scope) { x, y -> min(x, y) }
            "max" -> binary(tokens, scope) { x, y -> max(x, y) }
            "+-" -> ternary(tokens, scope) { x, y, z -> x + y - z }
            "*/" -> ternary(tokens, scope) { x, y, z -> x * y / z }
            "+/" -> ternary(tokens, scope) { x, y, z -> (x + y) / z }
            "?:" -> ternary(tokens, scope) { x, y, z -> if (x > 0.0) y else z }
            "pin" -> ternary(tokens, scope) { x, y, z -> y.coerceIn(min(x, z), max(x, z)) }
            "mod" -> ternary(tokens, scope) { x, y, z -> sqrt(x * x + y * y + z * z) }
            "sin" -> binary(tokens, scope) { x, y ->
                x * sin(Math.toRadians(y / UNITS_PER_DEGREE))
            }
            "cos" -> binary(tokens, scope) { x, y ->
                x * cos(Math.toRadians(y / UNITS_PER_DEGREE))
            }
            "tan" -> binary(tokens, scope) { x, y ->
                x * tan(Math.toRadians(y / UNITS_PER_DEGREE))
            }
            "at2" -> binary(tokens, scope) { x, y ->
                Math.toDegrees(atan2(y, x)) * UNITS_PER_DEGREE
            }
            "cat2" -> ternary(tokens, scope) { x, y, z -> x * cos(atan2(z, y)) }
            "sat2" -> ternary(tokens, scope) { x, y, z -> x * sin(atan2(z, y)) }
            else -> null
        }
        return result?.takeIf { it.isFinite() }
    }

    private fun unary(tokens: List<String>, scope: Map<String, Double>, operation: (Double) -> Double): Double? {
        if (tokens.size != 2) return null
        return operation(operand(tokens[1], scope) ?: return null)
    }

    private fun binary(
        tokens: List<String>,
        scope: Map<String, Double>,
        operation: (Double, Double) -> Double,
    ): Double? {
        if (tokens.size < 3) return null
        val x = operand(tokens[1], scope) ?: return null
        val y = operand(tokens[2], scope) ?: return null
        return operation(x, y)
    }

    private fun ternary(
        tokens: List<String>,
        scope: Map<String, Double>,
        operation: (Double, Double, Double) -> Double,
    ): Double? {
        if (tokens.size < 4) return null
        val x = operand(tokens[1], scope) ?: return null
        val y = operand(tokens[2], scope) ?: return null
        val z = operand(tokens[3], scope) ?: return null
        return operation(x, y, z)
    }

    private fun operand(token: String, scope: Map<String, Double>): Double? =
        token.toDoubleOrNull()?.takeIf { it.isFinite() } ?: scope[token] ?: builtins[token]

    private companion object {
        const val HALF_TURN = 10_800_000.0

        const val UNITS_PER_DEGREE = 60_000.0

        val WHITESPACE = Regex("\\s+")

        val LINEAR_DIVISORS = listOf(2, 3, 4, 5, 6, 8, 10, 12, 16, 32)

        val SHORT_DIVISORS = listOf(2, 4, 6, 8, 16, 32)
    }
}
