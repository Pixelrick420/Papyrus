package io.github.pixelrick420.papyrus.viewer

internal class DocParaProps(val istd: Int) {
    var align = BlockAlign.START
        private set

    var leftTwips = 0
        private set

    var inTable = false
        private set

    // Its 0x07 looks like a cell mark, and an empty cell's does too, so this flag is the only sure sign that a row is over.
    var rowEnd = false
        private set

    var ilfo = 0
        private set

    var ilvl = 0
        private set

    var headingLevel = 0
        internal set

    fun copyFor(istd: Int): DocParaProps {
        val out = DocParaProps(istd)
        out.align = align
        out.leftTwips = leftTwips
        out.inTable = inTable
        out.rowEnd = rowEnd
        out.ilfo = ilfo
        out.ilvl = ilvl
        out.headingLevel = headingLevel
        return out
    }

    fun applyGrpprl(buf: ByteArray, from: Int, end: Int) {
        val reader = DocSprmReader(buf, from, end)
        while (reader.next()) {
            when (reader.opcode) {
                PJC80, PJC -> align = alignmentOf(reader.u8())
                PDXALEFT80, PDXALEFT -> leftTwips = reader.i16()
                PFINTABLE -> inTable = reader.u8() != 0
                PFTTP -> rowEnd = reader.u8() != 0
                // A negative `ilfo` names an old outline-numbered list, which is not read: no list.
                PILFO -> ilfo = reader.i16().coerceAtLeast(0)
                PILVL -> ilvl = reader.u8()
            }
        }
    }

    private fun alignmentOf(jc: Int): BlockAlign = when (jc) {
        JC_CENTER -> BlockAlign.CENTER
        JC_RIGHT -> BlockAlign.END
        else -> BlockAlign.START
    }

    private companion object {
        /** `sprmPJc80` is Word 97's alignment, `sprmPJc` Word 2000's; writers emit both, the same. */
        const val PJC80 = 0x2403
        const val PJC = 0x2461
        const val PDXALEFT80 = 0x840F
        const val PDXALEFT = 0x845E
        const val PFINTABLE = 0x2416
        const val PFTTP = 0x2417
        const val PILVL = 0x260A
        const val PILFO = 0x460B
        const val JC_CENTER = 1
        const val JC_RIGHT = 2
    }
}
