package io.github.pixelrick420.papyrus.viewer.pptx

import android.util.Xml
import io.github.pixelrick420.papyrus.viewer.pptx.chart.ChartBuilder
import io.github.pixelrick420.papyrus.viewer.pptx.chart.ChartDrawing
import io.github.pixelrick420.papyrus.viewer.pptx.chart.ChartParser
import io.github.pixelrick420.papyrus.viewer.pptx.chart.PptxChart
import io.github.pixelrick420.papyrus.viewer.pptx.chart.ChartStyle
import io.github.pixelrick420.papyrus.viewer.pptx.diagram.SmartArtDrawing
import io.github.pixelrick420.papyrus.viewer.pptx.diagram.SmartArtStyle
import io.github.pixelrick420.papyrus.viewer.pptx.table.TableStyles
import io.github.pixelrick420.papyrus.viewer.pptx.table.TableStyleSheet
import io.github.pixelrick420.papyrus.viewer.pptx.text.TextStyleSources
import org.xmlpull.v1.XmlPullParser

/**
 * Resolves PowerPoint's inheritance once per slide: background, draw order, placeholders, body and text properties.
 * The result is a [SlideScene] every later pass can paint without consulting a master, layout or theme again.
 */
internal class StyleResolver(
    private val newParserFactory: () -> XmlPullParser = { Xml.newPullParser() },
    private val limits: PptxLimits = PptxLimits(),
) {

    private val shapes = ShapeTreeParser(newParserFactory, limits)
    private val layouts = MasterLayoutParser(newParserFactory, limits)
    private val reader = OoxmlReader(newParserFactory, OoxmlNamespace.CORE, limits)
    private val diagramReader = OoxmlReader(
        newParserFactory,
        OoxmlNamespace.CORE + OoxmlNamespace.DSP + OoxmlNamespace.DIAGRAM,
        limits,
    )

    fun resolveAll(pkg: PptxPackage, presentation: PresentationInfo): DeckScenes {
        val provider = lazyScenes(pkg, presentation)
        return DeckScenes(List(provider.count) { index -> provider.scene(index)!! })
    }

    /** Masters, layouts, themes and the slide list are read once; slide scenes are resolved on demand. */
    fun lazyScenes(pkg: PptxPackage, presentation: PresentationInfo): LazyDeckScenes {
        val size = SceneSize(presentation.size.widthEmu.toDouble(), presentation.size.heightEmu.toDouble())
        val templates = Templates(pkg, layouts.parse(pkg), presentation.defaultTextStyle, shapes)
        val tableStyles = readTableStyles(pkg, ColorModel())
        val parts = shapes.parts(pkg, presentation)
        return LazyDeckScenes(
            count = parts.size,
            size = size,
            initialHidden = shapes.hiddenSlides(pkg, parts),
            factory = { index ->
                val part = parts[index]
                val scene = slide(pkg, part, size, presentation.firstSlideNumber + index, templates)
                TableStyles.apply(scene.scene, tableStyles)
                scene
            },
        )
    }

    private fun readTableStyles(pkg: PptxPackage, colors: ColorModel): TableStyleSheet {
        val part = pkg.part("ppt/tableStyles.xml")
            ?: pkg.relationshipsOf(pkg.mainPart).firstOrNull {
                it.type.substringAfterLast('/').equals("tableStyles", ignoreCase = true)
            }?.let { pkg.targetOf(pkg.mainPart, it.id)?.let(pkg::part) }
        val root = part?.let { reader.readTree(it.inputStream(), "ppt/tableStyles.xml") }
        return TableStyles.read(root, limits, colors)
    }

    private fun slide(
        pkg: PptxPackage,
        part: String,
        size: SceneSize,
        number: Int,
        templates: Templates,
    ): SlideScene {
        val tree = recover<ParsedShapeTree?>(null) { shapes.parsePart(pkg, part) }
            ?: return SlideScene(part, Scene(size, emptyList()), incomplete = true)
        val layout = pkg.relationshipsOf(part)
            .firstOrNull { it.type.substringAfterLast('/').equals("slideLayout", ignoreCase = true) }
            ?.let { pkg.targetOf(part, it.id) }
            ?.let(templates::layout)
        val context = templates.context(layout)
        val budget = TextBudget(limits.maxTextChars)
        val nodes = buildList {
            if (layout?.showMasterShapes != false) {
                addAll(resolveNodes(context.masterTree?.nodes.orEmpty(), pkg, context, number, budget, 0, skipPlaceholders = true))
            }
            if (tree.showMasterShapes) {
                addAll(resolveNodes(context.layoutTree?.nodes.orEmpty(), pkg, context, number, budget, 0, skipPlaceholders = true))
            }
            addAll(resolveNodes(tree.nodes, pkg, context, number, budget, 0))
        }
        val background = backgroundOf(tree.background, context)
        return SlideScene(part, Scene(size, nodes, background), tree.incomplete || budget.truncated, tree.hidden)
    }

    private fun resolveNodes(
        nodes: List<SceneNode>,
        pkg: PptxPackage,
        context: LayoutContext,
        number: Int,
        budget: TextBudget,
        depth: Int,
        skipPlaceholders: Boolean = false,
    ): List<SceneNode> {
        val resolved = ArrayList<SceneNode>(nodes.size)
        for (node in nodes) {
            if (node.hidden) continue
            if (skipPlaceholders && templateOf(node) != null) continue
            when (node) {
                is SceneGroup -> resolved += SceneGroup(
                    id = node.id,
                    name = node.name,
                    frame = node.frame,
                    hidden = node.hidden,
                    description = node.description,
                    decorative = node.decorative,
                    childFrame = node.childFrame,
                    children = if (depth < limits.maxGroupDepth) {
                        resolveNodes(node.children, pkg, context, number, budget, depth + 1, skipPlaceholders)
                    } else {
                        emptyList()
                    },
                    hyperlink = node.hyperlink,
                    rotation = node.rotation,
                    flipH = node.flipH,
                    flipV = node.flipV,
                    sourcePart = node.sourcePart,
                )
                is SceneShape -> resolved += resolveShape(node, context, number, budget)
                is ScenePicture -> resolved += resolvePicture(node, context)
                is SceneConnector -> resolved += resolveConnector(node, context)
                is SceneGraphicFrame -> resolved += resolveGraphicFrame(node, pkg, context)
            }
        }
        return resolved
    }

    private fun resolveShape(shape: SceneShape, context: LayoutContext, number: Int, budget: TextBudget): SceneShape {
        val chain = templateChain(shape.placeholder, context)
        val identity = chain.firstOrNull()?.identity ?: shape.placeholder
        return SceneShape(
            id = shape.id,
            name = shape.name,
            frame = inheritFrame(
                listOf(shape.transform) + chain.map { it.transform },
                listOf(shape.frame) + chain.map { it.frame },
            ),
            hidden = shape.hidden,
            description = shape.description,
            decorative = shape.decorative,
            placeholder = shape.placeholder,
            text = textOf(
                shape.textBody,
                listOfNotNull(shape.textBody?.child(OoxmlNamespace.A, "bodyPr")) + chain.mapNotNull {
                    it.textBody?.child(OoxmlNamespace.A, "bodyPr")
                },
                listOfNotNull(shape.textBody?.child(OoxmlNamespace.A, "lstStyle")) + chain.mapNotNull {
                    it.textBody?.child(OoxmlNamespace.A, "lstStyle")
                },
                masterStyleOf(identity, context),
                context,
                number,
                budget,
            ),
            preset = shape.preset,
            customGeometry = shape.customGeometry,
            textBody = shape.textBody,
            style = shape.style,
            hyperlink = shape.hyperlink,
            transform = shape.transform,
            properties = shape.properties,
            format = formatOf(
                listOf(shape.properties to shape.style) + chain.map { it.properties to it.style },
                context,
            ),
            sourcePart = shape.sourcePart,
        )
    }

    private fun resolvePicture(picture: ScenePicture, context: LayoutContext): ScenePicture {
        val chain = templateChain(picture.placeholder, context)
        return ScenePicture(
            id = picture.id,
            name = picture.name,
            frame = inheritFrame(
                listOf(picture.transform) + chain.map { it.transform },
                listOf(picture.frame) + chain.map { it.frame },
            ),
            hidden = picture.hidden,
            description = picture.description,
            decorative = picture.decorative,
            relationshipId = picture.relationshipId,
            text = picture.text,
            style = picture.style,
            hyperlink = picture.hyperlink,
            placeholder = picture.placeholder,
            transform = picture.transform,
            properties = picture.properties,
            format = formatOf(
                listOf(picture.properties to picture.style) + chain.map { it.properties to it.style },
                context,
            ),
            source = picture.source,
            mask = picture.mask,
            customGeometry = picture.customGeometry,
            sourcePart = picture.sourcePart,
        )
    }

    private fun resolveConnector(connector: SceneConnector, context: LayoutContext): SceneConnector = SceneConnector(
        id = connector.id,
        name = connector.name,
        frame = connector.frame,
        hidden = connector.hidden,
        description = connector.description,
        decorative = connector.decorative,
        start = connector.start,
        end = connector.end,
        preset = connector.preset,
        customGeometry = connector.customGeometry,
        style = connector.style,
        hyperlink = connector.hyperlink,
        transform = connector.transform,
        properties = connector.properties,
        format = formatOf(listOf(connector.properties to connector.style), context),
        sourcePart = connector.sourcePart,
    )

    private fun resolveGraphicFrame(
        frame: SceneGraphicFrame,
        pkg: PptxPackage,
        context: LayoutContext,
    ): SceneGraphicFrame {
        return when (frame.kind) {
            GraphicKind.CHART -> chartOf(frame, pkg, context)?.let { copyFrame(frame, chart = it) }
            GraphicKind.SMART_ART -> smartArtOf(frame, pkg, context)?.let { copyFrame(frame, smartArt = it) }
            else -> null
        } ?: frame
    }

    private fun copyFrame(
        frame: SceneGraphicFrame,
        chart: ChartDrawing? = null,
        smartArt: SmartArtDrawing? = null,
    ): SceneGraphicFrame = SceneGraphicFrame(
        id = frame.id,
        name = frame.name,
        frame = frame.frame,
        hidden = frame.hidden,
        description = frame.description,
        decorative = frame.decorative,
        kind = frame.kind,
        relationshipId = frame.relationshipId,
        hyperlink = frame.hyperlink,
        table = frame.table,
        chart = chart,
        smartArt = smartArt,
        fallbackPicture = frame.fallbackPicture,
        sourcePart = frame.sourcePart,
    )

    private fun chartOf(frame: SceneGraphicFrame, pkg: PptxPackage, context: LayoutContext): ChartDrawing? {
        val source = frame.sourcePart ?: return null
        val relationship = frame.relationshipId ?: return null
        val part = pkg.targetOf(source, relationship) ?: return null
        val bytes = pkg.part(part) ?: return null
        val tree = recover<XNode?>(null) { reader.readTree(bytes.inputStream(), part) } ?: return null
        val chart = recover<PptxChart?>(null) { ChartParser.parse(tree, limits) } ?: return null
        val bounds = frame.frame?.let { SceneRect(0.0, 0.0, it.width, it.height) } ?: return null
        if (bounds.width <= 0.0 || bounds.height <= 0.0) return null
        return ChartBuilder.build(chart, bounds, chartStyle(context), frame.description)
    }

    private fun smartArtOf(frame: SceneGraphicFrame, pkg: PptxPackage, context: LayoutContext): SmartArtDrawing? {
        val source = frame.sourcePart ?: return null
        val modelRelation = frame.relationshipId ?: return null
        val modelPart = pkg.targetOf(source, modelRelation) ?: return null
        val model = pkg.part(modelPart)?.let { bytes ->
            recover<XNode?>(null) { diagramReader.readTree(bytes.inputStream(), modelPart) }
        }
        val nodeText = model?.let(::dataModelText).orEmpty()
        val origin = frame.frame ?: return null
        val style = smartArtStyle(context)
        val drawingPart = pkg.relationshipsOf(modelPart)
            .firstOrNull { it.type.substringAfterLast('/').equals("diagramDrawing", ignoreCase = true) }
            ?.let { pkg.targetOf(modelPart, it.id) }
            ?: return SmartArtDrawing.fallback(origin, style, nodeText)
        val bytes = pkg.part(drawingPart) ?: return SmartArtDrawing.fallback(origin, style, nodeText)
        val root = recover<XNode?>(null) { diagramReader.readTree(bytes.inputStream(), drawingPart) }
        return SmartArtDrawing.read(root, drawingPart, style, origin, nodeText)
    }

    private fun dataModelText(root: XNode): List<String> =
        root.child(OoxmlNamespace.DIAGRAM, "ptLst")
            ?.childrenOf(OoxmlNamespace.DIAGRAM, "pt")
            .orEmpty()
            .filterNot { it.attribute("type").equals("pres", ignoreCase = true) }
            .mapNotNull { point ->
                val body = point.child(OoxmlNamespace.DIAGRAM, "t") ?: return@mapNotNull null
                val text = body.childrenOf(OoxmlNamespace.A, "p").joinToString("\n") { paragraph ->
                    paragraph.childrenOf(OoxmlNamespace.A, "r")
                        .joinToString("") { it.child(OoxmlNamespace.A, "t")?.text.orEmpty() }
                }
                text.takeIf { it.isNotBlank() }
            }

    private fun chartStyle(context: LayoutContext): ChartStyle {
        fun scheme(slot: String, transform: ColorTransform? = null): Rgba? = context.colors.resolve(
            ColorValue(ColorSource.Scheme(slot), listOfNotNull(transform)),
        )
        val palette = buildList {
            for (slot in 1..6) scheme("accent$slot")?.let(::add)
            for (slot in 1..6) {
                scheme("accent$slot", ColorTransform(ColorTransformKind.LUM_MOD, DARKER_SERIES))?.let(::add)
            }
        }.ifEmpty { listOf(DEFAULT_CHART_ACCENT) }
        return ChartStyle(
            palette = palette,
            text = scheme("tx1") ?: Rgba.BLACK,
            grid = scheme("tx1", ColorTransform(ColorTransformKind.ALPHA, GRID_ALPHA)) ?: Rgba(217, 217, 217),
            axis = scheme("tx1", ColorTransform(ColorTransformKind.ALPHA, AXIS_ALPHA)) ?: Rgba(128, 128, 128),
            fillOf = { properties ->
                properties?.let(::fillElementOf)?.let {
                    Fills.paintOf(it, null, context.colors, limits.maxGradientStops)
                }
            },
            lineOf = { properties ->
                properties?.let { lineAcross(listOf(it to null), context) }?.takeIf { it.paint != null }
            },
        )
    }

    private fun smartArtStyle(context: LayoutContext): SmartArtStyle = SmartArtStyle(
        text = context.colors.resolve(ColorValue(ColorSource.Scheme("tx1"))) ?: Rgba.BLACK,
        formatOf = { properties, style -> formatOf(listOf(properties to style), context) },
    )

    private fun templateOf(node: SceneNode): Template? = when (node) {
        is SceneShape -> node.placeholder?.let {
            Template(it, node.frame, node.transform, node.properties, node.style, node.textBody)
        }
        is ScenePicture -> node.placeholder?.let {
            Template(it, node.frame, node.transform, node.properties, node.style, null)
        }
        else -> null
    }

    private fun templateChain(identity: PlaceholderIdentity?, context: LayoutContext): List<Template> {
        if (identity == null) return emptyList()
        val fromLayout = context.layoutPlaceholders.match(identity) ?: return emptyList()
        return listOfNotNull(fromLayout, context.masterPlaceholders.matchType(fromLayout.identity.type))
    }

    /** Attributes inherit one at a time, so a shape that overrides only `off` keeps the inherited `ext`. */
    private fun inheritFrame(transforms: List<XNode?>, frames: List<SceneRect?>): SceneRect? {
        val offsets = transforms.mapNotNull { it?.child(OoxmlNamespace.A, "off") }
        val extents = transforms.mapNotNull { it?.child(OoxmlNamespace.A, "ext") }
        val x = offsets.firstNotNullOfOrNull { it.attribute("x")?.toDoubleOrNull() }
        val y = offsets.firstNotNullOfOrNull { it.attribute("y")?.toDoubleOrNull() }
        val width = extents.firstNotNullOfOrNull { it.attribute("cx")?.toDoubleOrNull() }
        val height = extents.firstNotNullOfOrNull { it.attribute("cy")?.toDoubleOrNull() }
        if (x != null && y != null && width != null && height != null) return SceneRect(x, y, width, height)
        return frames.firstOrNull { it != null }
    }

    private fun formatOf(pairs: List<Pair<XNode?, XNode?>>, context: LayoutContext): SceneFormat? {
        val fill = pairs.firstNotNullOfOrNull { (properties, style) -> fillOf(properties, style, context) }
        val line = lineAcross(pairs, context)
        val effects = pairs.firstNotNullOfOrNull { (properties, style) -> effectsOf(properties, style, context) }
        return if (fill == null && line == null && effects == null) null else SceneFormat(fill, line, effects)
    }

    private fun fillOf(properties: XNode?, style: XNode?, context: LayoutContext): ScenePaint? {
        val explicit = properties?.let(::fillElementOf)
        if (explicit != null) return Fills.paintOf(explicit, null, context.colors, limits.maxGradientStops)
        val reference = style?.child(OoxmlNamespace.A, "fillRef") ?: return null
        val index = reference.attribute("idx")?.toIntOrNull() ?: return null
        if (index <= 0) return ScenePaint.None
        val element = context.theme?.formats?.fill(index) ?: return null
        return Fills.paintOf(
            element,
            placeholderColour(reference, context),
            context.colors,
            limits.maxGradientStops,
        )
    }

    private fun lineAcross(pairs: List<Pair<XNode?, XNode?>>, context: LayoutContext): SceneLine? {
        val sources = pairs.flatMap { (properties, style) -> lineSources(properties, style, context) }
        if (sources.isEmpty()) return null
        val width = sources.firstNotNullOfOrNull { (line) -> line.attribute("w")?.toDoubleOrNull() }
        val paint = sources.firstNotNullOfOrNull { (line, placeholder) ->
            fillElementOf(line)?.let {
                Fills.paintOf(it, placeholder, context.colors, limits.maxGradientStops)
            }
        }
        val dash = sources.firstNotNullOfOrNull { (line) -> line.child(OoxmlNamespace.A, "prstDash")?.attribute("val") }
        val cap = sources.firstNotNullOfOrNull { (line) -> line.attribute("cap") }?.let(StrokeCap::of)
            ?: StrokeCap.FLAT
        val join = sources.firstNotNullOfOrNull { (line) ->
            when {
                line.child(OoxmlNamespace.A, "miter") != null -> StrokeJoin.MITER
                line.child(OoxmlNamespace.A, "bevel") != null -> StrokeJoin.BEVEL
                line.child(OoxmlNamespace.A, "round") != null -> StrokeJoin.ROUND
                else -> null
            }
        } ?: StrokeJoin.ROUND
        val miterLimit = sources.firstNotNullOfOrNull { (line) ->
            line.child(OoxmlNamespace.A, "miter")?.attribute("lim")?.toDoubleOrNull()
        } ?: Strokes.DEFAULT_MITER_LIMIT
        val compound = sources.firstNotNullOfOrNull { (line) -> line.attribute("cmpd") }
            ?.let(StrokeCompound::of) ?: StrokeCompound.SINGLE
        val customDash = sources.firstNotNullOfOrNull { (line) ->
            line.child(OoxmlNamespace.A, "custDash")?.let { Strokes.customDash(it, limits.maxDashEntries) }?.ifEmpty { null }
        }.orEmpty()
        val headEnd = sources.firstNotNullOfOrNull { (line) -> arrow(line, "headEnd") } ?: ArrowEnd.NONE
        val tailEnd = sources.firstNotNullOfOrNull { (line) -> arrow(line, "tailEnd") } ?: ArrowEnd.NONE
        return SceneLine(
            widthEmu = width,
            paint = paint,
            dash = dash,
            cap = cap,
            join = join,
            miterLimit = miterLimit,
            compound = compound,
            customDash = customDash,
            headEnd = headEnd,
            tailEnd = tailEnd,
        )
    }

    private fun arrow(line: XNode, name: String): ArrowEnd? {
        val element = line.child(OoxmlNamespace.A, name) ?: return null
        return ArrowEnd(
            type = ArrowType.of(element.attribute("type")),
            width = ArrowSize.of(element.attribute("w")),
            length = ArrowSize.of(element.attribute("len")),
        )
    }

    private fun lineSources(
        properties: XNode?,
        style: XNode?,
        context: LayoutContext,
    ): List<Pair<XNode, Rgba?>> = buildList {
        properties?.child(OoxmlNamespace.A, "ln")?.let { add(it to null) }
        val reference = style?.child(OoxmlNamespace.A, "lnRef") ?: return@buildList
        val index = reference.attribute("idx")?.toIntOrNull() ?: return@buildList
        if (index <= 0) return@buildList
        context.theme?.formats?.line(index)?.let { add(it to placeholderColour(reference, context)) }
    }

    private fun effectsOf(properties: XNode?, style: XNode?, context: LayoutContext): SceneEffects? {
        val node = properties?.child(OoxmlNamespace.A, "effectLst") ?: effectFromStyle(style, context) ?: return null
        return Effects.of(node, context.colors)
    }

    private fun effectFromStyle(style: XNode?, context: LayoutContext): XNode? {
        val reference = style?.child(OoxmlNamespace.A, "effectRef") ?: return null
        val index = reference.attribute("idx")?.toIntOrNull() ?: return null
        if (index <= 0) return null
        val styleElement = context.theme?.formats?.effect(index) ?: return null
        return styleElement.child(OoxmlNamespace.A, "effectLst") ?: styleElement
    }

    private fun placeholderColour(reference: XNode, context: LayoutContext): Rgba? {
        val fill = fillElementOf(reference) ?: reference
        val colour = fill.children.firstNotNullOfOrNull(ColorModel::valueOf) ?: return null
        return context.colors.resolve(colour)
    }

    private fun backgroundOf(slideBackground: XNode?, context: LayoutContext): ScenePaint =
        paintOfBackground(slideBackground, context)
            ?: paintOfBackground(context.layoutTree?.background, context)
            ?: paintOfBackground(context.masterTree?.background, context)
            ?: themeBackground(context)

    private fun paintOfBackground(background: XNode?, context: LayoutContext): ScenePaint? {
        background ?: return null
        background.child(OoxmlNamespace.P, "bgPr")?.let { properties ->
            val fill = fillElementOf(properties) ?: return null
            return Fills.paintOf(fill, null, context.colors, limits.maxGradientStops)
        }
        val reference = background.child(OoxmlNamespace.P, "bgRef") ?: return null
        val index = reference.attribute("idx")?.toIntOrNull() ?: return null
        val element = context.theme?.formats?.background(index) ?: return null
        return Fills.paintOf(
            element,
            placeholderColour(reference, context),
            context.colors,
            limits.maxGradientStops,
        )
    }

    private fun themeBackground(context: LayoutContext): ScenePaint {
        val element = context.theme?.formats?.background(THEME_BACKGROUND)
        val placeholder = context.colors.resolve(ColorValue(ColorSource.Scheme("bg1")))
        return element?.let {
            Fills.paintOf(it, placeholder, context.colors, limits.maxGradientStops)
        } ?: ScenePaint.Solid(Rgba.WHITE)
    }

    private fun masterStyleOf(identity: PlaceholderIdentity?, context: LayoutContext): XNode? {
        val styles = context.textStyles ?: return null
        return when (canonicalType(identity?.type)) {
            "title" -> styles.titleStyle
            "body" -> styles.bodyStyle
            else -> styles.otherStyle
        }
    }

    private fun textOf(
        textBody: XNode?,
        bodyProperties: List<XNode>,
        listStyles: List<XNode>,
        masterStyle: XNode?,
        context: LayoutContext,
        number: Int,
        budget: TextBudget,
    ): SceneTextFrame? {
        textBody ?: return null
        val body = bodyPropertiesOf(bodyProperties)
        val chain = TextChain(listStyles, masterStyle, context.defaultTextStyle)
        val paragraphs = textBody.childrenOf(OoxmlNamespace.A, "p")
            .take(limits.maxParagraphs)
            .map { paragraphOf(it, chain, context, number, budget) }
        return SceneTextFrame(
            paragraphs = paragraphs,
            inset = body.inset,
            anchor = body.anchor,
            wrap = body.wrap,
            autofit = body.autofit,
            vertical = body.vertical,
            columns = body.columns,
            textSources = TextStyleSources(
                listStyles = listStyles,
                masterStyle = masterStyle,
                defaultTextStyle = context.defaultTextStyle,
                theme = context.theme,
                colors = context.colors,
            ),
        )
    }

    private fun bodyPropertiesOf(chain: List<XNode>): BodyProperties {
        fun attribute(name: String): String? = chain.firstNotNullOfOrNull { it.attribute(name) }
        val autofit = chain.firstNotNullOfOrNull { body ->
            body.children.firstOrNull { it.name.namespace == OoxmlNamespace.A && it.name.localName in AUTOFIT_TAGS }
        }
        val anchor = when (attribute("anchor")) {
            "ctr" -> TextAnchor.MIDDLE
            "b" -> TextAnchor.BOTTOM
            else -> TextAnchor.TOP
        }
        return BodyProperties(
            inset = SceneInsets(
                left = attribute("lIns")?.toDoubleOrNull() ?: HORIZONTAL_INSET,
                top = attribute("tIns")?.toDoubleOrNull() ?: VERTICAL_INSET,
                right = attribute("rIns")?.toDoubleOrNull() ?: HORIZONTAL_INSET,
                bottom = attribute("bIns")?.toDoubleOrNull() ?: VERTICAL_INSET,
            ),
            anchor = anchor,
            wrap = attribute("wrap") != "none",
            autofit = autofit,
            vertical = attribute("vert"),
            columns = attribute("numCol")?.toIntOrNull()?.coerceAtLeast(1) ?: 1,
        )
    }

    private fun paragraphOf(
        paragraph: XNode,
        chain: TextChain,
        context: LayoutContext,
        number: Int,
        budget: TextBudget,
    ): SceneParagraph {
        val properties = paragraph.child(OoxmlNamespace.A, "pPr")
        val level = (properties?.attribute("lvl")?.toIntOrNull() ?: 0).coerceIn(0, MAX_LEVEL - 1)
        val runs = runsOf(paragraph, properties, chain, level, context, number, budget)
        val endStyle = runStyleOf(
            chain.runSources(paragraph.child(OoxmlNamespace.A, "endParaRPr"), properties, level),
            context,
        )
        return SceneParagraph(
            runs = runs,
            level = level,
            style = paragraphStyleOf(chain.paragraphSources(properties, level)),
            endStyle = endStyle,
        )
    }

    private fun runsOf(
        paragraph: XNode,
        properties: XNode?,
        chain: TextChain,
        level: Int,
        context: LayoutContext,
        number: Int,
        budget: TextBudget,
    ): List<SceneRun> {
        val runs = ArrayList<SceneRun>()
        for (child in paragraph.children) {
            when {
                child.name.matches(OoxmlNamespace.A, "r") -> runs += runOf(
                    child.child(OoxmlNamespace.A, "t")?.text.orEmpty(),
                    child.child(OoxmlNamespace.A, "rPr"),
                    properties,
                    chain,
                    level,
                    context,
                    budget,
                )
                child.name.matches(OoxmlNamespace.A, "br") -> runs += runOf(
                    "\n",
                    child.child(OoxmlNamespace.A, "rPr"),
                    properties,
                    chain,
                    level,
                    context,
                    budget,
                )
                child.name.matches(OoxmlNamespace.A, "fld") -> runs += runOf(
                    fieldText(child, number),
                    child.child(OoxmlNamespace.A, "rPr"),
                    properties,
                    chain,
                    level,
                    context,
                    budget,
                )
            }
        }
        return runs
    }

    private fun runOf(
        text: String,
        runProperties: XNode?,
        paragraphProperties: XNode?,
        chain: TextChain,
        level: Int,
        context: LayoutContext,
        budget: TextBudget,
    ): SceneRun = SceneRun(
        budget.take(text),
        runStyleOf(chain.runSources(runProperties, paragraphProperties, level), context),
    )

    private fun runStyleOf(sources: List<XNode>, context: LayoutContext): SceneRunStyle {
        fun attribute(name: String): String? = sources.firstNotNullOfOrNull { it.attribute(name) }
        val fill = sources.firstNotNullOfOrNull { properties ->
            properties.children.firstOrNull { it.name.namespace == OoxmlNamespace.A && it.name.localName in FILL_TAGS }
        }
        val declared = sources.firstNotNullOfOrNull { properties ->
            properties.child(OoxmlNamespace.A, "latin")?.attribute("typeface")?.takeIf { it.isNotBlank() }
        }
        return SceneRunStyle(
            typeface = typefaceOf(declared, context.theme),
            sizePt = attribute("sz")?.toDoubleOrNull()?.div(100.0) ?: DEFAULT_SIZE_PT,
            bold = isSwitch(attribute("b")),
            italic = isSwitch(attribute("i")),
            underline = attribute("u")?.let { it != "none" } == true,
            strike = attribute("strike")?.let { it != "noStrike" } == true,
            caps = attribute("cap") == "all" || attribute("cap") == "small",
            color = fill?.let { solidColourOf(it, context) }
                ?: context.colors.resolve(ColorValue(ColorSource.Scheme("tx1"))),
            language = attribute("lang"),
            baseline = attribute("baseline")?.toDoubleOrNull()?.div(100_000.0) ?: 0.0,
        )
    }

    private fun solidColourOf(fill: XNode, context: LayoutContext): Rgba? {
        if (fill.name.localName == "noFill") return null
        return fill.children.firstNotNullOfOrNull(ColorModel::valueOf)?.let(context.colors::resolve)
    }

    private fun typefaceOf(declared: String?, theme: Theme?): String? = when {
        declared == null -> theme?.fonts?.minor?.latin
        declared.startsWith("+") -> theme?.fonts?.resolve(declared)
        else -> declared
    }

    private fun paragraphStyleOf(sources: List<XNode>): SceneParagraphStyle {
        fun attribute(name: String): String? = sources.firstNotNullOfOrNull { it.attribute(name) }
        val bullet = sources.firstNotNullOfOrNull { properties ->
            properties.children.firstOrNull { it.name.namespace == OoxmlNamespace.A && it.name.localName in BULLET_TAGS }
        }
        return SceneParagraphStyle(
            alignment = attribute("algn"),
            marginLeftEmu = attribute("marL")?.toDoubleOrNull(),
            indentEmu = attribute("indent")?.toDoubleOrNull(),
            lineSpacing = spacingOf(sources, "lnSpc"),
            spaceBefore = spacingOf(sources, "spcBef"),
            spaceAfter = spacingOf(sources, "spcAft"),
            bullet = when (bullet?.name?.localName) {
                "buChar" -> SceneBullet.Char(
                    char = bullet.attribute("char").orEmpty(),
                    font = sources.firstNotNullOfOrNull { it.child(OoxmlNamespace.A, "buFont")?.attribute("typeface") },
                )
                "buAutoNum" -> SceneBullet.AutoNumber(
                    scheme = bullet.attribute("type").orEmpty(),
                    startAt = bullet.attribute("startAt")?.toIntOrNull(),
                )
                "buNone" -> SceneBullet.None
                else -> null
            },
        )
    }

    private fun spacingOf(sources: List<XNode>, tag: String): SceneSpacing? {
        val node = sources.firstNotNullOfOrNull { it.child(OoxmlNamespace.A, tag) } ?: return null
        val percent = node.child(OoxmlNamespace.A, "spcPct")?.attribute("val")?.toDoubleOrNull()?.div(1000.0)
        val points = node.child(OoxmlNamespace.A, "spcPts")?.attribute("val")?.toDoubleOrNull()?.div(100.0)
        return SceneSpacing(percent, points)
    }

    private fun fieldText(field: XNode, number: Int): String =
        if (field.attribute("type") == "slidenum") number.toString() else field.child(OoxmlNamespace.A, "t")?.text.orEmpty()

    private companion object {
        const val THEME_BACKGROUND = 1001

        const val MAX_LEVEL = 9

        const val DEFAULT_SIZE_PT = 18.0

        const val HORIZONTAL_INSET = 91_440.0

        const val VERTICAL_INSET = 45_720.0

        const val DARKER_SERIES = 75_000

        const val GRID_ALPHA = 20_000

        const val AXIS_ALPHA = 55_000

        val DEFAULT_CHART_ACCENT = Rgba(68, 114, 196)

        val FILL_TAGS = setOf("noFill", "solidFill", "gradFill", "blipFill", "pattFill", "grpFill")

        val AUTOFIT_TAGS = setOf("noAutofit", "normAutofit", "spAutoFit")

        val BULLET_TAGS = setOf("buNone", "buChar", "buAutoNum", "buBlip")
    }
}

private class BodyProperties(
    val inset: SceneInsets,
    val anchor: TextAnchor,
    val wrap: Boolean,
    val autofit: XNode?,
    val vertical: String?,
    val columns: Int,
)

/** The paragraph- and run-property levels, in the order PowerPoint consults them, first definition wins. */
private class TextChain(
    private val listStyles: List<XNode>,
    private val masterStyle: XNode?,
    private val defaultTextStyle: XNode?,
) {

    fun paragraphSources(properties: XNode?, level: Int): List<XNode> = buildList {
        properties?.let(::add)
        for (style in levels) levelStyle(style, level)?.let(::add)
    }

    fun runSources(runProperties: XNode?, properties: XNode?, level: Int): List<XNode> = buildList {
        runProperties?.let(::add)
        properties?.child(OoxmlNamespace.A, "defRPr")?.let(::add)
        for (style in levels) levelStyle(style, level)?.child(OoxmlNamespace.A, "defRPr")?.let(::add)
    }

    private val levels: List<XNode>
        get() = listStyles + listOfNotNull(masterStyle, defaultTextStyle)

    private fun levelStyle(style: XNode, level: Int): XNode? =
        style.child(OoxmlNamespace.A, "lvl${level + 1}pPr")
}

private class TextBudget(private val max: Int) {

    private var used = 0

    var truncated = false
        private set

    fun take(text: String): String {
        if (text.isEmpty()) return text
        val room = max - used
        if (room <= 0) {
            truncated = true
            return ""
        }
        val kept = if (text.length <= room) text else text.substring(0, room).also { truncated = true }
        used += kept.length
        return kept
    }
}

private class Template(
    val identity: PlaceholderIdentity,
    val frame: SceneRect?,
    val transform: XNode?,
    val properties: XNode?,
    val style: XNode?,
    val textBody: XNode?,
)

private class Placeholders private constructor(
    private val byIdx: Map<Int, Template>,
    private val byType: Map<String, Template>,
) {

    fun match(identity: PlaceholderIdentity): Template? {
        if (identity.indexExplicit) byIdx[identity.idx]?.let { return it }
        return matchType(identity.type)
    }

    fun matchType(type: String?): Template? {
        type ?: return null
        byType[type]?.let { return it }
        val canonical = canonicalType(type)
        byType[canonical]?.let { return it }
        return when (canonical) {
            "title" -> byType["ctrTitle"]
            "body" -> byType["obj"] ?: byType["subTitle"]
            else -> null
        }
    }

    companion object {
        val EMPTY = Placeholders(emptyMap(), emptyMap())

        fun of(nodes: List<SceneNode>): Placeholders {
            val found = mutableListOf<Template>()
            collect(nodes, found)
            if (found.isEmpty()) return EMPTY
            val byIdx = LinkedHashMap<Int, Template>()
            val byType = LinkedHashMap<String, Template>()
            for (template in found) {
                byIdx.putIfAbsent(template.identity.idx, template)
                template.identity.type?.let { byType.putIfAbsent(it, template) }
            }
            return Placeholders(byIdx, byType)
        }

        private fun collect(nodes: List<SceneNode>, into: MutableList<Template>) {
            for (node in nodes) {
                when (node) {
                    is SceneShape -> node.placeholder?.let {
                        into += Template(it, node.frame, node.transform, node.properties, node.style, node.textBody)
                    }
                    is ScenePicture -> node.placeholder?.let {
                        into += Template(it, node.frame, node.transform, node.properties, node.style, null)
                    }
                    is SceneGroup -> collect(node.children, into)
                    else -> Unit
                }
            }
        }
    }
}

private class LayoutContext(
    val layout: SlideLayout?,
    val master: SlideMaster?,
    val layoutTree: ParsedShapeTree?,
    val masterTree: ParsedShapeTree?,
    val layoutPlaceholders: Placeholders,
    val masterPlaceholders: Placeholders,
    val colors: ColorModel,
    val theme: Theme?,
    val textStyles: MasterTextStyles?,
    val defaultTextStyle: XNode?,
) {
    companion object {
        val EMPTY = LayoutContext(
            layout = null,
            master = null,
            layoutTree = null,
            masterTree = null,
            layoutPlaceholders = Placeholders.EMPTY,
            masterPlaceholders = Placeholders.EMPTY,
            colors = ColorModel(),
            theme = null,
            textStyles = null,
            defaultTextStyle = null,
        )
    }
}

/** Masters and layouts are parsed once per package and resolved once per layout, however many slides name them. */
private class Templates(
    private val pkg: PptxPackage,
    private val layouts: MasterLayouts,
    private val defaultTextStyle: XNode?,
    private val shapes: ShapeTreeParser,
) {

    private val masterTrees = HashMap<String, ParsedShapeTree?>()
    private val layoutTrees = HashMap<String, ParsedShapeTree?>()
    private val contexts = HashMap<String, LayoutContext>()

    fun layout(part: String): SlideLayout? = layouts.layout(part)

    fun context(layout: SlideLayout?): LayoutContext {
        layout ?: return LayoutContext.EMPTY
        return contexts.getOrPut(layout.part.lowercase()) { build(layout) }
    }

    private fun build(layout: SlideLayout): LayoutContext {
        val master = layout.masterPart?.let(layouts::master)
        val layoutTree = layoutTrees.getOrPut(layout.part.lowercase()) {
            recover<ParsedShapeTree?>(null) { shapes.parsePart(pkg, layout.part, "sldLayout") }
        } ?: return LayoutContext.EMPTY
        val masterTree = master?.let {
            masterTrees.getOrPut(it.part.lowercase()) {
                recover<ParsedShapeTree?>(null) { shapes.parsePart(pkg, it.part, "sldMaster") }
            }
        }
        val theme = master?.theme
        val colorMap = layout.colorMapOverride ?: master?.colorMap ?: ColorMap()
        return LayoutContext(
            layout = layout,
            master = master,
            layoutTree = layoutTree,
            masterTree = masterTree,
            layoutPlaceholders = Placeholders.of(layoutTree.nodes),
            masterPlaceholders = Placeholders.of(masterTree?.nodes.orEmpty()),
            colors = ColorModel(theme?.colors, colorMap),
            theme = theme,
            textStyles = master?.textStyles,
            defaultTextStyle = defaultTextStyle,
        )
    }
}

/** A bounded window over a deck's resolved slides, so a large deck does not keep every scene in memory. */
internal class LazyDeckScenes internal constructor(
    val count: Int,
    val size: SceneSize,
    private val factory: (Int) -> SlideScene,
    private val capacity: Int = DEFAULT_CAPACITY,
    private val initialHidden: Set<Int> = emptySet(),
) {

    private val cache = object : LinkedHashMap<Int, SlideScene>(capacity, LOAD_FACTOR, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, SlideScene>?): Boolean = size > capacity
    }

    private val sawIncomplete = java.util.concurrent.atomic.AtomicBoolean(false)

    private val hiddenSet = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>().apply { addAll(initialHidden) }

    private val parsed = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()

    /** Hidden slides discovered so far, without parsing; grows as slides are resolved. */
    val hiddenSnapshot: Set<Int> get() = hiddenSet.toSet()

    /** Hidden slides for callers that need the complete set; parses remaining slides once. */
    val hidden: Set<Int> get() {
        if (parsed.size < count) repeat(count) { scene(it) }
        return hiddenSet.toSet()
    }

    /** Completes the parse when a caller needs the full hidden set without an index build. */
    fun ensureAllParsed() {
        for (index in 0 until count) if (!parsed.contains(index)) scene(index)
    }

    /** True once any slide resolved so far reported a dropped shape or part. */
    val incomplete: Boolean get() = sawIncomplete.get()

    fun scene(index: Int): SlideScene? {
        if (index !in 0 until count) return null
        synchronized(cache) { cache.get(index) }?.let { return it }
        // Resolve outside the monitor: parsing a slide can be slow, and other slides must stay readable.
        val resolved = factory(index)
        synchronized(cache) { cache.put(index, resolved) }
        parsed.add(index)
        if (resolved.hidden) hiddenSet.add(index)
        if (resolved.incomplete) sawIncomplete.set(true)
        return resolved
    }

    @Synchronized
    fun trimMemory(evictAll: Boolean) {
        if (evictAll) {
            cache.clear()
        } else {
            val entries = cache.entries.toList()
            entries.take((entries.size - capacity / 2).coerceAtLeast(0)).forEach { cache.remove(it.key) }
        }
    }

    @Synchronized
    fun clear() {
        cache.clear()
        parsed.clear()
        hiddenSet.clear()
        hiddenSet.addAll(initialHidden)
    }

    private companion object {
        const val DEFAULT_CAPACITY = 6

        const val LOAD_FACTOR = 0.75f
    }
}

private val TYPE_ALIASES = mapOf("ctrTitle" to "title", "subTitle" to "body", "obj" to "body")

private fun canonicalType(type: String?): String? = type?.let { TYPE_ALIASES[it] ?: it }

/** The real slide title is the `title`/`ctrTitle` placeholder, not the first line of flattened text. */
internal fun isTitlePlaceholder(placeholder: PlaceholderIdentity?): Boolean =
    placeholder?.type?.let { canonicalType(it) == "title" } ?: false

private fun fillElementOf(node: XNode): XNode? =
    node.children.firstOrNull { it.name.namespace == OoxmlNamespace.A && it.name.localName in FILL_TAGS }

private val FILL_TAGS = setOf("noFill", "solidFill", "gradFill", "blipFill", "pattFill", "grpFill")

private fun isSwitch(value: String?): Boolean = value == "1" || value.equals("true", ignoreCase = true)
