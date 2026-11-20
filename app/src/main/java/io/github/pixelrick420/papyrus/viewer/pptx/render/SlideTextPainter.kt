package io.github.pixelrick420.papyrus.viewer.pptx.render

import io.github.pixelrick420.papyrus.viewer.pptx.Affine
import io.github.pixelrick420.papyrus.viewer.pptx.CachedTextLayout
import io.github.pixelrick420.papyrus.viewer.pptx.Rgba
import io.github.pixelrick420.papyrus.viewer.pptx.Scene
import io.github.pixelrick420.papyrus.viewer.pptx.SceneGraphicFrame
import io.github.pixelrick420.papyrus.viewer.pptx.ScenePoint
import io.github.pixelrick420.papyrus.viewer.pptx.SceneRect
import io.github.pixelrick420.papyrus.viewer.pptx.SceneShape
import io.github.pixelrick420.papyrus.viewer.pptx.SceneTextFrame
import io.github.pixelrick420.papyrus.viewer.pptx.XNode
import io.github.pixelrick420.papyrus.viewer.pptx.chart.ChartDrawing
import io.github.pixelrick420.papyrus.viewer.pptx.diagram.SmartArtDrawing
import io.github.pixelrick420.papyrus.viewer.pptx.recover
import io.github.pixelrick420.papyrus.viewer.pptx.table.TableCell
import io.github.pixelrick420.papyrus.viewer.pptx.table.TableLayout
import io.github.pixelrick420.papyrus.viewer.pptx.text.BulletMarker
import io.github.pixelrick420.papyrus.viewer.pptx.text.GlyphRun as LayoutGlyphRun
import io.github.pixelrick420.papyrus.viewer.pptx.text.TextBody
import io.github.pixelrick420.papyrus.viewer.pptx.text.TextDecoration
import io.github.pixelrick420.papyrus.viewer.pptx.text.TextDecorationKind
import io.github.pixelrick420.papyrus.viewer.pptx.text.TextFrameLayout
import io.github.pixelrick420.papyrus.viewer.pptx.text.TextLayoutEngine
import io.github.pixelrick420.papyrus.viewer.pptx.text.TextLine
import io.github.pixelrick420.papyrus.viewer.pptx.text.TextOutline
import io.github.pixelrick420.papyrus.viewer.pptx.text.TextRunProperties
import io.github.pixelrick420.papyrus.viewer.pptx.text.TextShadow
import io.github.pixelrick420.papyrus.viewer.pptx.text.TextShapeTransform
import io.github.pixelrick420.papyrus.viewer.pptx.text.TextStyleResolver
import io.github.pixelrick420.papyrus.viewer.pptx.text.TextStyleSources
import kotlin.math.cos
import kotlin.math.sin

/**
 * Draws the text a [Scene] lays out: shapes and table cells that carry a text body. Everything is
 * positioned in the node's own frame, so the caller's transform places the whole block.
 */
internal class SlideTextPainter(
    private val resolver: TextStyleResolver,
    private val engine: TextLayoutEngine,
) {

    fun layoutTables(scene: Scene) {
        TableLayout.layout(scene, resolver, engine)
    }

    fun paintShape(scene: Scene, shape: SceneShape, out: MutableList<DrawOp>) {
        val frame = shape.frame?.takeIf { it.width > 0.0 && it.height > 0.0 } ?: return
        val cached = shape.text?.let { scene.textLayouts[it] } as? CachedTextLayout
        val body = cached?.body ?: bodyOf(shape.textBody, shape.text?.textSources) ?: return
        if (body.paragraphs.isEmpty()) return
        val layout = cached?.layout
            ?: layoutOf(scene, shape.text, body, frame, TextShapeTransform.of(shape.transform)) ?: return
        emit(
            layout = layout,
            transform = Affine.forShape(frame, rotation = layout.orientation.rotationDeg * SIXTIETHS_PER_DEGREE),
            originX = body.properties.insets.left,
            originY = body.properties.insets.top,
            box = SceneRect(0.0, 0.0, frame.width, frame.height),
            out = out,
        )
    }

    fun paintTable(node: SceneGraphicFrame, out: MutableList<DrawOp>) {
        val table = node.table ?: return
        val layout = table.layout ?: return
        val origin = node.frame ?: return
        for ((key, rect) in layout.cellFrames) {
            val cell = table.rows.getOrNull(key.first)?.cells?.getOrNull(key.second) ?: continue
            if (cell.horizontalMerge || cell.verticalMerge) continue
            val cellLayout = cell.textLayout ?: continue
            val body = cell.resolvedText ?: continue
            if (cellLayout.lines.isEmpty()) continue
            val cellOrigin = TableLayout.cellTextOrigin(cell, body, rect, cellLayout)
            emit(
                layout = cellLayout,
                transform = Affine.translate(origin.x + rect.x, origin.y + rect.y),
                originX = cellOrigin.x,
                originY = cellOrigin.y,
                box = SceneRect(0.0, 0.0, rect.width, rect.height),
                out = out,
            )
        }
    }

    fun paintChart(node: SceneGraphicFrame, drawing: ChartDrawing, out: MutableList<DrawOp>) {
        val origin = node.frame ?: return
        for (text in drawing.texts) {
            val rect = text.frame
            if (rect.width <= 0.0 || rect.height <= 0.0) continue
            val body = bodyOf(text.body) ?: continue
            if (body.paragraphs.isEmpty()) continue
            val layout = recover<TextFrameLayout?>(null) {
                engine.layout(body, rect.width, rect.height, TextShapeTransform.NONE)
            } ?: continue
            emit(
                layout = layout,
                transform = Affine.translate(origin.x + rect.x, origin.y + rect.y),
                originX = body.properties.insets.left,
                originY = body.properties.insets.top,
                box = SceneRect(0.0, 0.0, rect.width, rect.height),
                out = out,
            )
        }
    }

    fun paintSmartArt(drawing: SmartArtDrawing, out: MutableList<DrawOp>) {
        for (text in drawing.texts) {
            val frame = text.frame
            if (frame.width <= 0.0 || frame.height <= 0.0) continue
            val body = bodyOf(text.body) ?: continue
            if (body.paragraphs.isEmpty()) continue
            val layout = recover<TextFrameLayout?>(null) {
                engine.layout(body, frame.width, frame.height, TextShapeTransform.NONE)
            } ?: continue
            emit(
                layout = layout,
                transform = Affine.forShape(frame),
                originX = body.properties.insets.left,
                originY = body.properties.insets.top,
                box = SceneRect(0.0, 0.0, frame.width, frame.height),
                out = out,
            )
        }
    }

    private fun emit(
        layout: TextFrameLayout,
        transform: Affine,
        originX: Double,
        originY: Double,
        box: SceneRect,
        out: MutableList<DrawOp>,
    ) {
        val runs = ArrayList<DrawOp>()
        for (line in layout.lines) {
            for (run in line.runs) {
                if (run.text.isEmpty()) continue
                runs += runOps(displayRun(run, originX, originY, box))
            }
            bullet(line, originX, originY)?.let(runs::add)
        }
        if (runs.isEmpty()) return
        out += DrawOp.Save
        out += DrawOp.Concat(transform)
        out += runs
        out += DrawOp.Restore
    }

    private fun runOps(run: GlyphRun): List<DrawOp> {
        if (run.rotationDeg == 0.0) return listOf(DrawOp.DrawGlyphRun(run))
        val origin = ScenePoint(run.x.toDouble(), run.y.toDouble())
        val turn = Affine.about(origin, Affine.rotate(Math.toRadians(run.rotationDeg)))
        return listOf(DrawOp.Save, DrawOp.Concat(turn), DrawOp.DrawGlyphRun(run), DrawOp.Restore)
    }

    private fun displayRun(run: LayoutGlyphRun, originX: Double, originY: Double, box: SceneRect): GlyphRun {
        val baselineEmu = originY + run.baselinePt * EMU_PER_POINT
        val features = run.properties.features(run.sizePt)
        val paint = runPaint(run.properties, box) ?: PaintSpec.Solid(Rgba(0, 0, 0, 0))
        return GlyphRun(
            text = run.text,
            x = (originX + run.leftPt * EMU_PER_POINT).toFloat(),
            y = baselineEmu.toFloat(),
            paint = paint,
            size = (run.sizePt * EMU_PER_POINT).toFloat(),
            family = run.properties.latinTypeface,
            bold = run.font.bold,
            italic = run.font.italic,
            letterSpacing = run.properties.letterSpacingEm(run.sizePt),
            kerning = features.kerning,
            ligatures = features.ligatures,
            outline = outlineOf(run.properties.outline, box),
            shadow = shadowOf(run.properties.shadow),
            rules = run.decorations.mapNotNull { ruleOf(it, originX, baselineEmu, box) },
            font = run.font,
            rotationDeg = run.rotationDeg,
        )
    }

    private fun runPaint(properties: TextRunProperties, box: SceneRect): PaintSpec? = when (
        val fill = properties.fill
    ) {
        null -> PaintSpec.Solid(Rgba.BLACK)
        else -> PaintSpecs.of(fill, box)
    }

    private fun ruleOf(
        decoration: TextDecoration,
        originX: Double,
        baselineEmu: Double,
        box: SceneRect,
    ): TextRule? {
        if (decoration.rightPt <= decoration.leftPt) return null
        val paint = decoration.paint?.let { PaintSpecs.of(it, box) }
        return TextRule(
            kind = when (decoration.kind) {
                TextDecorationKind.UNDERLINE -> TextRuleKind.UNDERLINE
                TextDecorationKind.STRIKE -> TextRuleKind.STRIKE
            },
            style = ruleStyle(decoration.style),
            left = (originX + decoration.leftPt * EMU_PER_POINT).toFloat(),
            right = (originX + decoration.rightPt * EMU_PER_POINT).toFloat(),
            y = (baselineEmu + decoration.offsetPt * EMU_PER_POINT).toFloat(),
            thickness = (decoration.thicknessPt * EMU_PER_POINT).toFloat(),
            paint = paint,
        )
    }

    private fun ruleStyle(style: String): String {
        val heavy = if (style.endsWith("Heavy")) HEAVY_SUFFIX else ""
        return when {
            style.startsWith("wavyDouble") -> "wavyDouble"
            style.startsWith("wavyHeavy") -> "wavyHeavy"
            style.startsWith("wavy") -> "wavy"
            style.startsWith("sysDot") || style.startsWith("dotted") -> "dotted$heavy"
            style.startsWith("lgDash") -> "dashLong$heavy"
            style.startsWith("sysDash") || style.startsWith("dashLong") -> "dash$heavy"
            style.startsWith("dash") || style.startsWith("dotDash") || style.startsWith("dotDotDash") -> "dash$heavy"
            else -> style
        }
    }

    private fun outlineOf(outline: TextOutline?, box: SceneRect): TextOutlineSpec? {
        outline ?: return null
        if (outline.widthPt <= 0.0) return null
        val paint = PaintSpecs.of(outline.paint, box) ?: return null
        return TextOutlineSpec((outline.widthPt * EMU_PER_POINT).toFloat(), paint)
    }

    private fun shadowOf(shadow: TextShadow?): ShadowSpec? {
        shadow ?: return null
        val radians = Math.toRadians(shadow.directionDeg)
        return ShadowSpec(
            color = shadow.color,
            blur = (shadow.blurPt * EMU_PER_POINT).toFloat(),
            dx = (cos(radians) * shadow.distancePt * EMU_PER_POINT).toFloat(),
            dy = (sin(radians) * shadow.distancePt * EMU_PER_POINT).toFloat(),
        )
    }

    private fun bullet(line: TextLine, originX: Double, originY: Double): DrawOp? {
        val marker = line.bullet
        val text = when (marker) {
            is BulletMarker.Symbol -> marker.text
            is BulletMarker.Number -> marker.text
            else -> null
        }.orEmpty()
        if (text.isEmpty()) return null
        val sizePt = when (marker) {
            is BulletMarker.Symbol -> marker.sizePt
            is BulletMarker.Number -> marker.sizePt
            else -> 0.0
        }.takeIf { it > 0.0 } ?: line.runs.firstOrNull()?.sizePt ?: return null
        val color = when (marker) {
            is BulletMarker.Symbol -> marker.color
            is BulletMarker.Number -> marker.color
            else -> null
        }
        return DrawOp.DrawGlyphRun(
            GlyphRun(
                text = text,
                x = (originX + line.markerLeftPt * EMU_PER_POINT).toFloat(),
                y = (originY + line.baselinePt * EMU_PER_POINT).toFloat(),
                paint = PaintSpec.Solid(color ?: Rgba.BLACK),
                size = (sizePt * EMU_PER_POINT).toFloat(),
                family = (marker as? BulletMarker.Symbol)?.font,
            ),
        )
    }

    private fun bodyOf(textBody: XNode?, sources: TextStyleSources? = null): TextBody? {
        textBody ?: return null
        return recover<TextBody?>(null) { resolver.resolve(textBody, sources ?: TextStyleSources()) }
    }

    private fun layoutOf(
        scene: Scene,
        key: SceneTextFrame?,
        body: TextBody,
        frame: SceneRect,
        transform: TextShapeTransform,
    ): TextFrameLayout? {
        (key?.let { scene.textLayouts[it] } as? CachedTextLayout)?.let { return it.layout }
        (key?.let { scene.textLayouts[it] } as? TextFrameLayout)?.let { return it }
        val layout = recover<TextFrameLayout?>(null) {
            engine.layout(body, frame.width, frame.height, transform)
        } ?: return null
        if (key != null) scene.textLayouts[key] = CachedTextLayout(body, layout)
        return layout
    }

    private companion object {
        const val EMU_PER_POINT = 12_700.0
        const val SIXTIETHS_PER_DEGREE = 60_000.0
        const val HEAVY_SUFFIX = "Heavy"
    }
}
