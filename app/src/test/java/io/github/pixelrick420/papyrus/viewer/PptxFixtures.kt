package io.github.pixelrick420.papyrus.viewer

// Builds a PresentationML package in memory, so no third-party deck is ever committed:
//
//     val bytes = pptx {
//         slideSize(12_192_000, 6_858_000)
//         theme { accent1("4472C4"); majorFont("Calibri Light"); minorFont("Calibri") }
//         master { background(scheme = "bg1"); titleStyle { sz(4400) } }
//         layout("Title and Content") { placeholder(Type.TITLE, idx = 0, x = 838_200, y = 365_125, w = 10_515_600, h = 1_325_563) }
//     }.toBytes()

@DslMarker
internal annotation class PptxDsl

internal enum class Type(val ooxml: String) {
    TITLE("title"),
    CENTER_TITLE("ctrTitle"),
    SUBTITLE("subTitle"),
    BODY("body"),
    OBJECT("obj"),
    DATE("dt"),
    FOOTER("ftr"),
    SLIDE_NUMBER("sldNum"),
    PICTURE("pic"),
    CHART("chart"),
    TABLE("tbl"),
}

internal class Deck(val parts: Map<String, ByteArray>) {
    fun toBytes(): ByteArray = OfficeFixtures.zipOf(parts)

    fun text(name: String): String = parts.getValue(name).toString(Charsets.UTF_8)
}

internal fun pptx(build: DeckBuilder.() -> Unit): Deck = DeckBuilder().apply(build).build()

@PptxDsl
internal class DeckBuilder {
    private var cx = 12_192_000L
    private var cy = 6_858_000L
    private var firstSlide: Int? = null
    private val theme = ThemeBuilder()
    private val master = MasterBuilder()
    private val layouts = mutableListOf<LayoutBuilder>()
    private val defaultText = TextStyleBuilder.other()
    private val slides = mutableListOf<SlideBuilder>()
    private val sections = mutableListOf<Pair<String, List<Int>>>()
    private val images = ImageRegistry()
    private val extraParts = LinkedHashMap<String, ByteArray>()

    fun slideSize(cx: Long, cy: Long) {
        this.cx = cx
        this.cy = cy
    }

    fun firstSlideNumber(number: Int) {
        firstSlide = number
    }

    fun theme(block: ThemeBuilder.() -> Unit) {
        theme.apply(block)
    }

    fun master(block: MasterBuilder.() -> Unit) {
        master.apply(block)
    }

    fun layout(name: String, showMasterSp: Boolean = true, block: LayoutBuilder.() -> Unit = {}) {
        layouts += LayoutBuilder(name, showMasterSp).apply(block)
    }

    fun defaultTextStyle(block: TextStyleBuilder.() -> Unit) {
        defaultText.apply(block)
    }

    /** A slide on the layout at [layout], counted from 0 in declaration order. */
    fun slide(
        layout: Int = 0,
        hidden: Boolean = false,
        showMasterSp: Boolean = true,
        block: SlideBuilder.() -> Unit = {},
    ) {
        slides += SlideBuilder(layout, hidden, showMasterSp, images).apply(block)
    }

    /** A `p14` section over the slides at [slideIndexes], counted from 0 in declaration order. */
    fun section(name: String, vararg slideIndexes: Int) {
        sections += name to slideIndexes.toList()
    }

    /** A part of the caller's own, added as it is; for cases a builder cannot express. */
    fun part(name: String, bytes: ByteArray) {
        extraParts[name] = bytes
    }

    fun build(): Deck {
        // A master needs a layout to be usable, so a deck that declares none gets an empty one.
        val layoutList = layouts.ifEmpty { listOf(LayoutBuilder("Blank", true)) }
        val pkg = PackagePartsWriter()

        pkg.rels(
            "_rels/.rels",
            listOf(Rel("rId1", REL + "officeDocument", "ppt/presentation.xml")),
        )
        pkg.xml("ppt/presentation.xml", CT_PRESENTATION, presentationXml())
        pkg.rels(
            "ppt/_rels/presentation.xml.rels",
            listOf(
                Rel("rId1", REL + "slideMaster", "slideMasters/slideMaster1.xml"),
                Rel("rId2", REL + "theme", "theme/theme1.xml"),
                Rel("rId3", REL + "tableStyles", "tableStyles.xml"),
            ) + slides.indices.map { Rel("rId${FIRST_SLIDE_REL + it}", REL + "slide", "slides/slide${it + 1}.xml") },
        )
        pkg.xml("ppt/tableStyles.xml", CT_TABLE_STYLES, TABLE_STYLES)
        pkg.xml("ppt/theme/theme1.xml", CT_THEME, theme.xml())

        pkg.xml("ppt/slideMasters/slideMaster1.xml", CT_MASTER, master.xml(layoutList.size))
        pkg.rels(
            "ppt/slideMasters/_rels/slideMaster1.xml.rels",
            layoutList.indices.map { Rel("rId${it + 1}", REL + "slideLayout", "../slideLayouts/slideLayout${it + 1}.xml") } +
                Rel("rId${layoutList.size + 1}", REL + "theme", "../theme/theme1.xml"),
        )
        layoutList.forEachIndexed { i, layout ->
            val name = "ppt/slideLayouts/slideLayout${i + 1}.xml"
            pkg.xml(name, CT_LAYOUT, layout.xml())
            pkg.rels(
                "ppt/slideLayouts/_rels/slideLayout${i + 1}.xml.rels",
                listOf(Rel("rId1", REL + "slideMaster", "../slideMasters/slideMaster1.xml")),
            )
        }

        slides.forEachIndexed { i, slide ->
            require(slide.layoutIndex in layoutList.indices) {
                "slide ${i + 1} uses layout ${slide.layoutIndex}, but the deck has ${layoutList.size}"
            }
            pkg.xml("ppt/slides/slide${i + 1}.xml", CT_SLIDE, slide.xml())
            pkg.rels(
                "ppt/slides/_rels/slide${i + 1}.xml.rels",
                listOf(Rel("rId1", REL + "slideLayout", "../slideLayouts/slideLayout${slide.layoutIndex + 1}.xml")) + slide.relationships,
            )
        }
        images.parts.forEach { (name, bytes) -> pkg.binary(name, bytes) }
        extraParts.forEach { (name, bytes) -> pkg.binary(name, bytes) }
        return Deck(pkg.finish())
    }

    private fun presentationXml(): String {
        val first = firstSlide?.let { """ firstSlideNum="$it"""" }.orEmpty()
        return """<p:presentation $PRESENTATION_NS$first saveSubsetFonts="1">""" +
            """<p:sldMasterIdLst><p:sldMasterId id="$MASTER_ID" r:id="rId1"/></p:sldMasterIdLst>""" +
            slideIdList() +
            """<p:sldSz cx="$cx" cy="$cy"/><p:notesSz cx="6858000" cy="9144000"/>""" +
            defaultText.emit("p:defaultTextStyle") +
            sectionsXml() +
            "</p:presentation>"
    }

    private fun sectionsXml(): String {
        if (sections.isEmpty()) return ""
        val body = sections.mapIndexed { index, (name, indexes) ->
            val ids = indexes.joinToString("") { """<p14:sldId id="${FIRST_SLIDE_ID + it}"/>""" }
            """<p14:section name="${esc(name)}" id="{${SECTION_ID_BASE + index}}"><p14:sldIdLst>$ids</p14:sldIdLst></p14:section>"""
        }.joinToString("")
        return "<p14:sectionLst>$body</p14:sectionLst>"
    }

    private fun slideIdList(): String =
        if (slides.isEmpty()) "" else slides.indices.joinToString("", "<p:sldIdLst>", "</p:sldIdLst>") {
            """<p:sldId id="${FIRST_SLIDE_ID + it}" r:id="rId${FIRST_SLIDE_REL + it}"/>"""
        }

    private companion object {
        const val MASTER_ID = 2_147_483_648L
        const val FIRST_SLIDE_ID = 256
        const val FIRST_SLIDE_REL = 4
        const val SECTION_ID_BASE = 1_000
    }
}

@PptxDsl
internal class ThemeBuilder {
    private val colors = linkedMapOf(
        "dk1" to "000000", "lt1" to "FFFFFF", "dk2" to "44546A", "lt2" to "E7E6E6",
        "accent1" to "4472C4", "accent2" to "ED7D31", "accent3" to "A5A5A5",
        "accent4" to "FFC000", "accent5" to "5B9BD5", "accent6" to "70AD47",
        "hlink" to "0563C1", "folHlink" to "954F72",
    )
    private var major = "Calibri Light"
    private var minor = "Calibri"
    private var fillStyles: String? = null

    fun dk1(hex: String) = color("dk1", hex)
    fun lt1(hex: String) = color("lt1", hex)
    fun dk2(hex: String) = color("dk2", hex)
    fun lt2(hex: String) = color("lt2", hex)
    fun accent1(hex: String) = color("accent1", hex)
    fun accent2(hex: String) = color("accent2", hex)
    fun accent3(hex: String) = color("accent3", hex)
    fun accent4(hex: String) = color("accent4", hex)
    fun accent5(hex: String) = color("accent5", hex)
    fun accent6(hex: String) = color("accent6", hex)
    fun hlink(hex: String) = color("hlink", hex)
    fun folHlink(hex: String) = color("folHlink", hex)

    fun majorFont(face: String) {
        major = face
    }

    fun minorFont(face: String) {
        minor = face
    }

    /** Replaces the theme's `a:fillStyleLst` entries, for gradients no helper writes. */
    fun fillStyles(xml: String) {
        fillStyles = xml
    }

    private fun color(slot: String, hex: String) {
        require(HEX.matches(hex)) { "not an RRGGBB colour: $hex" }
        colors[slot] = hex.uppercase()
    }

    internal fun xml(): String {
        val scheme = colors.entries.joinToString("") { (slot, hex) -> """<a:$slot><a:srgbClr val="$hex"/></a:$slot>""" }
        return """<a:theme xmlns:a="$A_NS" name="Office Theme"><a:themeElements>""" +
            """<a:clrScheme name="Office">$scheme</a:clrScheme>""" +
            """<a:fontScheme name="Office">""" +
            """<a:majorFont><a:latin typeface="${esc(major)}"/><a:ea typeface=""/><a:cs typeface=""/></a:majorFont>""" +
            """<a:minorFont><a:latin typeface="${esc(minor)}"/><a:ea typeface=""/><a:cs typeface=""/></a:minorFont>""" +
            "</a:fontScheme>${formatScheme()}</a:themeElements><a:objectDefaults/><a:extraClrSchemeLst/></a:theme>"
    }

    private fun formatScheme(): String =
        """<a:fmtScheme name="Office">${fillStyles ?: FILL_STYLES}$LINE_STYLES$EFFECT_STYLES$BG_FILLS</a:fmtScheme>"""

    private companion object {
        val HEX = Regex("[0-9A-Fa-f]{6}")

        const val FILL_STYLES = """<a:fillStyleLst><a:solidFill><a:schemeClr val="phClr"/></a:solidFill>""" +
            """<a:solidFill><a:schemeClr val="phClr"><a:tint val="50000"/></a:schemeClr></a:solidFill>""" +
            """<a:solidFill><a:schemeClr val="phClr"><a:shade val="80000"/></a:schemeClr></a:solidFill></a:fillStyleLst>"""

        const val LINE_STYLES = """<a:lnStyleLst><a:ln w="6350"><a:solidFill><a:schemeClr val="phClr"/></a:solidFill></a:ln>""" +
            """<a:ln w="12700"><a:solidFill><a:schemeClr val="phClr"/></a:solidFill></a:ln>""" +
            """<a:ln w="19050"><a:solidFill><a:schemeClr val="phClr"/></a:solidFill></a:ln></a:lnStyleLst>"""

        const val EFFECT_STYLES = """<a:effectStyleLst><a:effectStyle><a:effectLst/></a:effectStyle>""" +
            """<a:effectStyle><a:effectLst/></a:effectStyle><a:effectStyle><a:effectLst/></a:effectStyle></a:effectStyleLst>"""

        const val BG_FILLS = """<a:bgFillStyleLst><a:solidFill><a:schemeClr val="phClr"/></a:solidFill>""" +
            """<a:solidFill><a:schemeClr val="phClr"><a:tint val="95000"/></a:schemeClr></a:solidFill>""" +
            """<a:solidFill><a:schemeClr val="phClr"><a:shade val="90000"/></a:schemeClr></a:solidFill></a:bgFillStyleLst>"""
    }
}

/** What every part that holds a shape tree shares: a background, and the placeholders and shapes in z-order. */
@PptxDsl
internal abstract class ShapeTreeBuilder {
    private var nextId = 2
    private var backgroundXml: String? = null
    private val shapes = mutableListOf<String>()

    /** An explicit fill: `bgPr`. */
    fun background(scheme: String? = null, rgb: String? = null) {
        backgroundXml = "<p:bg><p:bgPr>${colorFill(scheme, rgb)}<a:effectLst/></p:bgPr></p:bg>"
    }

    /** A reference into the theme's background styles: `bgRef`, where [idx] 1001 is the first. */
    fun backgroundRef(idx: Int = 1001, scheme: String = "bg1") {
        backgroundXml = """<p:bg><p:bgRef idx="$idx"><a:schemeClr val="$scheme"/></p:bgRef></p:bg>"""
    }

    /** Clears the inherited default, so a part can be built with no background at all. */
    fun noBackground() {
        backgroundXml = null
    }

    /** A non-placeholder shape: on a slide a drawn shape, on a master or layout a shape shown behind the slide's. */
    fun shape(
        preset: Preset,
        x: Long,
        y: Long,
        w: Long,
        h: Long,
        name: String? = null,
        hidden: Boolean = false,
        block: ShapeBuilder.() -> Unit = {},
    ) = shape(preset.ooxml, x, y, w, h, name, hidden, block)

    /** For a preset the [Preset] enum does not list. */
    fun shape(
        prst: String,
        x: Long,
        y: Long,
        w: Long,
        h: Long,
        name: String? = null,
        hidden: Boolean = false,
        block: ShapeBuilder.() -> Unit = {},
    ) {
        val id = nextShapeId()
        add(ShapeBuilder().apply(block).emit(id, name ?: "Shape $id", prst, x, y, w, h, textBox = false, hidden = hidden))
    }

    /** A group of shapes; [hidden] hides the group and every child with it. */
    fun group(
        x: Long,
        y: Long,
        w: Long,
        h: Long,
        name: String? = null,
        hidden: Boolean = false,
        block: GroupBuilder.() -> Unit = {},
    ) {
        val id = nextShapeId()
        add(GroupBuilder(hidden, name ?: "Group $id").apply(block).emit(id, x, y, w, h))
    }

    fun placeholder(
        type: Type?,
        idx: Int? = null,
        x: Long? = null,
        y: Long? = null,
        w: Long? = null,
        h: Long? = null,
        hidden: Boolean = false,
        body: (TextBodyBuilder.() -> Unit)? = null,
        listStyle: (TextStyleBuilder.() -> Unit)? = null,
        decoration: ShapeBuilder.() -> Unit = {},
    ) {
        val id = nextShapeId()
        val ph = buildString {
            append("<p:ph")
            type?.let { append(""" type="${it.ooxml}"""") }
            idx?.let { append(""" idx="$it"""") }
            append("/>")
        }
        val name = "${type?.ooxml ?: "obj"} placeholder $id"
        val hiddenAttr = if (hidden) """ hidden="1"""" else ""
        val text = TextBodyBuilder().apply {
            listStyle?.let { listStyle(it) }
            body?.let { body(this) }
        }.emit("p:txBody")
        val extras = ShapeBuilder().apply(decoration).spPrExtras()
        shapes += """<p:sp><p:nvSpPr><p:cNvPr id="$id" name="${esc(name)}"$hiddenAttr/>""" +
            """<p:cNvSpPr><a:spLocks noGrp="1"/></p:cNvSpPr><p:nvPr>$ph</p:nvPr></p:nvSpPr>""" +
            "<p:spPr>${xfrm(x, y, w, h)}$extras</p:spPr>$text</p:sp>"
    }

    protected fun nextShapeId(): Int = nextId++

    protected fun add(shapeXml: String) {
        shapes += shapeXml
    }

    protected fun commonSlide(name: String?): String {
        val nameAttr = name?.let { """ name="${esc(it)}"""" }.orEmpty()
        return "<p:cSld$nameAttr>${backgroundXml.orEmpty()}$TREE_HEAD${shapes.joinToString("")}</p:spTree></p:cSld>"
    }

    private companion object {
        const val TREE_HEAD = """<p:spTree><p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr>""" +
            """<p:grpSpPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="0" cy="0"/><a:chOff x="0" y="0"/><a:chExt cx="0" cy="0"/></a:xfrm></p:grpSpPr>"""
    }
}

@PptxDsl
internal class MasterBuilder : ShapeTreeBuilder() {
    private val title = TextStyleBuilder.title()
    private val body = TextStyleBuilder.body()
    private val other = TextStyleBuilder.other()
    private var textStyles = true
    private val colorMap = linkedMapOf(
        "bg1" to "lt1", "tx1" to "dk1", "bg2" to "lt2", "tx2" to "dk2",
        "accent1" to "accent1", "accent2" to "accent2", "accent3" to "accent3",
        "accent4" to "accent4", "accent5" to "accent5", "accent6" to "accent6",
        "hlink" to "hlink", "folHlink" to "folHlink",
    )

    init {
        backgroundRef()
    }

    fun titleStyle(block: TextStyleBuilder.() -> Unit) {
        title.apply(block)
    }

    fun bodyStyle(block: TextStyleBuilder.() -> Unit) {
        body.apply(block)
    }

    fun otherStyle(block: TextStyleBuilder.() -> Unit) {
        other.apply(block)
    }

    /** Re-points colour-map entries, for example `clrMap("bg1" to "dk1", "tx1" to "lt1")` for a dark master. */
    fun clrMap(vararg overrides: Pair<String, String>) {
        overrides.forEach { (slot, target) ->
            require(slot in colorMap) { "not a colour-map slot: $slot" }
            colorMap[slot] = target
        }
    }

    /** Leaves `p:txStyles` out entirely, so the chain falls through to `defaultTextStyle` and the built-ins. */
    fun noTextStyles() {
        textStyles = false
    }

    internal fun xml(layoutCount: Int): String {
        val map = colorMap.entries.joinToString(" ") { (slot, target) -> """$slot="$target"""" }
        val ids = (1..layoutCount).joinToString("") { """<p:sldLayoutId id="${LAYOUT_ID_BASE + it}" r:id="rId$it"/>""" }
        val styles = if (textStyles) {
            "<p:txStyles>${title.emit("p:titleStyle")}${body.emit("p:bodyStyle")}${other.emit("p:otherStyle")}</p:txStyles>"
        } else {
            ""
        }
        return "<p:sldMaster $NS>${commonSlide(null)}<p:clrMap $map/><p:sldLayoutIdLst>$ids</p:sldLayoutIdLst>" +
            styles + "</p:sldMaster>"
    }

    private companion object {
        const val LAYOUT_ID_BASE = 2_147_483_648L
    }
}

@PptxDsl
internal class LayoutBuilder(private val name: String, private val showMasterSp: Boolean) : ShapeTreeBuilder() {
    internal fun xml(): String {
        val show = if (showMasterSp) "" else """ showMasterSp="0""""
        return """<p:sldLayout $NS preserve="1"$show>${commonSlide(name)}""" +
            "<p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sldLayout>"
    }
}

/** Character formatting: the content of `a:rPr` and `a:defRPr`. */
@PptxDsl
internal class RunFormat {
    private var size: Int? = null
    private var isBold: Boolean? = null
    private var isItalic: Boolean? = null
    private var underline: String? = null
    private var strike: String? = null
    private var caps: String? = null
    private var baselineShift: Int? = null
    private var spacing: Int? = null
    private var kerning: Int? = null
    private var language: String? = null
    private var fillXml: String? = null
    private var latinFace: String? = null

    /** Size in hundredths of a point: 1800 is 18 pt. */
    fun sz(hundredths: Int) {
        size = hundredths
    }

    fun bold(on: Boolean = true) {
        isBold = on
    }

    fun italic(on: Boolean = true) {
        isItalic = on
    }

    fun underline(style: String = "sng") {
        underline = style
    }

    fun strike(style: String = "sngStrike") {
        strike = style
    }

    fun caps(mode: String) {
        caps = mode
    }

    /** Percent of the font size in thousandths: 30000 raises the text, -25000 lowers it. */
    fun baseline(shift: Int) {
        baselineShift = shift
    }

    /** Character spacing in hundredths of a point. */
    fun spc(hundredths: Int) {
        spacing = hundredths
    }

    /** The smallest size, in hundredths of a point, at which kerning applies; 0 is never. */
    fun kern(hundredths: Int) {
        kerning = hundredths
    }

    fun lang(tag: String) {
        language = tag
    }

    fun color(scheme: String? = null, rgb: String? = null) {
        fillXml = colorFill(scheme, rgb)
    }

    /** A typeface name, or a theme reference such as `+mj-lt`. */
    fun font(face: String) {
        latinFace = face
    }

    internal fun isEmpty(): Boolean = attributes().isEmpty() && children().isEmpty()

    internal fun emit(tag: String): String {
        val children = children()
        val attrs = attributes()
        return if (children.isEmpty()) "<a:$tag$attrs/>" else "<a:$tag$attrs>$children</a:$tag>"
    }

    private fun attributes(): String = buildString {
        language?.let { append(""" lang="$it"""") }
        size?.let { append(""" sz="$it"""") }
        isBold?.let { append(""" b="${if (it) 1 else 0}"""") }
        isItalic?.let { append(""" i="${if (it) 1 else 0}"""") }
        underline?.let { append(""" u="$it"""") }
        strike?.let { append(""" strike="$it"""") }
        caps?.let { append(""" cap="$it"""") }
        kerning?.let { append(""" kern="$it"""") }
        spacing?.let { append(""" spc="$it"""") }
        baselineShift?.let { append(""" baseline="$it"""") }
    }

    private fun children(): String = buildString {
        fillXml?.let { append(it) }
        latinFace?.let { append("""<a:latin typeface="${esc(it)}"/>""") }
    }
}

/** One `a:lvlNpPr`: paragraph properties for a list level, with the character formatting it implies. */
@PptxDsl
internal class LevelStyle {
    private var marginLeft: Int? = null
    private var indentation: Int? = null
    private var alignment: String? = null
    private var lineSpacing: String? = null
    private var spaceBefore: String? = null
    private var spaceAfter: String? = null
    private var bulletXml: String? = null
    private val run = RunFormat()

    fun marL(emu: Int) {
        marginLeft = emu
    }

    /** Negative for a hanging indent. */
    fun indent(emu: Int) {
        indentation = emu
    }

    fun algn(value: String) {
        alignment = value
    }

    fun lnSpcPct(percent: Int) {
        lineSpacing = """<a:lnSpc><a:spcPct val="${percent * 1000}"/></a:lnSpc>"""
    }

    fun lnSpcPts(points: Int) {
        lineSpacing = """<a:lnSpc><a:spcPts val="${points * 100}"/></a:lnSpc>"""
    }

    fun spcBefPct(percent: Int) {
        spaceBefore = """<a:spcBef><a:spcPct val="${percent * 1000}"/></a:spcBef>"""
    }

    fun spcBefPts(points: Int) {
        spaceBefore = """<a:spcBef><a:spcPts val="${points * 100}"/></a:spcBef>"""
    }

    fun spcAftPct(percent: Int) {
        spaceAfter = """<a:spcAft><a:spcPct val="${percent * 1000}"/></a:spcAft>"""
    }

    fun spcAftPts(points: Int) {
        spaceAfter = """<a:spcAft><a:spcPts val="${points * 100}"/></a:spcAft>"""
    }

    fun bullet(char: String, font: String? = "Arial") {
        val face = font?.let { """<a:buFont typeface="${esc(it)}"/>""" }.orEmpty()
        bulletXml = face + """<a:buChar char="${esc(char)}"/>"""
    }

    fun noBullet() {
        bulletXml = "<a:buNone/>"
    }

    fun autoNumber(scheme: String = "arabicPeriod", startAt: Int? = null) {
        val start = startAt?.let { """ startAt="$it"""" }.orEmpty()
        bulletXml = """<a:buAutoNum type="$scheme"$start/>"""
    }

    fun sz(hundredths: Int) = run.sz(hundredths)

    fun bold(on: Boolean = true) = run.bold(on)

    fun italic(on: Boolean = true) = run.italic(on)

    fun kern(hundredths: Int) = run.kern(hundredths)

    fun color(scheme: String? = null, rgb: String? = null) = run.color(scheme, rgb)

    fun font(face: String) = run.font(face)

    internal fun emit(tag: String): String {
        val attrs = buildString {
            marginLeft?.let { append(""" marL="$it"""") }
            indentation?.let { append(""" indent="$it"""") }
            alignment?.let { append(""" algn="$it"""") }
        }
        val children = lineSpacing.orEmpty() + spaceBefore.orEmpty() + spaceAfter.orEmpty() +
            bulletXml.orEmpty() + if (run.isEmpty()) "" else run.emit("defRPr")
        return if (children.isEmpty()) "<a:$tag$attrs/>" else "<a:$tag$attrs>$children</a:$tag>"
    }
}

/** A list style: levels 1 to 9, each written only if it was set, so an `lstStyle` carries just its overrides. */
@PptxDsl
internal class TextStyleBuilder private constructor() {
    private val levels = sortedMapOf<Int, LevelStyle>()

    /** Size of level 1, in hundredths of a point. */
    fun sz(hundredths: Int) = level(1) { sz(hundredths) }

    fun level(n: Int, block: LevelStyle.() -> Unit) {
        require(n in 1..MAX_LEVEL) { "list levels run from 1 to $MAX_LEVEL" }
        levels.getOrPut(n) { LevelStyle() }.apply(block)
    }

    internal fun emit(tag: String): String =
        "<$tag>" + levels.entries.joinToString("") { (n, style) -> style.emit("lvl${n}pPr") } + "</$tag>"

    companion object {
        private const val MAX_LEVEL = 9

        /** A style that holds nothing until it is told to, for `lstStyle`. */
        fun blank() = TextStyleBuilder()

        /** The Office master's title style. */
        fun title() = TextStyleBuilder().apply {
            level(1) {
                algn("l")
                lnSpcPct(90)
                spcBefPts(0)
                noBullet()
                sz(4400)
                kern(1200)
                color(scheme = "tx1")
                font("+mj-lt")
            }
        }

        /** The Office master's body style: a bullet and a smaller size for each level. */
        fun body() = TextStyleBuilder().apply {
            val sizes = intArrayOf(2800, 2400, 2000, 1800, 1800, 1800, 1800, 1800, 1800)
            for (n in 1..MAX_LEVEL) {
                level(n) {
                    marL(228_600 + 457_200 * (n - 1))
                    indent(-228_600)
                    algn("l")
                    lnSpcPct(90)
                    spcBefPts(if (n == 1) 10 else 5)
                    bullet("•")
                    sz(sizes[n - 1])
                    kern(1200)
                    color(scheme = "tx1")
                    font("+mn-lt")
                }
            }
        }

        /** The Office master's other style, which is also the shape of a presentation's default text style. */
        fun other() = TextStyleBuilder().apply {
            for (n in 1..MAX_LEVEL) {
                level(n) {
                    marL(457_200 * (n - 1))
                    algn("l")
                    sz(1800)
                    kern(1200)
                    color(scheme = "tx1")
                    font("+mn-lt")
                }
            }
        }
    }
}

internal enum class Preset(val ooxml: String) {
    RECT("rect"),
    ROUND_RECT("roundRect"),
    ELLIPSE("ellipse"),
    TRIANGLE("triangle"),
    RIGHT_ARROW("rightArrow"),
    UP_ARROW("upArrow"),
    CHEVRON("chevron"),
    STAR5("star5"),
    LINE("line"),
}

internal class ImageRegistry {
    private val images = LinkedHashMap<String, ByteArray>()

    val parts: Map<String, ByteArray> get() = images

    /** Stores [bytes] as a new media part and returns its part name. */
    fun add(bytes: ByteArray, extension: String): String {
        val ext = extension.lowercase()
        require(ext in IMAGE_TYPES) { "no content type known for .$ext" }
        val name = "ppt/media/image${images.size + 1}.$ext"
        images[name] = bytes
        return name
    }
}

@PptxDsl
internal class ParaBuilder(private val level: Int) {
    private val runs = mutableListOf<String>()
    private var alignment: String? = null
    private var defaultRun: RunFormat? = null
    private var endPara: RunFormat? = null

    fun algn(value: String) {
        alignment = value
    }

    fun run(text: String, format: RunFormat.() -> Unit = {}) {
        val props = RunFormat().apply(format)
        runs += "<a:r>${props.emit("rPr")}<a:t>${esc(text)}</a:t></a:r>"
    }

    /** A field; [text] is the cached text the file stores beside it. */
    fun field(type: String, text: String = "", format: RunFormat.() -> Unit = {}) {
        val props = RunFormat().apply(format)
        runs += """<a:fld type="$type">${props.emit("rPr")}<a:t>${esc(text)}</a:t></a:fld>"""
    }

    fun lineBreak() {
        runs += "<a:br/>"
    }

    /** The paragraph-level `defRPr`, which outranks the list styles but not a run's own `rPr`. */
    fun defaultRun(format: RunFormat.() -> Unit = {}) {
        defaultRun = RunFormat().apply(format)
    }

    /** The paragraph mark's formatting, which sets the height of an empty paragraph. */
    fun endPara(format: RunFormat.() -> Unit = {}) {
        endPara = RunFormat().apply(format)
    }

    internal fun emit(): String {
        require(level in 0..MAX_LEVEL) { "list levels run from 0 to $MAX_LEVEL" }
        val attrs = (if (level > 0) """ lvl="$level"""" else "") + alignment?.let { """ algn="$it"""" }.orEmpty()
        val props = buildString {
            if (attrs.isNotEmpty() || defaultRun != null) {
                append("<a:pPr$attrs>")
                defaultRun?.let { append(it.emit("defRPr")) }
                append("</a:pPr>")
            }
        }
        val end = endPara?.emit("endParaRPr").orEmpty()
        return if (props.isEmpty() && runs.isEmpty() && end.isEmpty()) {
            "<a:p/>"
        } else {
            "<a:p>$props${runs.joinToString("")}$end</a:p>"
        }
    }

    private companion object {
        const val MAX_LEVEL = 8
    }
}

/** The paragraphs of a shape, a placeholder or a table cell. Levels count from 0, as `lvl` does in the file. */
@PptxDsl
internal class TextBodyBuilder {
    private val paragraphs = mutableListOf<String>()
    private var listStyle: TextStyleBuilder? = null
    private var anchor: String? = null
    private var wrap: String? = null
    private var autofit: String? = null
    private var insets: List<Long>? = null

    /** The shape's own `lstStyle`, which outranks the layout's and the master's. */
    fun listStyle(block: TextStyleBuilder.() -> Unit) {
        listStyle = TextStyleBuilder.blank().apply(block)
    }

    fun anchor(value: String) {
        anchor = value
    }

    fun wrap(on: Boolean) {
        wrap = if (on) "square" else "none"
    }

    /** Insets in EMU: left, top, right, bottom. */
    fun insets(l: Long, t: Long, r: Long, b: Long) {
        insets = listOf(l, t, r, b)
    }

    /** `normAutofit`, with the scale PowerPoint last applied (percent of font size, line spacing reduction) if known. */
    fun shrinkOnOverflow(fontScalePercent: Int? = null, lnSpcReductionPercent: Int? = null) {
        val scale = fontScalePercent?.let { """ fontScale="${it * 1000}"""" }.orEmpty()
        val reduction = lnSpcReductionPercent?.let { """ lnSpcReduction="${it * 1000}"""" }.orEmpty()
        autofit = "<a:normAutofit$scale$reduction/>"
    }

    fun resizeShapeToFit() {
        autofit = "<a:spAutoFit/>"
    }

    fun noAutofit() {
        autofit = "<a:noAutofit/>"
    }

    fun para(text: String, level: Int = 0, format: RunFormat.() -> Unit = {}) {
        para(level) { run(text, format) }
    }

    fun para(level: Int = 0, block: ParaBuilder.() -> Unit) {
        paragraphs += ParaBuilder(level).apply(block).emit()
    }

    internal fun emit(tag: String): String {
        val attrs = buildString {
            wrap?.let { append(""" wrap="$it"""") }
            insets?.let { (l, t, r, b) -> append(""" lIns="$l" tIns="$t" rIns="$r" bIns="$b"""") }
            anchor?.let { append(""" anchor="$it"""") }
        }
        val body = paragraphs.ifEmpty { listOf("<a:p/>") }.joinToString("")
        val styles = listStyle?.emit("a:lstStyle") ?: "<a:lstStyle/>"
        return "<$tag><a:bodyPr$attrs>${autofit.orEmpty()}</a:bodyPr>$styles$body</$tag>"
    }
}

@PptxDsl
internal class ShapeBuilder {
    private var fillXml: String? = null
    private var lineXml: String? = null
    private var styleXml: String? = null
    private var rotation: Int? = null
    private var flippedH = false
    private var flippedV = false
    private val adjusts = mutableListOf<String>()
    private var textBody: TextBodyBuilder? = null

    fun fill(scheme: String) {
        fillXml = colorFill(scheme, null)
    }

    fun fillRgb(hex: String) {
        fillXml = colorFill(null, hex)
    }

    fun noFill() {
        fillXml = "<a:noFill/>"
    }

    /** A fill written as it is, for gradients and colour transforms no helper expresses. */
    fun fillRaw(xml: String) {
        fillXml = xml
    }

    fun line(
        scheme: String? = null,
        rgb: String? = null,
        widthEmu: Int = 12_700,
        dash: String? = null,
        attributes: String = "",
        extra: String = "",
    ) {
        val dashXml = dash?.let { """<a:prstDash val="$it"/>""" }.orEmpty()
        lineXml = """<a:ln w="$widthEmu"$attributes>${colorFill(scheme, rgb)}$dashXml$extra</a:ln>"""
    }

    fun noLine() {
        lineXml = "<a:ln><a:noFill/></a:ln>"
    }

    /** Rotation in 60000ths of a degree, as in the file. */
    fun rotate(sixtyThousandths: Int) {
        rotation = sixtyThousandths
    }

    fun flip(horizontal: Boolean = false, vertical: Boolean = false) {
        flippedH = horizontal
        flippedV = vertical
    }

    /** One adjust value of the preset, in the file's own units: `adjust("adj", 16_667)`. */
    fun adjust(name: String, value: Long) {
        adjusts += """<a:gd name="$name" fmla="val $value"/>"""
    }

    /** The `p:style` a placeholder or shape normally carries: references into the theme's format scheme. */
    fun style(lineRef: Int = 1, fillRef: Int = 3, effectRef: Int = 1, scheme: String = "accent1") {
        styleXml = "<p:style>" +
            """<a:lnRef idx="$lineRef">${colorFill(scheme, null)}</a:lnRef>""" +
            """<a:fillRef idx="$fillRef">${colorFill(scheme, null)}</a:fillRef>""" +
            """<a:effectRef idx="$effectRef">${colorFill(scheme, null)}</a:effectRef>""" +
            """<a:fontRef idx="minor">${colorFill("lt1", null)}</a:fontRef></p:style>"""
    }

    fun text(block: TextBodyBuilder.() -> Unit) {
        textBody = TextBodyBuilder().apply(block)
    }

    /** The `spPr` children a caller can add to a placeholder it does not build itself. */
    internal fun spPrExtras(): String = fillXml.orEmpty() + lineXml.orEmpty()

    internal fun emit(
        id: Int,
        name: String,
        prst: String,
        x: Long,
        y: Long,
        w: Long,
        h: Long,
        textBox: Boolean,
        hidden: Boolean = false,
    ): String {
        val attrs = buildString {
            rotation?.let { append(""" rot="$it"""") }
            if (flippedH) append(""" flipH="1"""")
            if (flippedV) append(""" flipV="1"""")
        }
        val hiddenAttr = if (hidden) """ hidden="1"""" else ""
        val cNvSpPr = if (textBox) """<p:cNvSpPr txBox="1"/>""" else "<p:cNvSpPr/>"
        return """<p:sp><p:nvSpPr><p:cNvPr id="$id" name="${esc(name)}"$hiddenAttr/>$cNvSpPr<p:nvPr/></p:nvSpPr>""" +
            """<p:spPr><a:xfrm$attrs><a:off x="$x" y="$y"/><a:ext cx="$w" cy="$h"/></a:xfrm>""" +
            """<a:prstGeom prst="$prst"><a:avLst>${adjusts.joinToString("")}</a:avLst></a:prstGeom>""" +
            "${fillXml.orEmpty()}${lineXml.orEmpty()}</p:spPr>" +
            styleXml.orEmpty() +
            textBody?.emit("p:txBody").orEmpty() + "</p:sp>"
    }
}

/** A group of shapes, so a test can check that a hidden group takes its children with it. */
@PptxDsl
internal class GroupBuilder(private val hidden: Boolean, private val name: String) {
    private var nextId = 2
    private val shapes = mutableListOf<String>()

    fun shape(
        preset: Preset,
        x: Long,
        y: Long,
        w: Long,
        h: Long,
        hidden: Boolean = false,
        block: ShapeBuilder.() -> Unit = {},
    ) {
        val id = nextId++
        shapes += ShapeBuilder().apply(block)
            .emit(id, "Shape $id", preset.ooxml, x, y, w, h, textBox = false, hidden = hidden)
    }

    internal fun emit(id: Int, x: Long, y: Long, w: Long, h: Long): String {
        val hiddenAttr = if (hidden) """ hidden="1"""" else ""
        return """<p:grpSp><p:nvGrpSpPr><p:cNvPr id="$id" name="${esc(name)}"$hiddenAttr/>""" +
            "<p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr>" +
            """<p:grpSpPr><a:xfrm><a:off x="$x" y="$y"/><a:ext cx="$w" cy="$h"/>""" +
            """<a:chOff x="0" y="0"/><a:chExt cx="$w" cy="$h"/></a:xfrm></p:grpSpPr>""" +
            shapes.joinToString("") + "</p:grpSp>"
    }
}

@PptxDsl
internal class RowBuilder(private val columnCount: Int) {
    private val cells = mutableListOf<String>()
    private var covered = 0

    /** A cell across [gridSpan] columns; the columns it covers get the merged cells the file needs. */
    fun cell(text: String = "", gridSpan: Int = 1, fillRgb: String? = null, format: RunFormat.() -> Unit = {}) {
        require(gridSpan >= 1) { "a cell spans at least one column" }
        val body = TextBodyBuilder().apply { if (text.isNotEmpty()) para(text, 0, format) }
        val span = if (gridSpan > 1) """ gridSpan="$gridSpan"""" else ""
        val tcPr = fillRgb?.let { "<a:tcPr>${colorFill(null, it)}</a:tcPr>" } ?: "<a:tcPr/>"
        cells += "<a:tc$span>${body.emit("a:txBody")}$tcPr</a:tc>"
        repeat(gridSpan - 1) {
            cells += """<a:tc hMerge="1">${TextBodyBuilder().emit("a:txBody")}<a:tcPr/></a:tc>"""
        }
        covered += gridSpan
    }

    internal fun emit(height: Long): String {
        require(covered == columnCount) { "a row must cover all $columnCount columns, this one covers $covered" }
        return """<a:tr h="$height">${cells.joinToString("")}</a:tr>"""
    }
}

@PptxDsl
internal class TableBuilder(private val columns: List<Long>) {
    private val rows = mutableListOf<Pair<Long, String>>()

    internal val totalHeight: Long get() = rows.sumOf { it.first }

    fun row(height: Long, vararg texts: String) {
        row(height) { texts.forEach { cell(it) } }
    }

    fun row(height: Long, block: RowBuilder.() -> Unit) {
        rows += height to RowBuilder(columns.size).apply(block).emit(height)
    }

    internal fun emit(): String =
        """<a:tbl><a:tblPr firstRow="1" bandRow="1"/><a:tblGrid>${columns.joinToString("") { """<a:gridCol w="$it"/>""" }}</a:tblGrid>""" +
            rows.joinToString("") { it.second } + "</a:tbl>"
}

@PptxDsl
internal class SlideBuilder(
    internal val layoutIndex: Int,
    private val hidden: Boolean,
    private val showMasterSp: Boolean,
    private val images: ImageRegistry,
) : ShapeTreeBuilder() {
    private val rels = mutableListOf<Rel>()

    /** Relationships beyond the layout's, which is always rId1. */
    internal val relationships: List<Rel> get() = rels

    /** A relationship of the caller's own, for a case the builders cannot express. */
    fun rel(id: String, type: String, target: String, external: Boolean = false) {
        rels += Rel(id, type, target, external)
    }

    fun title(text: String, type: Type = Type.TITLE, format: RunFormat.() -> Unit = {}) {
        textPlaceholder(type) { para(text, 0, format) }
    }

    fun body(idx: Int = 1, type: Type = Type.BODY, block: TextBodyBuilder.() -> Unit) {
        textPlaceholder(type, idx, block = block)
    }

    /** A placeholder with text; a frame given here overrides the one inherited from the layout. */
    fun textPlaceholder(
        type: Type?,
        idx: Int? = null,
        x: Long? = null,
        y: Long? = null,
        w: Long? = null,
        h: Long? = null,
        block: TextBodyBuilder.() -> Unit,
    ) {
        val id = nextShapeId()
        val ph = buildString {
            append("<p:ph")
            type?.let { append(""" type="${it.ooxml}"""") }
            idx?.let { append(""" idx="$it"""") }
            append("/>")
        }
        add(
            """<p:sp><p:nvSpPr><p:cNvPr id="$id" name="${esc("${type?.ooxml ?: "obj"} placeholder $id")}"/>""" +
                """<p:cNvSpPr><a:spLocks noGrp="1"/></p:cNvSpPr><p:nvPr>$ph</p:nvPr></p:nvSpPr>""" +
                "<p:spPr>${xfrm(x, y, w, h)}</p:spPr>${TextBodyBuilder().apply(block).emit("p:txBody")}</p:sp>",
        )
    }

    fun textBox(x: Long, y: Long, w: Long, h: Long, name: String? = null, block: TextBodyBuilder.() -> Unit) {
        val id = nextShapeId()
        val shape = ShapeBuilder().apply {
            noFill()
            text(block)
        }
        add(shape.emit(id, name ?: "TextBox $id", Preset.RECT.ooxml, x, y, w, h, textBox = true))
    }

    /** A table whose height is the sum of its rows'. */
    fun table(x: Long, y: Long, columns: List<Long>, name: String? = null, block: TableBuilder.() -> Unit) {
        require(columns.isNotEmpty()) { "a table needs at least one column" }
        val id = nextShapeId()
        val table = TableBuilder(columns).apply(block)
        add(
            """<p:graphicFrame><p:nvGraphicFramePr><p:cNvPr id="$id" name="${esc(name ?: "Table $id")}"/>""" +
                """<p:cNvGraphicFramePr><a:graphicFrameLocks noGrp="1"/></p:cNvGraphicFramePr><p:nvPr/></p:nvGraphicFramePr>""" +
                """<p:xfrm><a:off x="$x" y="$y"/><a:ext cx="${columns.sum()}" cy="${table.totalHeight}"/></p:xfrm>""" +
                """<a:graphic><a:graphicData uri="$TABLE_URI">${table.emit()}</a:graphicData></a:graphic></p:graphicFrame>""",
        )
    }

    fun picture(
        bytes: ByteArray,
        x: Long,
        y: Long,
        w: Long,
        h: Long,
        extension: String = "png",
        description: String = "",
        name: String? = null,
    ) {
        val id = nextShapeId()
        val relId = "rId${rels.size + 2}"
        rels += Rel(relId, REL + "image", "../media/" + images.add(bytes, extension).substringAfterLast('/'))
        add(
            """<p:pic><p:nvPicPr><p:cNvPr id="$id" name="${esc(name ?: "Picture $id")}" descr="${esc(description)}"/>""" +
                """<p:cNvPicPr><a:picLocks noChangeAspect="1"/></p:cNvPicPr><p:nvPr/></p:nvPicPr>""" +
                """<p:blipFill><a:blip r:embed="$relId"/><a:stretch><a:fillRect/></a:stretch></p:blipFill>""" +
                """<p:spPr>${xfrm(x, y, w, h)}<a:prstGeom prst="rect"><a:avLst/></a:prstGeom></p:spPr></p:pic>""",
        )
    }

    /** A graphic frame holding caller-supplied `graphicData` content, for charts and diagrams. */
    fun graphicFrame(
        uri: String,
        content: String,
        x: Long,
        y: Long,
        w: Long,
        h: Long,
        name: String? = null,
    ) {
        val id = nextShapeId()
        add(
            """<p:graphicFrame><p:nvGraphicFramePr><p:cNvPr id="$id" name="${esc(name ?: "Frame $id")}"/>""" +
                """<p:cNvGraphicFramePr><a:graphicFrameLocks noGrp="1"/></p:cNvGraphicFramePr><p:nvPr/></p:nvGraphicFramePr>""" +
                """<p:xfrm><a:off x="$x" y="$y"/><a:ext cx="$w" cy="$h"/></p:xfrm>""" +
                """<a:graphic><a:graphicData uri="$uri">$content</a:graphicData></a:graphic></p:graphicFrame>""",
        )
    }

    internal fun xml(): String {
        val show = if (hidden) """ show="0"""" else ""
        val master = if (showMasterSp) "" else """ showMasterSp="0""""
        return "<p:sld $NS$show$master>${commonSlide(null)}<p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sld>"
    }
}

internal class Rel(val id: String, val type: String, val target: String, val external: Boolean = false)

/** Collects parts, their relationships and their content types, and writes `[Content_Types].xml` last-known-first. */
private class PackagePartsWriter {
    private val parts = LinkedHashMap<String, ByteArray>()
    private val overrides = LinkedHashMap<String, String>()
    private val defaults = linkedMapOf(
        "rels" to "application/vnd.openxmlformats-package.relationships+xml",
        "xml" to "application/xml",
    )

    fun xml(name: String, contentType: String, body: String) {
        parts[name] = (XML_DECLARATION + body).toByteArray(Charsets.UTF_8)
        overrides["/$name"] = contentType
    }

    fun rels(name: String, rels: List<Rel>) {
        val body = rels.joinToString("") {
            val mode = if (it.external) """ TargetMode="External"""" else ""
            """<Relationship Id="${it.id}" Type="${it.type}" Target="${esc(it.target)}"$mode/>"""
        }
        parts[name] = (XML_DECLARATION + """<Relationships xmlns="$REL_NS">$body</Relationships>""").toByteArray(Charsets.UTF_8)
    }

    fun binary(name: String, bytes: ByteArray) {
        parts[name] = bytes
        IMAGE_TYPES[name.substringAfterLast('.').lowercase()]?.let { defaults[name.substringAfterLast('.').lowercase()] = it }
    }

    fun finish(): Map<String, ByteArray> {
        val defaultXml = defaults.entries.joinToString("") { (ext, type) -> """<Default Extension="$ext" ContentType="$type"/>""" }
        val overrideXml = overrides.entries.joinToString("") { (name, type) -> """<Override PartName="$name" ContentType="$type"/>""" }
        val types = XML_DECLARATION + """<Types xmlns="$TYPES_NS">$defaultXml$overrideXml</Types>"""
        return LinkedHashMap<String, ByteArray>().apply {
            put("[Content_Types].xml", types.toByteArray(Charsets.UTF_8))
            putAll(parts)
        }
    }
}

private val HEX_COLOUR = Regex("[0-9A-Fa-f]{6}")

private val IMAGE_TYPES = mapOf(
    "png" to "image/png", "jpg" to "image/jpeg", "jpeg" to "image/jpeg",
    "gif" to "image/gif", "bmp" to "image/bmp", "svg" to "image/svg+xml",
)

private fun colorFill(scheme: String?, rgb: String?): String {
    require((scheme == null) != (rgb == null)) { "give a scheme colour or an RGB value, not both or neither" }
    require(rgb == null || HEX_COLOUR.matches(rgb)) { "not an RRGGBB colour: $rgb" }
    return if (scheme != null) {
        """<a:solidFill><a:schemeClr val="$scheme"/></a:solidFill>"""
    } else {
        """<a:solidFill><a:srgbClr val="$rgb"/></a:solidFill>"""
    }
}

private fun xfrm(x: Long?, y: Long?, w: Long?, h: Long?): String {
    val offset = if (x == null && y == null) {
        ""
    } else {
        "<a:off" + (x?.let { """ x="$it"""" } ?: "") + (y?.let { """ y="$it"""" } ?: "") + "/>"
    }
    val extent = if (w == null && h == null) {
        ""
    } else {
        "<a:ext" + (w?.let { """ cx="$it"""" } ?: "") + (h?.let { """ cy="$it"""" } ?: "") + "/>"
    }
    if (offset.isEmpty() && extent.isEmpty()) return ""
    return "<a:xfrm>$offset$extent</a:xfrm>"
}

private fun esc(text: String): String = buildString {
    for (c in text) {
        when (c) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            else -> append(c)
        }
    }
}

private const val XML_DECLARATION = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>"""

private const val A_NS = "http://schemas.openxmlformats.org/drawingml/2006/main"

private const val TABLE_URI = "http://schemas.openxmlformats.org/drawingml/2006/table"

private const val R_NS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"

private const val P_NS = "http://schemas.openxmlformats.org/presentationml/2006/main"

private const val REL_NS = "http://schemas.openxmlformats.org/package/2006/relationships"

private const val TYPES_NS = "http://schemas.openxmlformats.org/package/2006/content-types"

private const val NS = """xmlns:a="$A_NS" xmlns:r="$R_NS" xmlns:p="$P_NS""""

private const val P14_NS = "http://schemas.microsoft.com/office/powerpoint/2010/main"

private const val MC_NS = "http://schemas.openxmlformats.org/markup-compatibility/2006"

private const val PRESENTATION_NS = NS + """ xmlns:p14="$P14_NS" xmlns:mc="$MC_NS" mc:Ignorable="p14""""

private const val REL = "$R_NS/"

private const val CT_BASE = "application/vnd.openxmlformats-officedocument."

private const val CT_PRESENTATION = CT_BASE + "presentationml.presentation.main+xml"

private const val CT_MASTER = CT_BASE + "presentationml.slideMaster+xml"

private const val CT_LAYOUT = CT_BASE + "presentationml.slideLayout+xml"

private const val CT_THEME = CT_BASE + "theme+xml"

private const val CT_SLIDE = CT_BASE + "presentationml.slide+xml"

private const val CT_TABLE_STYLES = CT_BASE + "presentationml.tableStyles+xml"

private const val TABLE_STYLES =
    """<a:tblStyleLst xmlns:a="$A_NS" def="{5C22544A-7EE6-4342-B048-85BDC9FD1C3A}"/>"""
