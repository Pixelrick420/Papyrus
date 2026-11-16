package io.github.pixelrick420.papyrus.viewer.pptx

import io.github.pixelrick420.papyrus.viewer.PdfPageText
import io.github.pixelrick420.papyrus.viewer.pptx.text.TextBody
import io.github.pixelrick420.papyrus.viewer.pptx.text.TextFrameLayout
import io.github.pixelrick420.papyrus.viewer.pptx.text.TextLayoutEngine
import io.github.pixelrick420.papyrus.viewer.pptx.text.TextRun
import io.github.pixelrick420.papyrus.viewer.pptx.text.TextShapeTransform
import io.github.pixelrick420.papyrus.viewer.pptx.text.TextStyleResolver
import java.util.BitSet
import kotlin.math.max
import kotlin.math.min

internal class SlideTextIndex(
    val pages: List<PdfPageText>,
) {

    fun textOf(index: Int): String = pages.getOrNull(index)?.text.orEmpty()

    companion object {

        fun fromScenes(
            scenes: List<SlideScene>,
            resolver: TextStyleResolver,
            engine: TextLayoutEngine,
        ): SlideTextIndex = SlideTextIndex(scenes.map { pageOf(it.scene, resolver, engine) })

        private fun pageOf(
            scene: Scene,
            resolver: TextStyleResolver,
            engine: TextLayoutEngine,
        ): PdfPageText {
            val placed = ArrayList<PlacedLayout>()
            collectNodes(scene.nodes, scene, resolver, engine, placed)
            placed.sortWith(compareBy({ it.top }, { it.left }))
            val text = StringBuilder()
            val boxes = ArrayList<Float>()
            val breaks = BitSet()
            for (item in placed.withIndex()) {
                if (item.index > 0 && text.isNotEmpty() && text.last() != '\n') {
                    breaks.set(text.length)
                    appendChar(text, boxes, '\n', Float.NaN, Float.NaN, Float.NaN, Float.NaN)
                }
                appendFrame(item.value, scene.size, text, boxes, breaks)
            }
            while (text.isNotEmpty() && text.last() == '\n') {
                val i = text.length - 1
                breaks.clear(i)
                text.deleteCharAt(i)
                repeat(4) { boxes.removeAt(boxes.size - 1) }
            }
            if (text.isEmpty()) return PdfPageText("", FloatArray(0))
            val arr = FloatArray(boxes.size) { boxes[it] }
            return PdfPageText(text.toString(), arr, breaks)
        }

        private fun collectNodes(
            nodes: List<SceneNode>,
            scene: Scene,
            resolver: TextStyleResolver,
            engine: TextLayoutEngine,
            out: MutableList<PlacedLayout>,
            groupOffsetX: Double = 0.0,
            groupOffsetY: Double = 0.0,
        ) {
            for (node in nodes) {
                when (node) {
                    is SceneGroup -> collectNodes(
                        node.children,
                        scene,
                        resolver,
                        engine,
                        out,
                        groupOffsetX + (node.frame?.x ?: 0.0),
                        groupOffsetY + (node.frame?.y ?: 0.0),
                    )
                    is SceneShape -> placedShape(node, resolver, engine, groupOffsetX, groupOffsetY)?.let(out::add)
                    is ScenePicture -> placedPicture(node, groupOffsetX, groupOffsetY)?.let(out::add)
                    is SceneGraphicFrame -> placedTable(node, groupOffsetX, groupOffsetY, out)
                    else -> Unit
                }
            }
        }

        private fun placedShape(
            shape: SceneShape,
            resolver: TextStyleResolver,
            engine: TextLayoutEngine,
            dx: Double,
            dy: Double,
        ): PlacedLayout? {
            val frame = shape.frame ?: return null
            if (frame.width <= 0.0 || frame.height <= 0.0) return null
            val body = shape.textBody?.let { runCatching { resolver.resolve(it) }.getOrNull() } ?: return null
            if (!body.hasText()) return null
            val layout = runCatching {
                engine.layout(body, frame.width, frame.height, transformOf(shape.transform))
            }.getOrNull() ?: return null
            if (layout.lines.isEmpty()) return null
            return PlacedLayout(layout, offset(frame, dx, dy), paragraphTexts(body))
        }

        private fun placedPicture(
            picture: ScenePicture,
            dx: Double,
            dy: Double,
        ): PlacedLayout? {
            val frame = picture.frame ?: return null
            val sceneText = picture.text ?: return null
            if (frame.width <= 0.0 || frame.height <= 0.0) return null
            val texts = sceneText.paragraphs.map { p -> p.runs.joinToString("") { it.text } }
            if (texts.all { it.isBlank() }) return null
            return null
        }

        private fun placedTable(
            node: SceneGraphicFrame,
            dx: Double,
            dy: Double,
            out: MutableList<PlacedLayout>,
        ) {
            val table = node.table ?: return
            val result = table.layout ?: return
            val origin = offset(node.frame ?: return, dx, dy)
            for ((key, rect) in result.cellFrames.entries.sortedWith(compareBy({ it.key.first }, { it.key.second }))) {
                val cell = table.rows.getOrNull(key.first)?.cells?.getOrNull(key.second) ?: continue
                val layout = cell.textLayout ?: continue
                val texts = paragraphTexts(cell.resolvedText ?: TextBody())
                val placed = SceneRect(origin.x + rect.x, origin.y + rect.y, rect.width, rect.height)
                out += PlacedLayout(layout, placed, texts)
            }
        }

        private fun appendFrame(
            placed: PlacedLayout,
            slide: SceneSize,
            text: StringBuilder,
            boxes: MutableList<Float>,
            breaks: BitSet,
        ) {
            val sw = slide.width.takeIf { it > 0.0 } ?: return
            val sh = slide.height.takeIf { it > 0.0 } ?: return
            val frame = placed.frame
            for ((lineIndex, line) in placed.layout.lines.withIndex()) {
                if (lineIndex > 0) {
                    breaks.set(text.length)
                    appendChar(text, boxes, '\n', Float.NaN, Float.NaN, Float.NaN, Float.NaN)
                }
                val paraText = placed.paragraphTexts.getOrNull(line.paragraph).orEmpty()
                for (charBox in line.characters) {
                    val s = charBox.start.coerceIn(0, paraText.length)
                    val e = charBox.end.coerceIn(s, paraText.length)
                    val left = normX(frame.x + charBox.leftPt * EMU_PER_POINT, sw)
                    val right = normX(frame.x + charBox.rightPt * EMU_PER_POINT, sw)
                    val top = normY(frame.y + charBox.topPt * EMU_PER_POINT, sh)
                    val bottom = normY(frame.y + charBox.bottomPt * EMU_PER_POINT, sh)
                    if (e > s) {
                        for (i in s until e) {
                            appendChar(text, boxes, paraText[i], left, top, right, bottom)
                        }
                    } else {
                        appendChar(text, boxes, ' ', left, top, right, bottom)
                    }
                }
            }
        }

        private fun appendChar(
            text: StringBuilder,
            boxes: MutableList<Float>,
            ch: Char,
            l: Float,
            t: Float,
            r: Float,
            b: Float,
        ) {
            text.append(ch)
            boxes += l
            boxes += t
            boxes += r
            boxes += b
        }

        private fun normX(v: Double, w: Double): Float = (v / w).toFloat().coerceIn(0f, 1f)
        private fun normY(v: Double, h: Double): Float = (v / h).toFloat().coerceIn(0f, 1f)

        private fun paragraphTexts(body: TextBody): List<String> = body.paragraphs.map { p ->
            buildString {
                for (run in p.runs) {
                    when (run) {
                        is TextRun.Text -> append(run.text)
                        is TextRun.Field -> append(run.text)
                        is TextRun.Break -> Unit
                    }
                }
            }
        }

        private fun TextBody.hasText(): Boolean = paragraphs.any { p ->
            p.runs.any { run ->
                when (run) {
                    is TextRun.Text -> run.text.isNotBlank()
                    is TextRun.Field -> run.text.isNotBlank()
                    is TextRun.Break -> false
                }
            }
        }

        private fun offset(rect: SceneRect, dx: Double, dy: Double): SceneRect =
            SceneRect(rect.x + dx, rect.y + dy, rect.width, rect.height)

        private fun transformOf(node: XNode?): TextShapeTransform {
            node ?: return TextShapeTransform.NONE
            val rot = (node.attribute("rot")?.toDoubleOrNull() ?: 0.0) / 60000.0
            return TextShapeTransform(
                rotationDeg = rot,
                flipH = node.attribute("flipH") == "1" || node.attribute("flipH").equals("true", ignoreCase = true),
                flipV = node.attribute("flipV") == "1" || node.attribute("flipV").equals("true", ignoreCase = true),
            )
        }

        private const val EMU_PER_POINT = 12_700.0
    }

    private data class PlacedLayout(
        val layout: TextFrameLayout,
        val frame: SceneRect,
        val paragraphTexts: List<String>,
    ) {
        val top: Double get() = frame.y
        val left: Double get() = frame.x
    }
}
