package io.github.pixelrick420.papyrus.viewer.pptx.text

import io.github.pixelrick420.papyrus.viewer.Json
import java.io.File

/** One measured line in points: its text, where it starts, its baseline, and its advance width. */
internal class ReferenceLine(val text: String, val x: Double, val baseline: Double, val width: Double)

/** One control-deck case: the inputs the deck swept and the lines the oracle drew from them. */
internal class ReferenceCase(
    val id: String,
    val font: String,
    val sizePt: Double,
    val lineSpacing: TextSpacing?,
    val spaceBefore: TextSpacing?,
    val spaceAfter: TextSpacing?,
    val lines: List<ReferenceLine>,
    val textHeightPt: Double,
    val ascentEm: Double? = null,
    val descentEm: Double? = null,
) {

    /** The font metrics the manifest recorded, or the model's fallback when it had no font file. */
    val fontBox: FontEmBox
        get() = if (ascentEm != null && descentEm != null) FontEmBox(ascentEm, descentEm) else FontEmBox.FALLBACK
}

/** A control deck's measured output, as `tools/pptx/extract_reference.py` writes it. */
internal class ReferenceDeck(
    val name: String,
    val powerpointBuild: String,
    /** Where the numbers came from; the LibreOffice fallback proves less than a PowerPoint export. */
    val oracle: String,
    val cases: List<ReferenceCase>,
) {

    companion object {

        fun load(file: File): ReferenceDeck {
            val root = Json(file.readText()).parse() as Map<*, *>
            val cases = (root["cases"] as List<*>).map { case ->
                val entry = case as Map<*, *>
                ReferenceCase(
                    id = entry["id"] as String,
                    font = entry["font"] as String,
                    sizePt = number(entry, "sizePt"),
                    lineSpacing = spacing(entry, "lnSpcPct", "lnSpcPts"),
                    spaceBefore = spacing(entry, "spcBefPct", "spcBefPts"),
                    spaceAfter = spacing(entry, "spcAftPct", "spcAftPts"),
                    lines = (entry["lines"] as List<*>).map { line ->
                        val measured = line as Map<*, *>
                        ReferenceLine(
                            text = measured["text"] as String,
                            x = number(measured, "x"),
                            baseline = number(measured, "baseline"),
                            width = number(measured, "width"),
                        )
                    },
                    textHeightPt = number(entry, "textHeightPt"),
                    ascentEm = (entry["ascentEm"] as? Number)?.toDouble(),
                    descentEm = (entry["descentEm"] as? Number)?.toDouble(),
                )
            }
            return ReferenceDeck(
                name = root["deck"] as String,
                powerpointBuild = root["powerpointBuild"] as String,
                oracle = root["oracle"] as String? ?: "PowerPoint",
                cases = cases,
            )
        }

        /** Every `*.json` under [directory], sorted so a failing sweep names a stable case. */
        fun loadAll(directory: File): List<ReferenceDeck> =
            directory.listFiles { file -> file.extension == "json" }
                ?.sortedBy { it.name }
                ?.map(::load)
                .orEmpty()

        private fun number(source: Map<*, *>, key: String): Double =
            (source[key] as? Number)?.toDouble() ?: error("missing $key")

        private fun spacing(source: Map<*, *>, percent: String, points: String): TextSpacing? {
            val pct = (source[percent] as? Number)?.toDouble()
            val pts = (source[points] as? Number)?.toDouble()
            return if (pct == null && pts == null) null else TextSpacing(pct, pts)
        }
    }
}
