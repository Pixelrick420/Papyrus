package io.github.pixelrick420.papyrus.viewer.pptx

import io.github.pixelrick420.papyrus.viewer.NormRect
import io.github.pixelrick420.papyrus.viewer.PdfPageText
import io.github.pixelrick420.papyrus.viewer.pptx.table.PptxTable
import io.github.pixelrick420.papyrus.viewer.pptx.text.ParagraphText
import io.github.pixelrick420.papyrus.viewer.pptx.text.TextBody
import io.github.pixelrick420.papyrus.viewer.pptx.text.TextFrameLayout
import io.github.pixelrick420.papyrus.viewer.pptx.text.TextLayoutEngine
import io.github.pixelrick420.papyrus.viewer.pptx.text.TextParagraph
import io.github.pixelrick420.papyrus.viewer.pptx.text.TextRun
import io.github.pixelrick420.papyrus.viewer.pptx.text.TextShaper
import io.github.pixelrick420.papyrus.viewer.pptx.text.TextShapeTransform
import io.github.pixelrick420.papyrus.viewer.pptx.text.TextStyleResolver
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
            val lineBoxes = ArrayList<List<NormRect>>()
            for ((i, item) in placed.withIndex()) {
                if (i > 0 && text.isNotEmpty() && text.last() != '\n') {
                    text.append('\n')
                    lineBoxes += emptyList<NormRect>()
                }
                appendFrame(item, scene.size, text, lineBoxes)
            }
            val raw = text.toString().trimEnd('\n')
            val trimmedBoxes = if (lineBoxes.size > raw.count { it == '\n' } + (if (raw.isEmpty()) 0 else 1)) {
                lineBoxes.take(raw.count { it == '\n' } + (if (raw.isEmpty()) 0 else 1))
            } else {
                lineBoxes
            }
            return if (raw.isEmpty()) PdfPageText("", FloatArray(0))
            else PdfPageText.fromLines(raw, trimmedBoxes)
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
                    is ScenePicture -> placedPicture(node, resolver, engine, groupOffsetX, groupOffsetY)?.let(out::add)
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
            if (body.paragraphs.all { p -> p.runs.all { (it as? TextRun.Text)?.text.isNullOrEmpty() && (it as? TextRun.Field)?.text.isNullOrEmpty() } }) {
                return null
            }
            val layout = runCatching {
                engine.layout(body, frame.width, frame.height, transformOf(shape.transform))
            }.getOrNull() ?: return null
            if (layout.lines.isEmpty()) return null
            return PlacedLayout(layout, offset(frame, dx, dy), paragraphTexts(body))
        }

        private fun placedPicture(
            picture: ScenePicture,
            resolver: TextStyleResolver,
            engine: TextLayoutEngine,
            dx: Double,
            dy: Double,
        ): PlacedLayout? {
            val frame = picture.frame ?: return null
            val sceneText = picture.text ?: return null
            if (frame.width <= 0.0 || frame.height <= 0.0) return null
            val body = bodyFromScene(sceneText)
            if (body.paragraphs.isEmpty()) return null
            val layout = runCatching { engine.layout(body, frame.width, frame.height) }.getOrNull() ?: return null
            if (layout.lines.isEmpty()) return null
            return PlacedLayout(layout, offset(frame, dx, dy), paragraphTexts(body))
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
                val body = cell.resolvedText ?: TextBody()
                val texts = paragraphTexts(body)
                val placed = SceneRect(origin.x + rect.x, origin.y + rect.y, rect.width, rect.height)
                out += PlacedLayout(layout, placed, texts)
            }
        }

        private fun appendFrame(
            placed: PlacedLayout,
            slide: SceneSize,
            text: StringBuilder,
            lineBoxes: MutableList<List<NormRect>>,
        ) {
            val sw = slide.width.takeIf { it > 0.0 } ?: return
            val sh = slide.height.takeIf { it > 0.0 } ?: return
            val frame = placed.frame
            for ((lineIndex, line) in placed.layout.lines.withIndex()) {
                if (lineIndex > 0) {
                    text.append('\n')
                    lineBoxes += emptyList<NormRect>()
                }
                val paraText = placed.paragraphTexts.getOrNull(line.paragraph).orEmpty()
                val rects = ArrayList<NormRect>(line.characters.size)
                for (charBox in line.characters) {
                    val s = charBox.start.coerceIn(0, paraText.length)
                    val e = charBox.end.coerceIn(s, paraText.length)
                    if (e <= s) {
                        text.append(' ')
                    } else {
                        text.append(paraText.substring(s, e))
                    }
                    val left = ((frame.x + charBox.leftPt * EMU_PER_POINT) / sw).toFloat()
                    val right = ((frame.x + charBox.rightPt * EMU_PER_POINT) / sh.let { sw }).toFloat()
                    val top = ((frame.y + charBox.topPt * EMU_PER_POINT) / sh).toFloat()
                    val bottom = ((frame.y + charBox.bottomPt * EMU_PER_POINT) / sh).toFloat()
                    rects += normRect(left, top, right, bottom)
                }
                lineBoxes += rects
            }
        }

        private fun normRect(l: Float, t: Float, r: Float, b: Float): NormRect = NormRect(
            min(l, r).coerceIn(0f, 1f),
            min(t, b).coerceIn(0f, 1f),
            max(l, r).coerceIn(0f, 1f),
            max(t, b).coerceIn(0f, 1f),
        )

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

        private fun bodyFromScene(frame: SceneTextFrame): TextBody = TextBody(
            paragraphs = frame.paragraphs.map { p ->
                TextParagraph(
                    level = p.level,
                    runs = p.runs.map { run ->
                        TextRun.Text(run.text, io.github.pixelrick420.papyrus.viewer.pptx.text.TextRunProperties())
                    },
                )
            },
        )

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
