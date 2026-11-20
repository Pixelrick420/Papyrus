package io.github.pixelrick420.papyrus.viewer.pptx

import io.github.pixelrick420.papyrus.viewer.NormRect
import io.github.pixelrick420.papyrus.viewer.PdfLinkTarget
import io.github.pixelrick420.papyrus.viewer.PdfPageLink
import io.github.pixelrick420.papyrus.viewer.PdfPageText
import io.github.pixelrick420.papyrus.viewer.pptx.table.TableLayout
import io.github.pixelrick420.papyrus.viewer.pptx.text.FontCatalog
import io.github.pixelrick420.papyrus.viewer.pptx.text.PaintTextMeasurer
import io.github.pixelrick420.papyrus.viewer.pptx.text.TextBody
import io.github.pixelrick420.papyrus.viewer.pptx.text.TextFrameLayout
import io.github.pixelrick420.papyrus.viewer.pptx.text.TextLayoutEngine
import io.github.pixelrick420.papyrus.viewer.pptx.text.TextMeasurer
import io.github.pixelrick420.papyrus.viewer.pptx.text.TextRun
import io.github.pixelrick420.papyrus.viewer.pptx.text.TextShapeTransform
import io.github.pixelrick420.papyrus.viewer.pptx.text.TextStyleResolver
import io.github.pixelrick420.papyrus.viewer.pptx.text.TextStyleSources
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.util.BitSet

/**
 * Canonical text path for slides: find-in-file, TTS and the library index all read these layout-based pages.
 * [SlideTextExtractor] is a legacy fallback only, used when a scene index cannot be built.
 */
internal class SlideTextIndex(
    val pages: List<PdfPageText>,
    val links: List<List<PdfPageLink>> = emptyList(),
    val semanticTexts: List<String> = emptyList(),
    val titles: List<String> = emptyList(),
) {

    fun textOf(index: Int): String = pages.getOrNull(index)?.text.orEmpty()

    companion object {

        /** One engine shape for the viewer's find index and the library indexer, so their words agree. */
        fun engine(fonts: FontCatalog): TextLayoutEngine = TextLayoutEngine(PaintTextMeasurer(fonts))

        fun fromScenes(
            scenes: List<SlideScene>,
            resolver: TextStyleResolver,
            engine: TextLayoutEngine,
            linkOf: (Int, String, String?, String?) -> PdfLinkTarget? = { _, _, _, _ -> null },
            limits: PptxLimits = PptxLimits(),
        ): SlideTextIndex {
            val pages = ArrayList<PdfPageText>(scenes.size)
            val links = ArrayList<List<PdfPageLink>>(scenes.size)
            val semantics = ArrayList<String>(scenes.size)
            val titles = ArrayList<String>(scenes.size)
            var charsLeft = limits.maxIndexChars
            scenes.forEachIndexed { index, slide ->
                val page = pageOf(slide.scene, resolver, engine, index, slide.part, linkOf, charsLeft)
                charsLeft -= page.text.text.length
                pages += page.text
                links += page.links
                semantics += semanticTextOf(slide.scene)
                titles += titleOf(slide.scene)
            }
            return SlideTextIndex(pages, links, semantics, titles)
        }

        /** Resolves one slide at a time so the bounded scene window can evict earlier ones as it walks. */
        fun fromDeck(
            pkg: PptxPackage,
            presentation: PresentationInfo,
            count: Int,
            sceneAt: (Int) -> SlideScene?,
            resolver: TextStyleResolver,
            engine: TextLayoutEngine,
            checkCancellation: () -> Unit = {},
            limits: PptxLimits = PptxLimits(),
        ): SlideTextIndex {
            val slideOfPart = presentation.slides
                .mapIndexedNotNull { index, ref ->
                    pkg.targetOf(pkg.mainPart, ref.relationshipId)?.let { it.lowercase() to index }
                }
                .toMap()
            val linkOf: (Int, String, String?, String?) -> PdfLinkTarget? = { index, part, relationshipId, action ->
                val rel = relationshipId?.let { pkg.relationship(part, it) }
                slideLinkTarget(
                    action = action,
                    externalUrl = rel?.takeIf { it.external }?.target,
                    targetSlide = rel?.takeUnless { it.external }?.target?.let { slideOfPart[it.lowercase()] },
                    current = index,
                    slideCount = count,
                )
            }
            val pages = ArrayList<PdfPageText>(count)
            val links = ArrayList<List<PdfPageLink>>(count)
            val semantics = ArrayList<String>(count)
            val titles = ArrayList<String>(count)
            var charsLeft = limits.maxIndexChars
            for (index in 0 until count) {
                checkCancellation()
                val slide = sceneAt(index)
                if (slide == null) {
                    pages.add(PdfPageText("", FloatArray(0)))
                    links.add(emptyList())
                    semantics.add("")
                    titles.add("")
                    continue
                }
                if (charsLeft > 0) TableLayout.layout(slide.scene, resolver, engine)
                val page = pageOf(slide.scene, resolver, engine, index, slide.part, linkOf, charsLeft, checkCancellation)
                charsLeft -= page.text.text.length
                pages.add(page.text)
                links.add(page.links)
                semantics.add(semanticTextOf(slide.scene))
                titles.add(titleOf(slide.scene))
            }
            return SlideTextIndex(pages, links, semantics, titles)
        }

        /** The text find-in-file sees, page by page, so the search index and the screen never disagree. */
        fun indexPages(
            open: () -> InputStream,
            measurer: TextMeasurer,
            newParserFactory: () -> XmlPullParser,
            limits: PptxLimits = PptxLimits(),
            includeHidden: Boolean = false,
        ): List<String> = indexPages(open, TextLayoutEngine(measurer), newParserFactory, limits, includeHidden)

        /** Library-index entry point: same engine shape as the viewer, driven by the app's font catalogue. */
        fun indexPages(
            open: () -> InputStream,
            fonts: FontCatalog,
            newParserFactory: () -> XmlPullParser,
            limits: PptxLimits = PptxLimits(),
            includeHidden: Boolean = false,
        ): List<String> = indexPages(open, engine(fonts), newParserFactory, limits, includeHidden)

        private fun indexPages(
            open: () -> InputStream,
            engine: TextLayoutEngine,
            newParserFactory: () -> XmlPullParser,
            limits: PptxLimits,
            includeHidden: Boolean,
        ): List<String> {
            val pkg = PptxPackage.read(open, mediaDir = null, limits = limits, newParserFactory = newParserFactory)
            val main = pkg.mainPart
            val bytes = pkg.part(main) ?: throw PptxFormatException("$main is missing")
            val presentation = PresentationParser(newParserFactory, limits).parse(bytes, main)
            val scenes = StyleResolver(newParserFactory, limits).lazyScenes(pkg, presentation)
            val resolver = TextStyleResolver(limits)
            val pages = fromDeck(
                pkg,
                presentation,
                scenes.count,
                scenes::scene,
                resolver,
                engine,
                {},
                limits,
            ).pages
            val hidden = if (includeHidden) emptySet() else scenes.hidden
            return pages.mapIndexed { index, page -> if (index in hidden) "" else page.text }
        }

        /** Prefer the `title`/`ctrTitle` placeholder; callers keep a first-line fallback when none is set. */
        private fun titleOf(scene: Scene): String {
            fun textOf(node: SceneNode): String = when (node) {
                is SceneShape -> node.text?.paragraphs.orEmpty().joinToString(" ") { paragraph ->
                    paragraph.runs.joinToString("") { it.text }
                }.trim()
                is SceneGroup -> node.children.firstNotNullOfOrNull { child ->
                    textOf(child).takeIf { it.isNotEmpty() }
                }.orEmpty()
                else -> ""
            }
            fun walk(nodes: List<SceneNode>): String {
                for (node in nodes) {
                    if (node is SceneShape && isTitlePlaceholder(node.placeholder)) {
                        textOf(node).takeIf { it.isNotEmpty() }?.let { return it }
                    }
                    if (node is SceneGroup) walk(node.children).takeIf { it.isNotEmpty() }?.let { return it }
                }
                return ""
            }
            return walk(scene.nodes)
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
                    is SceneGraphicFrame -> {
                        if (node.kind in ACCESSIBLE_GRAPHIC_KINDS) out += descr
                        if (node.kind == GraphicKind.SMART_ART) node.smartArt?.nodeText?.let(out::addAll)
                    }
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
            maxChars: Int = Int.MAX_VALUE,
            checkCancellation: () -> Unit = {},
        ): SlidePage {
            if (maxChars <= 0) return SlidePage(PdfPageText("", FloatArray(0)), emptyList())
            val placed = ArrayList<PlacedLayout>()
            collectNodes(scene.nodes, scene, resolver, engine, placed, slidePart, Affine.IDENTITY)
            val ordered = readingOrder(scene.size, placed)
            val links = ArrayList<PdfPageLink>()
            collectNodeLinks(scene.nodes, scene, Affine.IDENTITY, slideIndex, slidePart, linkOf, links)
            val text = StringBuilder(minOf(maxChars, 256))
            val boxes = FloatBuffer()
            val breaks = BitSet()
            for (item in ordered.withIndex()) {
                checkCancellation()
                if (text.length >= maxChars) break
                if (item.index > 0 && text.isNotEmpty() && text.last() != '\n') {
                    breaks.set(text.length)
                    appendChar(text, boxes, '\n', Float.NaN, Float.NaN, Float.NaN, Float.NaN)
                }
                appendFrame(item.value, scene.size, text, boxes, breaks, slideIndex, linkOf, links, maxChars, checkCancellation)
            }
            while (text.isNotEmpty() && text.last() == '\n') {
                val i = text.length - 1
                breaks.clear(i)
                text.deleteCharAt(i)
                boxes.removeLastChar()
            }
            if (text.isEmpty()) return SlidePage(PdfPageText("", FloatArray(0)), links)
            return SlidePage(PdfPageText(text.toString(), boxes.toArray(), breaks), links)
        }

        private fun collectNodeLinks(
            nodes: List<SceneNode>,
            scene: Scene,
            transform: Affine,
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
                        val local = if (node is SceneShape) {
                            val shape = TextShapeTransform.of(node.transform)
                            Affine.forShape(
                                frame,
                                rotation = shape.rotationDeg * TextShapeTransform.SIXTIETHS_PER_DEGREE,
                                flipH = shape.flipH,
                                flipV = shape.flipV,
                            )
                        } else {
                            Affine.forShape(frame)
                        }
                        boxOf(transform.then(local), 0.0, 0.0, frame.width, frame.height, scene.size)?.let {
                            out += PdfPageLink(NormRect(it[0], it[1], it[2], it[3]), target)
                        }
                    }
                }
                if (node is SceneGroup) {
                    collectNodeLinks(
                        node.children,
                        scene,
                        transform.then(groupTransform(node)),
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
            transform: Affine,
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
                        transform.then(groupTransform(node)),
                    )
                    is SceneShape -> placedShape(scene, node, resolver, engine, transform, slidePart)?.let(out::add)
                    is SceneGraphicFrame -> placedTable(node, transform, out, slidePart)
                    else -> Unit
                }
            }
        }

        private fun groupTransform(group: SceneGroup): Affine {
            val frame = group.frame ?: return Affine.IDENTITY
            return Affine.forShape(frame, group.childFrame, group.rotation, group.flipH, group.flipV)
        }

        private fun placedShape(
            scene: Scene,
            shape: SceneShape,
            resolver: TextStyleResolver,
            engine: TextLayoutEngine,
            transform: Affine,
            slidePart: String,
        ): PlacedLayout? {
            val frame = shape.frame ?: return null
            if (frame.width <= 0.0 || frame.height <= 0.0) return null
            val key = shape.text
            val cached = key?.let { scene.textLayouts[it] } as? CachedTextLayout
            val body = cached?.body ?: shape.textBody?.let { node ->
                recover<TextBody?>(null) { resolver.resolve(node, shape.text?.textSources ?: TextStyleSources()) }
            } ?: return null
            if (!body.hasText()) return null
            val layout = cached?.layout ?: recover<TextFrameLayout?>(null) {
                engine.layout(body, frame.width, frame.height, TextShapeTransform.of(shape.transform))
            } ?: return null
            if (layout.lines.isEmpty()) return null
            if (cached == null && key != null) scene.textLayouts[key] = CachedTextLayout(body, layout)
            val insets = body.properties.insets
            val placed = transform.then(
                Affine.forShape(
                    frame,
                    rotation = layout.orientation.rotationDeg * TextShapeTransform.SIXTIETHS_PER_DEGREE,
                ),
            )
            return PlacedLayout(
                layout,
                placed,
                insets.left,
                insets.top,
                paragraphTexts(body),
                shape.sourcePart ?: slidePart,
            )
        }

        private fun placedTable(
            node: SceneGraphicFrame,
            transform: Affine,
            out: MutableList<PlacedLayout>,
            slidePart: String,
        ) {
            val table = node.table ?: return
            val result = table.layout ?: return
            val origin = node.frame ?: return
            for ((key, rect) in result.cellFrames.entries.sortedWith(compareBy({ it.key.first }, { it.key.second }))) {
                val cell = table.rows.getOrNull(key.first)?.cells?.getOrNull(key.second) ?: continue
                val layout = cell.textLayout ?: continue
                val body = cell.resolvedText ?: TextBody()
                val texts = paragraphTexts(body)
                val placed = transform.then(Affine.translate(origin.x + rect.x, origin.y + rect.y))
                val cellOrigin = TableLayout.cellTextOrigin(cell, body, rect, layout)
                out += PlacedLayout(
                    layout,
                    placed,
                    cellOrigin.x,
                    cellOrigin.y,
                    texts,
                    node.sourcePart ?: slidePart,
                )
            }
        }

        private fun appendFrame(
            placed: PlacedLayout,
            slide: SceneSize,
            text: StringBuilder,
            boxes: FloatBuffer,
            breaks: BitSet,
            slideIndex: Int,
            linkOf: (Int, String, String?, String?) -> PdfLinkTarget?,
            links: MutableList<PdfPageLink>,
            maxChars: Int,
            checkCancellation: () -> Unit,
        ) {
            if (slide.width <= 0.0 || slide.height <= 0.0) return
            for ((lineIndex, line) in placed.layout.lines.withIndex()) {
                for (run in line.runs) {
                    val hyperlink = run.properties.hyperlink ?: continue
                    val target = linkOf(slideIndex, placed.sourcePart, hyperlink.relationshipId, hyperlink.action) ?: continue
                    val box = boxOf(
                        placed.transform,
                        placed.originX + run.leftPt * EMU_PER_POINT,
                        placed.originY + line.topPt * EMU_PER_POINT,
                        placed.originX + run.rightPt * EMU_PER_POINT,
                        placed.originY + (line.topPt + line.heightPt) * EMU_PER_POINT,
                        slide,
                    ) ?: continue
                    links += PdfPageLink(NormRect(box[0], box[1], box[2], box[3]), target)
                }
                if (lineIndex > 0 && text.length < maxChars) {
                    breaks.set(text.length)
                    appendChar(text, boxes, '\n', Float.NaN, Float.NaN, Float.NaN, Float.NaN)
                }
                val paraText = placed.paragraphTexts.getOrNull(line.paragraph).orEmpty()
                for (charBox in line.characters) {
                    if (text.length >= maxChars) return
                    checkCancellation()
                    val s = charBox.start.coerceIn(0, paraText.length)
                    val e = charBox.end.coerceIn(s, paraText.length)
                    val box = boxOf(
                        placed.transform,
                        placed.originX + charBox.leftPt * EMU_PER_POINT,
                        placed.originY + charBox.topPt * EMU_PER_POINT,
                        placed.originX + charBox.rightPt * EMU_PER_POINT,
                        placed.originY + charBox.bottomPt * EMU_PER_POINT,
                        slide,
                    ) ?: continue
                    if (e > s) {
                        for (i in s until e) {
                            if (text.length >= maxChars) return
                            appendChar(text, boxes, paraText[i], box[0], box[1], box[2], box[3])
                        }
                    } else {
                        appendChar(text, boxes, ' ', box[0], box[1], box[2], box[3])
                    }
                }
            }
        }

        private fun appendChar(
            text: StringBuilder,
            boxes: FloatBuffer,
            ch: Char,
            l: Float,
            t: Float,
            r: Float,
            b: Float,
        ) {
            text.append(ch)
            boxes.add(l, t, r, b)
        }

        /** Shapes on the same visual line read left to right, even when their tops differ by a few pixels. */
        private fun readingOrder(slide: SceneSize, placed: List<PlacedLayout>): List<PlacedLayout> {
            val byTop = placed.sortedBy { it.top }
            val rowTolerance = slide.height * READING_ORDER_ROW_FRACTION
            val out = ArrayList<PlacedLayout>(byTop.size)
            var start = 0
            while (start < byTop.size) {
                val top = byTop[start].top
                var end = start + 1
                while (end < byTop.size && byTop[end].top - top <= rowTolerance) end++
                out += byTop.subList(start, end).sortedBy { it.left }
                start = end
            }
            return out
        }

        private fun normX(v: Double, w: Double): Float = (v / w).toFloat().coerceIn(0f, 1f)
        private fun normY(v: Double, h: Double): Float = (v / h).toFloat().coerceIn(0f, 1f)

        /** Normalised bounding box of a local rectangle after the group and shape transforms. */
        private fun boxOf(
            transform: Affine,
            left: Double,
            top: Double,
            right: Double,
            bottom: Double,
            slide: SceneSize,
        ): FloatArray? {
            val sw = slide.width.takeIf { it > 0.0 } ?: return null
            val sh = slide.height.takeIf { it > 0.0 } ?: return null
            val a = transform.map(left, top)
            val b = transform.map(right, top)
            val c = transform.map(left, bottom)
            val d = transform.map(right, bottom)
            val l = normX(minOf(a.x, b.x, c.x, d.x), sw)
            val r = normX(maxOf(a.x, b.x, c.x, d.x), sw)
            val t = normY(minOf(a.y, b.y, c.y, d.y), sh)
            val bo = normY(maxOf(a.y, b.y, c.y, d.y), sh)
            if (r <= l || bo <= t) return null
            return floatArrayOf(l, t, r, bo)
        }

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

        private const val EMU_PER_POINT = 12_700.0

        private const val READING_ORDER_ROW_FRACTION = 0.02
    }

    private class FloatBuffer {
        private var data = FloatArray(1024)
        private var size = 0

        fun add(l: Float, t: Float, r: Float, b: Float) {
            if (size + 4 > data.size) data = data.copyOf(data.size * 2)
            data[size++] = l
            data[size++] = t
            data[size++] = r
            data[size++] = b
        }

        fun removeLastChar() {
            if (size >= 4) size -= 4
        }

        fun toArray(): FloatArray = data.copyOf(size)
    }

    private class SlidePage(val text: PdfPageText, val links: List<PdfPageLink>)

    private data class PlacedLayout(
        val layout: TextFrameLayout,
        val transform: Affine,
        val originX: Double,
        val originY: Double,
        val paragraphTexts: List<String>,
        val sourcePart: String,
    ) {
        val top: Double get() = transform.f
        val left: Double get() = transform.e
    }
}
