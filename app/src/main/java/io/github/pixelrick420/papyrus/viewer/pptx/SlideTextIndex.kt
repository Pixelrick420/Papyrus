package io.github.pixelrick420.papyrus.viewer.pptx

import io.github.pixelrick420.papyrus.viewer.NormRect
import io.github.pixelrick420.papyrus.viewer.PdfLinkTarget
import io.github.pixelrick420.papyrus.viewer.PdfPageLink
import io.github.pixelrick420.papyrus.viewer.PdfPageText
import io.github.pixelrick420.papyrus.viewer.pptx.table.TableLayout
import io.github.pixelrick420.papyrus.viewer.pptx.text.TextBody
import io.github.pixelrick420.papyrus.viewer.pptx.text.TextFrameLayout
import io.github.pixelrick420.papyrus.viewer.pptx.text.TextLayoutEngine
import io.github.pixelrick420.papyrus.viewer.pptx.text.TextMeasurer
import io.github.pixelrick420.papyrus.viewer.pptx.text.TextRun
import io.github.pixelrick420.papyrus.viewer.pptx.text.TextShapeTransform
import io.github.pixelrick420.papyrus.viewer.pptx.text.TextStyleResolver
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.util.BitSet
import kotlin.math.max
import kotlin.math.min

internal class SlideTextIndex(
    val pages: List<PdfPageText>,
    val links: List<List<PdfPageLink>> = emptyList(),
    val semanticTexts: List<String> = emptyList(),
) {

    fun textOf(index: Int): String = pages.getOrNull(index)?.text.orEmpty()

    companion object {

        fun fromScenes(
            scenes: List<SlideScene>,
            resolver: TextStyleResolver,
            engine: TextLayoutEngine,
            linkOf: (Int, String, String?, String?) -> PdfLinkTarget? = { _, _, _, _ -> null },
        ): SlideTextIndex {
            val pages = ArrayList<PdfPageText>(scenes.size)
            val links = ArrayList<List<PdfPageLink>>(scenes.size)
            val semantics = ArrayList<String>(scenes.size)
            scenes.forEachIndexed { index, slide ->
                val page = pageOf(slide.scene, resolver, engine, index, slide.part, linkOf)
                pages += page.text
                links += page.links
                semantics += semanticTextOf(slide.scene)
            }
            return SlideTextIndex(pages, links, semantics)
        }

        fun fromDeck(
            pkg: PptxPackage,
            presentation: PresentationInfo,
            scenes: List<SlideScene>,
            resolver: TextStyleResolver,
            engine: TextLayoutEngine,
        ): SlideTextIndex {
            for (slide in scenes) TableLayout.layout(slide.scene, resolver, engine)
            val slideOfPart = presentation.slides
                .mapIndexedNotNull { index, ref ->
                    pkg.targetOf(pkg.mainPart, ref.relationshipId)?.let { it.lowercase() to index }
                }
                .toMap()
            val slideCount = scenes.size
            return fromScenes(scenes, resolver, engine) { index, part, relationshipId, action ->
                val rel = relationshipId?.let { pkg.relationship(part, it) }
                slideLinkTarget(
                    action = action,
                    externalUrl = rel?.takeIf { it.external }?.target,
                    targetSlide = rel?.takeUnless { it.external }?.target?.let { slideOfPart[it.lowercase()] },
                    current = index,
                    slideCount = slideCount,
                )
            }
        }

        /** The text find-in-file sees, page by page, so the search index and the screen never disagree. */
        fun indexPages(
            open: () -> InputStream,
            measurer: TextMeasurer,
            newParserFactory: () -> XmlPullParser,
            limits: PptxLimits = PptxLimits(),
        ): List<String> {
            val pkg = PptxPackage.read(open, mediaDir = null, limits = limits, newParserFactory = newParserFactory)
            val main = pkg.mainPart
            val bytes = pkg.part(main) ?: throw PptxFormatException("$main is missing")
            val presentation = PresentationParser(newParserFactory, limits).parse(bytes, main)
            val scenes = StyleResolver(newParserFactory, limits).resolveAll(pkg, presentation)
            val resolver = TextStyleResolver(limits)
            val engine = TextLayoutEngine(measurer)
            return fromDeck(pkg, presentation, scenes.slides, resolver, engine).pages.map { it.text }
        }

        internal fun semanticTextOf(scene: Scene): String {
            val parts = ArrayList<String>()
            collectSemanticNodes(scene.nodes, parts)
            return parts.joinToString(". ") { it.trimEnd('.', '!', '?', ':', ';') }.trim()
        }

        private fun collectSemanticNodes(nodes: List<SceneNode>, out: MutableList<String>) {
            for (node in nodes) {
                if (node.hidden) continue
                if (node is SceneGroup) {
                    if (!node.decorative) collectSemanticNodes(node.children, out)
                    continue
                }
                if (node.decorative) continue
                val descr = node.description?.trim().orEmpty()
                if (descr.isEmpty()) continue
                when (node) {
                    is ScenePicture -> out += descr
                    is SceneGraphicFrame -> if (node.kind in ACCESSIBLE_GRAPHIC_KINDS) out += descr
                    else -> Unit
                }
            }
        }

        private val ACCESSIBLE_GRAPHIC_KINDS = setOf(GraphicKind.CHART, GraphicKind.SMART_ART, GraphicKind.OLE_OBJECT)

        private fun pageOf(
            scene: Scene,
            resolver: TextStyleResolver,
            engine: TextLayoutEngine,
            slideIndex: Int,
            slidePart: String,
            linkOf: (Int, String, String?, String?) -> PdfLinkTarget?,
        ): SlidePage {
            val placed = ArrayList<PlacedLayout>()
            collectNodes(scene.nodes, scene, resolver, engine, placed, slidePart)
            placed.sortWith(compareBy({ it.top }, { it.left }))
            val links = ArrayList<PdfPageLink>()
            collectNodeLinks(scene.nodes, scene, 0.0, 0.0, slideIndex, slidePart, linkOf, links)
            val text = StringBuilder()
            val boxes = ArrayList<Float>()
            val breaks = BitSet()
            for (item in placed.withIndex()) {
                if (item.index > 0 && text.isNotEmpty() && text.last() != '\n') {
                    breaks.set(text.length)
                    appendChar(text, boxes, '\n', Float.NaN, Float.NaN, Float.NaN, Float.NaN)
                }
                appendFrame(item.value, scene.size, text, boxes, breaks, slideIndex, linkOf, links)
            }
            while (text.isNotEmpty() && text.last() == '\n') {
                val i = text.length - 1
                breaks.clear(i)
                text.deleteCharAt(i)
                repeat(4) { boxes.removeAt(boxes.size - 1) }
            }
            if (text.isEmpty()) return SlidePage(PdfPageText("", FloatArray(0)), links)
            val arr = FloatArray(boxes.size) { boxes[it] }
            return SlidePage(PdfPageText(text.toString(), arr, breaks), links)
        }

        private fun collectNodeLinks(
            nodes: List<SceneNode>,
            scene: Scene,
            dx: Double,
            dy: Double,
            slideIndex: Int,
            slidePart: String,
            linkOf: (Int, String, String?, String?) -> PdfLinkTarget?,
            out: MutableList<PdfPageLink>,
        ) {
            for (node in nodes) {
                val hyperlink = node.hyperlink
                val frame = node.frame
                if (hyperlink != null && frame != null) {
                    val part = node.sourcePart ?: slidePart
                    linkOf(slideIndex, part, hyperlink.relationshipId, hyperlink.action)?.let { target ->
                        out += PdfPageLink(normRect(offset(frame, dx, dy), scene.size), target)
                    }
                }
                if (node is SceneGroup) {
                    collectNodeLinks(
                        node.children,
                        scene,
                        dx + (node.frame?.x ?: 0.0),
                        dy + (node.frame?.y ?: 0.0),
                        slideIndex,
                        slidePart,
                        linkOf,
                        out,
                    )
                }
            }
        }

        private fun collectNodes(
            nodes: List<SceneNode>,
            scene: Scene,
            resolver: TextStyleResolver,
            engine: TextLayoutEngine,
            out: MutableList<PlacedLayout>,
            slidePart: String,
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
                        slidePart,
                        groupOffsetX + (node.frame?.x ?: 0.0),
                        groupOffsetY + (node.frame?.y ?: 0.0),
                    )
                    is SceneShape -> placedShape(node, resolver, engine, groupOffsetX, groupOffsetY, slidePart)?.let(out::add)
                    is ScenePicture -> placedPicture(node)?.let(out::add)
                    is SceneGraphicFrame -> placedTable(node, groupOffsetX, groupOffsetY, out, slidePart)
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
            slidePart: String,
        ): PlacedLayout? {
            val frame = shape.frame ?: return null
            if (frame.width <= 0.0 || frame.height <= 0.0) return null
            val body = shape.textBody?.let { runCatching { resolver.resolve(it) }.getOrNull() } ?: return null
            if (!body.hasText()) return null
            val layout = runCatching {
                engine.layout(body, frame.width, frame.height, TextShapeTransform.of(shape.transform))
            }.getOrNull() ?: return null
            if (layout.lines.isEmpty()) return null
            return PlacedLayout(layout, offset(frame, dx, dy), paragraphTexts(body), shape.sourcePart ?: slidePart)
        }

        private fun placedPicture(
            picture: ScenePicture,
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
            slidePart: String,
        ) {
            val table = node.table ?: return
            val result = table.layout ?: return
            val origin = offset(node.frame ?: return, dx, dy)
            for ((key, rect) in result.cellFrames.entries.sortedWith(compareBy({ it.key.first }, { it.key.second }))) {
                val cell = table.rows.getOrNull(key.first)?.cells?.getOrNull(key.second) ?: continue
                val layout = cell.textLayout ?: continue
                val texts = paragraphTexts(cell.resolvedText ?: TextBody())
                val placed = SceneRect(origin.x + rect.x, origin.y + rect.y, rect.width, rect.height)
                out += PlacedLayout(layout, placed, texts, node.sourcePart ?: slidePart)
            }
        }

        private fun appendFrame(
            placed: PlacedLayout,
            slide: SceneSize,
            text: StringBuilder,
            boxes: MutableList<Float>,
            breaks: BitSet,
            slideIndex: Int,
            linkOf: (Int, String, String?, String?) -> PdfLinkTarget?,
            links: MutableList<PdfPageLink>,
        ) {
            val sw = slide.width.takeIf { it > 0.0 } ?: return
            val sh = slide.height.takeIf { it > 0.0 } ?: return
            val frame = placed.frame
            for ((lineIndex, line) in placed.layout.lines.withIndex()) {
                for (run in line.runs) {
                    val hyperlink = run.properties.hyperlink ?: continue
                    val target = linkOf(slideIndex, placed.sourcePart, hyperlink.relationshipId, hyperlink.action) ?: continue
                    val left = normX(frame.x + run.leftPt * EMU_PER_POINT, sw)
                    val right = normX(frame.x + run.rightPt * EMU_PER_POINT, sw)
                    val top = normY(frame.y + line.topPt * EMU_PER_POINT, sh)
                    val bottom = normY(frame.y + (line.topPt + line.heightPt) * EMU_PER_POINT, sh)
                    if (right > left && bottom > top) {
                        links += PdfPageLink(NormRect(left, top, right, bottom), target)
                    }
                }
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

        private fun normRect(rect: SceneRect, size: SceneSize): NormRect {
            val sw = size.width.takeIf { it > 0.0 }
            val sh = size.height.takeIf { it > 0.0 }
            if (sw == null || sh == null) return NormRect(0f, 0f, 0f, 0f)
            val left = normX(rect.x, sw)
            val right = normX(rect.right, sw)
            val top = normY(rect.y, sh)
            val bottom = normY(rect.bottom, sh)
            return NormRect(min(left, right), min(top, bottom), max(left, right), max(top, bottom))
        }

        private const val EMU_PER_POINT = 12_700.0
    }

    private class SlidePage(val text: PdfPageText, val links: List<PdfPageLink>)

    private data class PlacedLayout(
        val layout: TextFrameLayout,
        val frame: SceneRect,
        val paragraphTexts: List<String>,
        val sourcePart: String,
    ) {
        val top: Double get() = frame.y
        val left: Double get() = frame.x
    }
}
