package io.github.pixelrick420.papyrus.viewer.pptx.text

import io.github.pixelrick420.papyrus.viewer.pptx.ColorModel
import io.github.pixelrick420.papyrus.viewer.pptx.ColorSource
import io.github.pixelrick420.papyrus.viewer.pptx.ColorValue
import io.github.pixelrick420.papyrus.viewer.pptx.Fills
import io.github.pixelrick420.papyrus.viewer.pptx.OoxmlNamespace
import io.github.pixelrick420.papyrus.viewer.pptx.PptxLimits
import io.github.pixelrick420.papyrus.viewer.pptx.Rgba
import io.github.pixelrick420.papyrus.viewer.pptx.ScenePaint
import io.github.pixelrick420.papyrus.viewer.pptx.Theme
import io.github.pixelrick420.papyrus.viewer.pptx.XNode

/**
 * The style levels a text body is resolved against, most specific first: the shape's own `lstStyle`,
 * its layout's, its master's, the master's `txStyles` entry and the presentation default.
 */
internal class TextStyleSources(
    val listStyles: List<XNode> = emptyList(),
    val masterStyle: XNode? = null,
    val defaultTextStyle: XNode? = null,
    val theme: Theme? = null,
    val colors: ColorModel = ColorModel(),
)

/** Cascades `bodyPr`, `pPr` and `rPr` chains into the flat [TextBody] the layout pass consumes. */
internal class TextStyleResolver(private val limits: PptxLimits = PptxLimits()) {

    fun resolve(
        textBody: XNode,
        sources: TextStyleSources = TextStyleSources(),
        bodyProperties: List<XNode> = listOfNotNull(textBody.child(OoxmlNamespace.A, "bodyPr")),
    ): TextBody {
        val cascade = Cascade(sources)
        val budget = TextBudget(limits.maxTextChars)
        val paragraphs = textBody.childrenOf(OoxmlNamespace.A, "p")
            .take(limits.maxParagraphs)
            .map { paragraphOf(it, cascade, sources, budget) }
        return TextBody(bodyPropertiesOf(bodyProperties), paragraphs, budget.truncated)
    }

    private fun bodyPropertiesOf(sources: List<XNode>): TextBodyProperties {
        fun attribute(name: String): String? = sources.firstNotNullOfOrNull { it.attribute(name) }
        return TextBodyProperties(
            insets = TextInsets(
                left = attribute("lIns")?.toDoubleOrNull() ?: TextInsets.DEFAULT.left,
                top = attribute("tIns")?.toDoubleOrNull() ?: TextInsets.DEFAULT.top,
                right = attribute("rIns")?.toDoubleOrNull() ?: TextInsets.DEFAULT.right,
                bottom = attribute("bIns")?.toDoubleOrNull() ?: TextInsets.DEFAULT.bottom,
            ),
            anchor = TextAnchor.of(attribute("anchor")),
            anchorCenter = isSwitch(attribute("anchorCtr")),
            wrap = if (attribute("wrap") == "none") TextWrap.NONE else TextWrap.SQUARE,
            vertical = TextVertical.of(attribute("vert")),
            rotation = (attribute("rot")?.toDoubleOrNull() ?: 0.0) / ROTATION_UNITS_PER_DEGREE,
            rightToLeftColumns = isSwitch(attribute("rtlCol")),
            columnCount = attribute("numCol")?.toIntOrNull()?.coerceAtLeast(1) ?: 1,
            columnSpacingEmu = attribute("spcCol")?.toDoubleOrNull(),
            warp = warpOf(sources),
            autofit = autofitOf(sources),
            upright = isSwitch(attribute("upright")),
            compatibleLineSpacing = isSwitch(attribute("compatLnSpc")),
        )
    }

    private fun warpOf(sources: List<XNode>): TextWarp? {
        val node = sources.firstNotNullOfOrNull { it.child(OoxmlNamespace.A, "prstTxWarp") } ?: return null
        val preset = node.attribute("prst")?.takeIf { it.isNotBlank() } ?: return null
        val adjusts = node.child(OoxmlNamespace.A, "avLst")?.childrenOf(OoxmlNamespace.A, "gd")
            ?.mapNotNull { guide ->
                val name = guide.attribute("name")
                val formula = guide.attribute("fmla")
                if (name.isNullOrBlank() || formula.isNullOrBlank()) null else name to formula
            }
            ?.toMap()
            ?: emptyMap()
        return TextWarp(preset, adjusts)
    }

    private fun autofitOf(sources: List<XNode>): TextAutofit {
        val element = sources.firstNotNullOfOrNull { body ->
            body.children.firstOrNull { it.name.namespace == OoxmlNamespace.A && it.name.localName in AUTOFIT_TAGS }
        } ?: return TextAutofit.None
        return when (element.name.localName) {
            "normAutofit" -> TextAutofit.Normal(
                // fontScale is thousandths of a percent; absent means 100%, not 1%.
                fontScale = (element.attribute("fontScale")?.toDoubleOrNull() ?: (100.0 * FULL_SCALE)) / FULL_SCALE,
                lineSpacingReduction = (element.attribute("lnSpcReduction")?.toDoubleOrNull() ?: 0.0) / FULL_SCALE,
            )
            "spAutoFit" -> TextAutofit.Shape
            else -> TextAutofit.None
        }
    }

    private fun paragraphOf(
        paragraph: XNode,
        cascade: Cascade,
        context: TextStyleSources,
        budget: TextBudget,
    ): TextParagraph {
        val properties = paragraph.child(OoxmlNamespace.A, "pPr")
        val level = (properties?.attribute("lvl")?.toIntOrNull() ?: 0).coerceIn(0, MAX_LEVEL - 1)
        return TextParagraph(
            level = level,
            properties = paragraphPropertiesOf(cascade.paragraphSources(properties, level), context),
            runs = runsOf(paragraph, properties, cascade, context, level, budget),
            endProperties = runPropertiesOf(
                cascade.runSources(paragraph.child(OoxmlNamespace.A, "endParaRPr"), properties, level),
                context,
            ),
        )
    }

    private fun runsOf(
        paragraph: XNode,
        properties: XNode?,
        cascade: Cascade,
        context: TextStyleSources,
        level: Int,
        budget: TextBudget,
    ): List<TextRun> {
        val runs = ArrayList<TextRun>()
        for (child in paragraph.children) {
            if (child.name.namespace != OoxmlNamespace.A) continue
            val runProperties = child.child(OoxmlNamespace.A, "rPr")
            val style = { runPropertiesOf(cascade.runSources(runProperties, properties, level), context) }
            when (child.name.localName) {
                "r" -> runs += TextRun.Text(
                    budget.take(child.child(OoxmlNamespace.A, "t")?.text.orEmpty()),
                    style(),
                )
                "br" -> runs += TextRun.Break(style())
                "fld" -> runs += TextRun.Field(
                    type = child.attribute("type").orEmpty(),
                    text = budget.take(child.child(OoxmlNamespace.A, "t")?.text.orEmpty()),
                    properties = style(),
                )
            }
        }
        return runs
    }

    private fun paragraphPropertiesOf(sources: List<XNode>, context: TextStyleSources): TextParagraphProperties {
        fun attribute(name: String): String? = sources.firstNotNullOfOrNull { it.attribute(name) }
        return TextParagraphProperties(
            alignment = TextAlignment.of(attribute("algn")),
            marginLeftEmu = attribute("marL")?.toDoubleOrNull(),
            marginRightEmu = attribute("marR")?.toDoubleOrNull(),
            indentEmu = attribute("indent")?.toDoubleOrNull(),
            defaultTabSizeEmu = attribute("defTabSz")?.toDoubleOrNull(),
            tabStops = tabStopsOf(sources),
            rightToLeft = isSwitch(attribute("rtl")),
            lineSpacing = spacingOf(sources, "lnSpc"),
            spaceBefore = spacingOf(sources, "spcBef"),
            spaceAfter = spacingOf(sources, "spcAft"),
            fontAlignment = TextFontAlignment.of(attribute("fontAlgn")),
            eastAsianLineBreak = attribute("eaLnBrk")?.let(::isSwitch) ?: true,
            latinLineBreak = attribute("latinLnBrk")?.let(::isSwitch) ?: true,
            hangingPunctuation = isSwitch(attribute("hangingPunct")),
            bullet = bulletOf(sources, context),
        )
    }

    private fun tabStopsOf(sources: List<XNode>): List<TextTabStop> {
        val list = sources.firstNotNullOfOrNull { it.child(OoxmlNamespace.A, "tabLst") } ?: return emptyList()
        return list.childrenOf(OoxmlNamespace.A, "tab").mapNotNull { tab ->
            val position = tab.attribute("pos")?.toDoubleOrNull() ?: return@mapNotNull null
            TextTabStop(position, TextTabAlignment.of(tab.attribute("algn")))
        }
    }

    private fun spacingOf(sources: List<XNode>, tag: String): TextSpacing? {
        val node = sources.firstNotNullOfOrNull { it.child(OoxmlNamespace.A, tag) } ?: return null
        val percent = node.child(OoxmlNamespace.A, "spcPct")?.attribute("val")?.toDoubleOrNull()
            ?.div(PERCENT_PER_UNIT)
        val points = node.child(OoxmlNamespace.A, "spcPts")?.attribute("val")?.toDoubleOrNull()
            ?.div(POINTS_PER_UNIT)
        return TextSpacing(percent, points)
    }

    private fun bulletOf(sources: List<XNode>, context: TextStyleSources): TextBullet {
        val element = sources.firstNotNullOfOrNull { properties ->
            properties.children.firstOrNull { it.name.namespace == OoxmlNamespace.A && it.name.localName in BULLET_TAGS }
        } ?: return TextBullet.None
        val color = bulletColor(sources, context.colors)
        val size = bulletSize(sources)
        return when (element.name.localName) {
            "buChar" -> TextBullet.Character(
                char = element.attribute("char").orEmpty(),
                font = bulletFont(sources),
                color = color,
                size = size,
            )
            "buAutoNum" -> TextBullet.Numbering(
                scheme = element.attribute("type").orEmpty(),
                startAt = element.attribute("startAt")?.toIntOrNull(),
                color = color,
                size = size,
            )
            "buBlip" -> TextBullet.Picture(
                image = element.child(OoxmlNamespace.A, "blip"),
                color = color,
                size = size,
            )
            else -> TextBullet.None
        }
    }

    private fun bulletColor(sources: List<XNode>, colors: ColorModel): Rgba? {
        for (properties in sources) {
            if (properties.child(OoxmlNamespace.A, "buClrTx") != null) {
                return colors.resolve(ColorValue(ColorSource.Scheme("tx1")))
            }
            val color = properties.child(OoxmlNamespace.A, "buClr") ?: continue
            val value = color.children.firstNotNullOfOrNull(ColorModel::valueOf) ?: continue
            return colors.resolve(value)
        }
        return null
    }

    private fun bulletSize(sources: List<XNode>): TextBulletSize {
        for (properties in sources) {
            if (properties.child(OoxmlNamespace.A, "buSzTx") != null) return TextBulletSize.Inherit
            properties.child(OoxmlNamespace.A, "buSzPct")?.attribute("val")?.toDoubleOrNull()?.let {
                return TextBulletSize.Percent(it / PERCENT_PER_UNIT)
            }
            properties.child(OoxmlNamespace.A, "buSzPts")?.attribute("val")?.toDoubleOrNull()?.let {
                return TextBulletSize.Points(it / POINTS_PER_UNIT)
            }
        }
        return TextBulletSize.Inherit
    }

    private fun bulletFont(sources: List<XNode>): String? {
        for (properties in sources) {
            if (properties.child(OoxmlNamespace.A, "buFontTx") != null) return null
            val typeface = properties.child(OoxmlNamespace.A, "buFont")?.attribute("typeface") ?: continue
            if (typeface.isNotBlank()) return typeface
        }
        return null
    }

    private fun runPropertiesOf(sources: List<XNode>, context: TextStyleSources): TextRunProperties {
        fun attribute(name: String): String? = sources.firstNotNullOfOrNull { it.attribute(name) }
        fun child(tag: String): XNode? = sources.firstNotNullOfOrNull { it.child(OoxmlNamespace.A, tag) }
        val fill = sources.firstNotNullOfOrNull { properties ->
            properties.children.firstOrNull { it.name.namespace == OoxmlNamespace.A && it.name.localName in FILL_TAGS }
        }
        return TextRunProperties(
            sizePt = attribute("sz")?.toDoubleOrNull()?.div(POINTS_PER_UNIT) ?: TextRunProperties.DEFAULT_SIZE_PT,
            bold = isSwitch(attribute("b")),
            italic = isSwitch(attribute("i")),
            underline = underlineOf(attribute("u"), sources, context),
            strike = TextStrike.of(attribute("strike")),
            caps = TextCaps.of(attribute("cap")),
            baseline = (attribute("baseline")?.toDoubleOrNull() ?: 0.0) / BASELINE_UNITS_PER_EM,
            spacingPt = (attribute("spc")?.toDoubleOrNull() ?: 0.0) / POINTS_PER_UNIT,
            kerningPt = (attribute("kern")?.toDoubleOrNull() ?: 0.0) / POINTS_PER_UNIT,
            fill = fill?.let { Fills.paintOf(it, null, context.colors, limits.maxGradientStops) }
                ?: ScenePaint.Solid(context.colors.resolve(ColorValue(ColorSource.Scheme("tx1"))) ?: Rgba.BLACK),
            outline = outlineOf(child("ln"), context),
            shadow = shadowOf(child("effectLst") ?: child("effectDag"), context),
            highlight = child("highlight")?.let { highlight ->
                highlight.children.firstNotNullOfOrNull(ColorModel::valueOf)?.let(context.colors::resolve)
            },
            latinTypeface = typefaceOf(declaredFace(sources, "latin"), context.theme),
            eastAsianTypeface = themedFace(declaredFace(sources, "ea"), context.theme),
            complexScriptTypeface = themedFace(declaredFace(sources, "cs"), context.theme),
            symbolTypeface = themedFace(declaredFace(sources, "sym"), context.theme),
            hyperlink = child("hlinkClick")?.let { hyperlinkOf(it, context) },
            language = attribute("lang"),
            alternativeLanguage = attribute("altLang"),
        )
    }

    private fun underlineOf(value: String?, sources: List<XNode>, context: TextStyleSources): TextUnderline {
        val style = value ?: TextUnderline.NONE_STYLE
        val fillElement = sources.firstNotNullOfOrNull { it.child(OoxmlNamespace.A, "uFill") }
            ?.children
            ?.firstOrNull { it.name.namespace == OoxmlNamespace.A && it.name.localName in FILL_TAGS }
        return TextUnderline(
            style = style,
            fill = fillElement?.let { Fills.paintOf(it, null, context.colors, limits.maxGradientStops) },
            line = sources.firstNotNullOfOrNull { it.child(OoxmlNamespace.A, "uLn") },
        )
    }

    private fun outlineOf(node: XNode?, context: TextStyleSources): TextOutline? {
        if (node == null || node.child(OoxmlNamespace.A, "noFill") != null) return null
        val fill = node.children.firstOrNull {
            it.name.namespace == OoxmlNamespace.A && it.name.localName in FILL_TAGS
        } ?: return null
        val paint = Fills.paintOf(fill, null, context.colors, limits.maxGradientStops)
        if (paint === ScenePaint.None) return null
        val widthPt = node.attribute("w")?.toDoubleOrNull()?.div(EMU_PER_POINT) ?: DEFAULT_LINE_PT
        return TextOutline(widthPt, paint)
    }

    private fun shadowOf(node: XNode?, context: TextStyleSources): TextShadow? {
        val outer = node?.child(OoxmlNamespace.A, "outerShdw") ?: return null
        val color = outer.children.firstNotNullOfOrNull(ColorModel::valueOf)
            ?.let(context.colors::resolve)
            ?: Rgba.BLACK
        return TextShadow(
            blurPt = (outer.attribute("blurRad")?.toDoubleOrNull() ?: 0.0) / EMU_PER_POINT,
            distancePt = (outer.attribute("dist")?.toDoubleOrNull() ?: 0.0) / EMU_PER_POINT,
            directionDeg = (outer.attribute("dir")?.toDoubleOrNull() ?: 0.0) / ROTATION_UNITS_PER_DEGREE,
            color = color,
        )
    }

    private fun hyperlinkOf(node: XNode, context: TextStyleSources): TextHyperlink = TextHyperlink(
        relationshipId = node.attribute(OoxmlNamespace.R, "id"),
        action = node.attribute("action"),
        color = context.colors.resolve(ColorValue(ColorSource.Scheme("hlink"))),
        visitedColor = context.colors.resolve(ColorValue(ColorSource.Scheme("folHlink"))),
    )

    private fun declaredFace(sources: List<XNode>, tag: String): String? =
        sources.firstNotNullOfOrNull { it.child(OoxmlNamespace.A, tag)?.attribute("typeface") }
            ?.takeIf { it.isNotBlank() }

    private fun typefaceOf(declared: String?, theme: Theme?): String? = when {
        declared == null -> theme?.fonts?.minor?.latin
        declared.startsWith("+") -> theme?.fonts?.resolve(declared)
        else -> declared
    }

    private fun themedFace(declared: String?, theme: Theme?): String? = when {
        declared == null -> null
        declared.startsWith("+") -> theme?.fonts?.resolve(declared)
        else -> declared
    }

    private companion object {
        const val MAX_LEVEL = 9

        const val POINTS_PER_UNIT = 100.0

        const val PERCENT_PER_UNIT = 1000.0

        const val BASELINE_UNITS_PER_EM = 100_000.0

        const val ROTATION_UNITS_PER_DEGREE = 60_000.0

        const val EMU_PER_POINT = 12_700.0

        const val DEFAULT_LINE_PT = 0.75

        const val FULL_SCALE = 1000.0

        val FILL_TAGS = setOf("noFill", "solidFill", "gradFill", "blipFill", "pattFill", "grpFill")

        val AUTOFIT_TAGS = setOf("noAutofit", "normAutofit", "spAutoFit")

        val BULLET_TAGS = setOf("buNone", "buChar", "buAutoNum", "buBlip")
    }
}

/** The paragraph- and run-property levels in cascade order, most specific first. */
private class Cascade(private val sources: TextStyleSources) {

    private val levels: List<XNode>
        get() = sources.listStyles + listOfNotNull(sources.masterStyle, sources.defaultTextStyle)

    fun paragraphSources(properties: XNode?, level: Int): List<XNode> = buildList {
        properties?.let(::add)
        for (style in levels) levelStyle(style, level)?.let(::add)
    }

    fun runSources(runProperties: XNode?, paragraphProperties: XNode?, level: Int): List<XNode> = buildList {
        runProperties?.let(::add)
        paragraphProperties?.child(OoxmlNamespace.A, "defRPr")?.let(::add)
        for (style in levels) levelStyle(style, level)?.child(OoxmlNamespace.A, "defRPr")?.let(::add)
    }

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

private fun isSwitch(value: String?): Boolean = value == "1" || value.equals("true", ignoreCase = true)
