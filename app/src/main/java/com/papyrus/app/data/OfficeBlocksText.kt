package com.papyrus.app.data

import com.papyrus.app.viewer.OfficeBlock

fun officeBlocksToText(blocks: List<OfficeBlock>, maxChars: Int): String {
    if (maxChars <= 0) return ""
    val out = StringBuilder()
    var wroteBlock = false
    for (block in blocks) {
        if (out.length >= maxChars) break
        val line = when (block) {
            is OfficeBlock.Heading -> block.text
            is OfficeBlock.Paragraph -> block.text
            is OfficeBlock.ListItem -> block.text
            is OfficeBlock.Table -> block.rows.joinToString(" ") { row -> row.joinToString(" ") { it.text } }
            is OfficeBlock.Image -> block.altText.orEmpty()
            is OfficeBlock.Note -> listOf(block.label, block.text)
                .filter { it.isNotBlank() }
                .joinToString(" ")
            OfficeBlock.Divider -> ""
        }
        if (line.isBlank()) continue
        if (wroteBlock) out.append('\n')
        wroteBlock = true
        out.append(line.take(maxChars - out.length))
    }
    return out.toString()
}
