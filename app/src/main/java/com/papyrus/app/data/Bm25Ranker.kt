package com.papyrus.app.data

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ln
import kotlin.math.min

// BM25 over FTS4 matchinfo('pcnalx'): little-endian int32s laid out p, c, n, a[c], l[c], x[3*c*p].
// Single-column table: revisit document frequency if a column is added. A bad blob scores 0, never throws.
object Bm25Ranker {

    /** Term-frequency saturation: how quickly further occurrences stop improving a score. */
    private const val K1 = 1.2

    /** Length normalisation: how far a long document is penalised against a short one. */
    private const val B = 0.75

    /** `p`, `c` and `n`, before the `a`, `l` and `x` blocks. */
    private const val LEADING_COUNT = 3

    // Higher is better; only comparable within one search.
    fun score(matchInfo: ByteArray): Double {
        val ints = readLittleEndianInts(matchInfo) ?: return 0.0
        if (ints.size < LEADING_COUNT) return 0.0
        val phrases = ints[0]
        val columns = ints[1]
        val rows = ints[2]
        if (phrases <= 0 || columns <= 0 || rows <= 0) return 0.0

        var total = 0.0
        for (phrase in 0 until phrases) {
            for (column in 0 until columns) {
                val cell = LEADING_COUNT + 2 * columns + 3 * (column + phrase * columns)
                val termFrequency = ints.getOrElse(cell) { 0 }
                val documentFrequency = min(ints.getOrElse(cell + 2) { 0 }, rows)
                if (termFrequency <= 0 || documentFrequency <= 0) continue
                val length = ints.getOrElse(LEADING_COUNT + columns + column) { 0 }.toDouble()
                val average = ints.getOrElse(LEADING_COUNT + column) { 0 }.coerceAtLeast(1).toDouble()
                // The +1 inside the logarithm keeps IDF positive even for terms appearing in most documents.
                val idf = ln(1.0 + (rows - documentFrequency + 0.5) / (documentFrequency + 0.5))
                val normalisation = K1 * (1 - B + B * length / average)
                total += idf * (termFrequency * (K1 + 1)) / (termFrequency + normalisation)
            }
        }
        return total
    }

    private fun readLittleEndianInts(bytes: ByteArray): IntArray? {
        if (bytes.size < LEADING_COUNT * Int.SIZE_BYTES || bytes.size % Int.SIZE_BYTES != 0) return null
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return IntArray(bytes.size / Int.SIZE_BYTES) { buffer.getInt() }
    }
}
