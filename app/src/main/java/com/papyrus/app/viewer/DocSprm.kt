package com.papyrus.app.viewer

// The opcode's top three bits (`spra`) give the operand size; [TDEFTABLE] and [PCHGTABS] break that rule. A grpprl may be damaged: a size running past [end] ends the walk.
internal class DocSprmReader(private val buf: ByteArray, from: Int, end: Int) {
    private val end = end.coerceAtMost(buf.size)
    private var pos = from.coerceAtLeast(0)

    var opcode = 0
        private set

    var operand = 0
        private set

    var operandSize = 0
        private set

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

    fun u8(at: Int = 0): Int = if (at >= 0 && at < operandSize) buf[operand + at].toInt() and 0xFF else 0

    fun u16(at: Int = 0): Int = if (at >= 0 && at + 2 <= operandSize) u16(buf, operand + at) else 0

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
        const val TDEFTABLE = 0xD608
        const val PCHGTABS = 0xC615

        private const val SPRA_SHIFT = 13
        private const val TAB_LIST_SIZED_INSIDE = 0xFF
        private const val MAX_TABS = 64
        private const val TAB_DELETED_BYTES = 4
        private const val TAB_ADDED_BYTES = 3
    }
}
