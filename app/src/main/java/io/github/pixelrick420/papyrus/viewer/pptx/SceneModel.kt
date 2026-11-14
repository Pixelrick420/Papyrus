package io.github.pixelrick420.papyrus.viewer.pptx

/** A point in EMU. */
internal data class ScenePoint(val x: Double, val y: Double)

/** A width and height in EMU. */
internal data class SceneSize(val width: Double, val height: Double)

/** An offset and extent in EMU. */
internal data class SceneRect(val x: Double, val y: Double, val width: Double, val height: Double) {

    val right: Double get() = x + width

    val bottom: Double get() = y + height
}

/** A `a:hlinkClick` on a node's non-visual properties: the relationship and action it names. */
internal class SceneHyperlink(val relationshipId: String?, val action: String?)

/** A preset geometry: its `prst` name, its `a:avLst` adjust formulas and the `a:xfrm` flips. */
internal class ScenePreset(
    val preset: String?,
    val adjusts: Map<String, String> = emptyMap(),
    val flipH: Boolean = false,
    val flipV: Boolean = false,
)

/** A fill as the paint pass consumes it: a colour already resolved through the theme, or a gradient. */
internal sealed interface ScenePaint {
    class Solid(val color: Rgba) : ScenePaint

    object None : ScenePaint

    class Linear(val gradient: LinearGradient) : ScenePaint

    class Radial(val gradient: RadialGradient) : ScenePaint

    /** A picture or pattern fill, or a `rect`/`shape` path gradient still owned by a later part, held whole. */
    class Complex(val element: XNode, val placeholder: Rgba?) : ScenePaint
}

internal class SceneLine(
    val widthEmu: Double?,
    val paint: ScenePaint?,
    val dash: String?,
    val cap: StrokeCap = StrokeCap.FLAT,
    val join: StrokeJoin = StrokeJoin.ROUND,
    val miterLimit: Double = Strokes.DEFAULT_MITER_LIMIT,
    val compound: StrokeCompound = StrokeCompound.SINGLE,
    val customDash: List<DashStop> = emptyList(),
    val headEnd: ArrowEnd = ArrowEnd.NONE,
    val tailEnd: ArrowEnd = ArrowEnd.NONE,
)

/** The fill, line and effects of one shape, each already resolved independently of the others. */
internal class SceneFormat(val fill: ScenePaint?, val line: SceneLine?, val effects: XNode?)

/** One node of a resolved slide, in paint order. */
internal sealed interface SceneNode {
    val id: Int?
    val name: String?
    val frame: SceneRect?
    val hidden: Boolean
    val hyperlink: SceneHyperlink? get() = null
}

internal class SceneShape(
    override val id: Int?,
    override val name: String?,
    override val frame: SceneRect?,
    override val hidden: Boolean = false,
    val placeholder: PlaceholderIdentity? = null,
    val text: SceneTextFrame? = null,
    val preset: ScenePreset? = null,
    /** An inline `a:custGeom`, when the shape carries one, drawn in place of a named preset. */
    val customGeometry: PresetShape? = null,
    /** The `p:txBody` held whole; the text parser (011.md) resolves it into [text]. */
    val textBody: XNode? = null,
    /** The `p:style` reference into the theme's format scheme, held whole for the paint pass. */
    val style: XNode? = null,
    override val hyperlink: SceneHyperlink? = null,
    /** The raw `a:xfrm`, so an inherited frame can keep an offset the shape overrides but not its extent. */
    val transform: XNode? = null,
    val properties: XNode? = null,
    val format: SceneFormat? = null,
) : SceneNode

internal class ScenePicture(
    override val id: Int?,
    override val name: String?,
    override val frame: SceneRect?,
    override val hidden: Boolean = false,
    val relationshipId: String? = null,
    val text: SceneTextFrame? = null,
    val style: XNode? = null,
    override val hyperlink: SceneHyperlink? = null,
    val placeholder: PlaceholderIdentity? = null,
    val transform: XNode? = null,
    val properties: XNode? = null,
    val format: SceneFormat? = null,
    /** The resolved `p:blipFill`: its image relationship, alpha, crop and stretch or tile. */
    val source: PictureSource? = null,
    /** The picture's `a:prstGeom`, used as a mask when it is not a plain rectangle. */
    val mask: ScenePreset? = null,
    /** An inline `a:custGeom` used as the mask in place of a named preset. */
    val customGeometry: PresetShape? = null,
) : SceneNode

/** A group whose children are laid out in [childFrame], which the paint pass maps into [frame]. */
internal class SceneGroup(
    override val id: Int?,
    override val name: String?,
    override val frame: SceneRect?,
    override val hidden: Boolean = false,
    /** The `a:chOff`/`a:chExt` space the children were laid out in, before the group's own transform. */
    val childFrame: SceneRect? = null,
    val children: List<SceneNode> = emptyList(),
    override val hyperlink: SceneHyperlink? = null,
    val rotation: Double = 0.0,
    val flipH: Boolean = false,
    val flipV: Boolean = false,
) : SceneNode

internal class SceneConnector(
    override val id: Int?,
    override val name: String?,
    override val frame: SceneRect?,
    override val hidden: Boolean = false,
    val start: ScenePoint? = null,
    val end: ScenePoint? = null,
    val preset: ScenePreset? = null,
    /** An inline `a:custGeom`, when the connector carries one, drawn in place of a named preset. */
    val customGeometry: PresetShape? = null,
    val style: XNode? = null,
    override val hyperlink: SceneHyperlink? = null,
    val transform: XNode? = null,
    val properties: XNode? = null,
    val format: SceneFormat? = null,
) : SceneNode

internal enum class GraphicKind { TABLE, CHART, SMART_ART, OLE_OBJECT, OTHER }

internal class SceneGraphicFrame(
    override val id: Int?,
    override val name: String?,
    override val frame: SceneRect?,
    override val hidden: Boolean = false,
    val kind: GraphicKind = GraphicKind.OTHER,
    val relationshipId: String? = null,
    override val hyperlink: SceneHyperlink? = null,
) : SceneNode

internal enum class TextAnchor { TOP, MIDDLE, BOTTOM }

internal class SceneInsets(val left: Double, val top: Double, val right: Double, val bottom: Double) {

    companion object {
        val NONE = SceneInsets(0.0, 0.0, 0.0, 0.0)
    }
}

/** A length resolved from an `a:spcPct` (percent of the line) or an `a:spcPts` (points). */
internal class SceneSpacing(val percent: Double? = null, val points: Double? = null)

internal sealed interface SceneBullet {
    class Char(val char: String, val font: String?) : SceneBullet

    object None : SceneBullet

    class AutoNumber(val scheme: String, val startAt: Int?) : SceneBullet
}

/** One `a:lvlNpPr` after inheritance: the paragraph properties the layout pass reads. */
internal class SceneParagraphStyle(
    val alignment: String? = null,
    val marginLeftEmu: Double? = null,
    val indentEmu: Double? = null,
    val lineSpacing: SceneSpacing? = null,
    val spaceBefore: SceneSpacing? = null,
    val spaceAfter: SceneSpacing? = null,
    val bullet: SceneBullet? = null,
)

internal class SceneTextFrame(
    val paragraphs: List<SceneParagraph>,
    val inset: SceneInsets = SceneInsets.NONE,
    val anchor: TextAnchor = TextAnchor.TOP,
    val wrap: Boolean = true,
    val autofit: XNode? = null,
    val vertical: String? = null,
    val columns: Int = 1,
)

internal class SceneParagraph(
    val runs: List<SceneRun>,
    val level: Int = 0,
    val style: SceneParagraphStyle? = null,
    /** The resolved paragraph-mark style, which sets the height of an empty paragraph. */
    val endStyle: SceneRunStyle? = null,
)

/** A run whose inherited character formatting has already been resolved, ready for the text layout pass. */
internal class SceneRun(val text: String, val style: SceneRunStyle = SceneRunStyle())

internal class SceneRunStyle(
    val typeface: String? = null,
    val sizePt: Double? = null,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val strike: Boolean = false,
    val caps: Boolean = false,
    val color: Rgba? = null,
    val language: String? = null,
    /** Baseline shift as a fraction of the font size: positive is superscript, negative subscript. */
    val baseline: Double = 0.0,
)

/** A text frame's laid-out form, supplied by the text layout pass (025.md). */
internal interface LaidOutText

/** The immutable resolved scene a slide paints from, in EMU. */
internal class Scene(
    val size: SceneSize,
    val nodes: List<SceneNode>,
    val background: ScenePaint = ScenePaint.None,
) {

    /** Filled lazily by the text layout pass (025.md); every other part of a scene is fixed once built. */
    val textLayouts: MutableMap<SceneTextFrame, LaidOutText> = HashMap()
}
