package com.papyrus.app.viewer

/**
 * Walks a grpprl: sprms packed one after another, each a 2-byte opcode and an operand. The opcode's
 * top three bits (`spra`) give the operand's size: 1, 1, 2, 4, 2, 2 bytes for 0 to 5, a length
 * byte then that many bytes for 6, and 3 bytes for 7. Two sprms break that rule, [TDEFTABLE] (a
 * 2-byte length) and [PCHGTABS] (a length of 0xFF means the size is spelled out inside).
 *
 * A grpprl comes from a file that may be damaged: a size that would run past [end] ends the walk
 * rather than throwing, so what was read before it still counts.
 */
internal class DocSprmReader(private val buf: ByteArray, from: Int, end: Int) {
    private val end = end.coerceAtMost(buf.size)
    private var pos = from.coerceAtLeast(0)

    var opcode = 0
        private set

    /** Offset of the operand's first byte; for a variable-size operand, of its length prefix. */
    var operand = 0
        private set

    var operandSize = 0
        private set

    /** Moves to the next sprm; false once the grpprl is used up or its next sprm does not fit. */
    fun next(): Boolean {
        if (pos + 2 > end) return false
        val op = u16(buf, pos)
        val size = operandSizeOf(op, pos + 2)
        if (size < 0 || pos + 2 + size > end) {
            pos = end
            return false
        }
        opcode = op
        operand = pos + 2
        operandSize = size
        pos += 2 + size
        return true
    }

    fun u8(): Int = if (operandSize >= 1) buf[operand].toInt() and 0xFF else 0

    fun u16(): Int = if (operandSize >= 2) u16(buf, operand) else 0

    /** The operand as a signed 16-bit value. */
    fun i16(): Int = u16().toShort().toInt()

    private fun operandSizeOf(op: Int, at: Int): Int = when (op ushr SPRA_SHIFT) {
        0, 1 -> 1
        2, 4, 5 -> 2
        3 -> 4
        7 -> 3
        else -> variableSize(op, at)
    }

    private fun variableSize(op: Int, at: Int): Int {
        if (at >= end) return -1
        if (op == TDEFTABLE) return if (at + 2 <= end) u16(buf, at) + 1 else -1
        val cb = buf[at].toInt() and 0xFF
        return if (cb == TAB_LIST_SIZED_INSIDE && op == PCHGTABS) tabListSize(at) else cb + 1
    }

    /** Operand of a sprmPChgTabs whose length byte is 0xFF: deleted tab stops, then added ones. */
    private fun tabListSize(at: Int): Int {
        if (at + 2 > end) return -1
        val deleted = buf[at + 1].toInt() and 0xFF
        val addedAt = at + 2 + deleted * TAB_DELETED_BYTES
        if (deleted > MAX_TABS || addedAt >= end) return -1
        val added = buf[addedAt].toInt() and 0xFF
        if (added > MAX_TABS) return -1
        return addedAt + 1 + added * TAB_ADDED_BYTES - at
    }

    companion object {
        /** sprmTDefTable: a row's cell edges and cell descriptors. */
        const val TDEFTABLE = 0xD608
        const val PCHGTABS = 0xC615

        private const val SPRA_SHIFT = 13
        private const val TAB_LIST_SIZED_INSIDE = 0xFF
        private const val MAX_TABS = 64
        private const val TAB_DELETED_BYTES = 4
        private const val TAB_ADDED_BYTES = 3
    }
}
