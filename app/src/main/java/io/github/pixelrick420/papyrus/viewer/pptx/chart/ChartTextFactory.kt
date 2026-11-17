package io.github.pixelrick420.papyrus.viewer.pptx.chart

import io.github.pixelrick420.papyrus.viewer.pptx.OoxmlAttribute
import io.github.pixelrick420.papyrus.viewer.pptx.OoxmlName
import io.github.pixelrick420.papyrus.viewer.pptx.OoxmlNamespace
import io.github.pixelrick420.papyrus.viewer.pptx.Rgba
import io.github.pixelrick420.papyrus.viewer.pptx.XNode

/** Builds the `a:txBody` a chart label hands to the same text engine that lays out slide text. */
internal object ChartTextFactory {

    fun body(
        text: String,
        sizePt: Double,
        color: Rgba,
        bold: Boolean = false,
        align: String = "ctr",
    ): XNode {
        val body = node(OoxmlNamespace.A, "txBody")
        body.children += node(
            OoxmlNamespace.A,
            "bodyPr",
            "wrap" to "none",
            "lIns" to "0",
            "tIns" to "0",
            "rIns" to "0",
            "bIns" to "0",
            "anchor" to "ctr",
        )
        for (line in text.split('\n')) {
            val paragraph = node(OoxmlNamespace.A, "p")
            paragraph.children += node(OoxmlNamespace.A, "pPr", "algn" to align)
            val run = node(OoxmlNamespace.A, "r")
            val properties = node(
                OoxmlNamespace.A,
                "rPr",
                "lang" to "en-US",
                "sz" to (sizePt * 100).toInt().toString(),
                "b" to if (bold) "1" else "0",
            )
            val fill = node(OoxmlNamespace.A, "solidFill")
            fill.children += node(OoxmlNamespace.A, "srgbClr", "val" to hex(color))
            properties.children += fill
            run.children += properties
            val content = node(OoxmlNamespace.A, "t")
            content.text = line
            run.children += content
            paragraph.children += run
            body.children += paragraph
        }
        return body
    }

    private fun node(namespace: String, name: String, vararg attributes: Pair<String, String>): XNode =
        XNode(
            OoxmlName(namespace, name),
            attributes.map { (key, value) -> OoxmlAttribute("", key, value) },
        )

    private fun hex(color: Rgba): String = "%02X%02X%02X".format(color.red, color.green, color.blue)
}
