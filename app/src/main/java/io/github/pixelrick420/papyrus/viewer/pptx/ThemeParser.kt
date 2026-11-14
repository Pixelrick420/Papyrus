package io.github.pixelrick420.papyrus.viewer.pptx

import android.util.Xml
import org.xmlpull.v1.XmlPullParser

internal class ThemeFonts(
    val latin: String?,
    val eastAsian: String?,
    val complexScript: String?,
    /** Per-script overrides, keyed by the `script` attribute, for example `Jpan`. */
    val scripts: Map<String, String>,
)

internal class ThemeFontScheme(val major: ThemeFonts, val minor: ThemeFonts) {

    /** The face a theme reference such as `+mj-lt` or `+mn-Jpan` names, or null when it names none. */
    fun resolve(reference: String): String? {
        if (!reference.startsWith("+")) return null
        val body = reference.substring(1)
        val dash = body.indexOf('-')
        if (dash < 0) return null
        val fonts = when (body.substring(0, dash)) {
            "mj" -> major
            "mn" -> minor
            else -> return null
        }
        return when (val script = body.substring(dash + 1)) {
            "lt" -> fonts.latin
            "ea" -> fonts.eastAsian
            "cs" -> fonts.complexScript
            else -> fonts.scripts[script]
        }
    }
}

/** The four `a:fmtScheme` lists, selected by `fillRef`, `lnRef`, `effectRef` and `bgRef`. */
internal class ThemeFormatScheme(
    val fills: List<XNode>,
    val lines: List<XNode>,
    val effects: List<XNode>,
    val backgroundFills: List<XNode>,
) {

    fun fill(idx: Int): XNode? = fills.getOrNull(idx - 1)

    fun line(idx: Int): XNode? = lines.getOrNull(idx - 1)

    fun effect(idx: Int): XNode? = effects.getOrNull(idx - 1)

    fun background(idx: Int): XNode? = backgroundFills.getOrNull(idx - BACKGROUND_BASE)

    private companion object {
        const val BACKGROUND_BASE = 1001
    }
}

internal class Theme(
    val name: String?,
    val colors: ThemeColorScheme,
    val fonts: ThemeFontScheme,
    val formats: ThemeFormatScheme,
)

internal class ThemeParser(
    newParserFactory: () -> XmlPullParser = { Xml.newPullParser() },
    limits: PptxLimits = PptxLimits(),
) {

    private val reader = OoxmlReader(newParserFactory, OoxmlNamespace.CORE, limits)

    fun parse(bytes: ByteArray, part: String = "ppt/theme/theme1.xml"): Theme {
        val root = reader.readTree(bytes.inputStream(), part)
            ?: throw PptxFormatException("$part is empty")
        if (!root.name.matches(OoxmlNamespace.A, "theme")) throw PptxFormatException("$part is not a theme")
        val elements = root.child(OoxmlNamespace.A, "themeElements")
            ?: throw PptxFormatException("$part has no themeElements")
        return Theme(
            name = root.attribute("name"),
            colors = colorsOf(elements.child(OoxmlNamespace.A, "clrScheme")),
            fonts = fontsOf(elements.child(OoxmlNamespace.A, "fontScheme")),
            formats = formatsOf(elements.child(OoxmlNamespace.A, "fmtScheme")),
        )
    }

    private fun colorsOf(node: XNode?): ThemeColorScheme {
        if (node == null) return ThemeColorScheme(emptyMap())
        val slots = LinkedHashMap<String, ColorValue>()
        for (slot in COLOR_SLOTS) {
            val entry = node.child(OoxmlNamespace.A, slot) ?: continue
            val colour = entry.children.firstNotNullOfOrNull(ColorModel::valueOf) ?: continue
            slots[slot] = colour
        }
        return ThemeColorScheme(slots)
    }

    private fun fontsOf(node: XNode?): ThemeFontScheme = ThemeFontScheme(
        major = fontsIn(node?.child(OoxmlNamespace.A, "majorFont")),
        minor = fontsIn(node?.child(OoxmlNamespace.A, "minorFont")),
    )

    private fun fontsIn(node: XNode?): ThemeFonts {
        if (node == null) return ThemeFonts(null, null, null, emptyMap())
        val scripts = LinkedHashMap<String, String>()
        for (child in node.children) {
            if (!child.name.matches(OoxmlNamespace.A, "font")) continue
            val script = child.attribute("script") ?: continue
            val face = child.attribute("typeface")?.takeIf { it.isNotBlank() } ?: continue
            scripts[script] = face
        }
        return ThemeFonts(
            latin = typeface(node.child(OoxmlNamespace.A, "latin")),
            eastAsian = typeface(node.child(OoxmlNamespace.A, "ea")),
            complexScript = typeface(node.child(OoxmlNamespace.A, "cs")),
            scripts = scripts,
        )
    }

    private fun typeface(node: XNode?): String? = node?.attribute("typeface")?.takeIf { it.isNotBlank() }

    private fun formatsOf(node: XNode?): ThemeFormatScheme = ThemeFormatScheme(
        fills = entriesOf(node, "fillStyleLst"),
        lines = entriesOf(node, "lnStyleLst"),
        effects = entriesOf(node, "effectStyleLst"),
        backgroundFills = entriesOf(node, "bgFillStyleLst"),
    )

    private fun entriesOf(node: XNode?, list: String): List<XNode> =
        node?.child(OoxmlNamespace.A, list)?.children.orEmpty()

    private companion object {
        val COLOR_SLOTS = listOf(
            "dk1", "lt1", "dk2", "lt2",
            "accent1", "accent2", "accent3", "accent4", "accent5", "accent6",
            "hlink", "folHlink",
        )
    }
}
