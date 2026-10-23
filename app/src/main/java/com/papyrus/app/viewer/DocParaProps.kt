package com.papyrus.app.viewer

/**
 * The paragraph properties the extractor draws: a style's own, with a paragraph's direct sprms
 * applied over them. Filled by [applyGrpprl]; an instance is never changed once it is handed out.
 */
internal class DocParaProps(val istd: Int) {
    var align = BlockAlign.START
        private set

    /** Left indent in twips, for [indentLevel]. */
    var leftTwips = 0
        private set

    /** The paragraph is a cell's or a row's last paragraph or in one (`sprmPFInTable`). */
    var inTable = false
        private set

    /** Heading 1 to 6 from the paragraph's style, or 0 for body text. Set by [DocStyleSheet]. */
    var headingLevel = 0
        internal set

    /** A copy for paragraph [istd], to be changed by the direct sprms of one paragraph. */
    fun copyFor(istd: Int): DocParaProps {
        val out = DocParaProps(istd)
        out.align = align
        out.leftTwips = leftTwips
        out.inTable = inTable
        out.headingLevel = headingLevel
        return out
    }

    /** Applies the paragraph sprms in `buf[from, end)`; sprms of other kinds, and unknown ones, are skipped. */
    fun applyGrpprl(buf: ByteArray, from: Int, end: Int) {
        val reader = DocSprmReader(buf, from, end)
        while (reader.next()) {
            when (reader.opcode) {
                PJC80, PJC -> align = alignmentOf(reader.u8())
                PDXALEFT80, PDXALEFT -> leftTwips = reader.i16()
                PFINTABLE -> inTable = reader.u8() != 0
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
        const val JC_CENTER = 1
        const val JC_RIGHT = 2
    }
}
