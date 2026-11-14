package io.github.pixelrick420.papyrus.viewer.pptx

import android.util.Xml
import org.xmlpull.v1.XmlPullParser

internal class SlideSize(val widthEmu: Long, val heightEmu: Long) {

    val widthInches: Double get() = widthEmu / EMU_PER_INCH

    val heightInches: Double get() = heightEmu / EMU_PER_INCH

    companion object {
        const val EMU_PER_INCH = 914_400.0

        val DEFAULT = SlideSize(9_144_000, 6_858_000)
    }
}

/** One `p:sldId`: the id PowerPoint keeps on the slide, and the relationship that names its part. */
internal class SlideRef(val id: Long, val relationshipId: String)

/** One `p14:section`, holding the ids of the slides it covers in presentation order. */
internal class Section(val id: String?, val name: String?, val slideIds: List<Long>)

internal class PresentationInfo(
    val size: SlideSize,
    val slides: List<SlideRef>,
    val sections: List<Section>,
    /** The `p:defaultTextStyle` list style, held whole for the text parser. */
    val defaultTextStyle: XNode?,
    val firstSlideNumber: Int,
)

internal class PresentationParser(
    newParserFactory: () -> XmlPullParser = { Xml.newPullParser() },
    private val limits: PptxLimits = PptxLimits(),
) {

    private val reader = OoxmlReader(newParserFactory, OoxmlNamespace.CORE + OoxmlNamespace.P14, limits)

    fun parse(bytes: ByteArray, part: String = "ppt/presentation.xml"): PresentationInfo {
        val root = reader.readTree(bytes.inputStream(), part)
            ?: throw PptxFormatException("$part is empty")
        if (!root.name.matches(OoxmlNamespace.P, "presentation")) {
            throw PptxFormatException("$part is not a presentation")
        }
        return PresentationInfo(
            size = sizeOf(root.child(OoxmlNamespace.P, "sldSz")),
            slides = slidesOf(root.child(OoxmlNamespace.P, "sldIdLst")),
            sections = sectionsOf(root.child(OoxmlNamespace.P14, "sectionLst")),
            defaultTextStyle = root.child(OoxmlNamespace.P, "defaultTextStyle"),
            firstSlideNumber = root.attribute("firstSlideNum")?.toIntOrNull() ?: DEFAULT_FIRST_SLIDE,
        )
    }

    private fun sizeOf(node: XNode?): SlideSize {
        val width = node?.attribute("cx")?.toLongOrNull()
        val height = node?.attribute("cy")?.toLongOrNull()
        return SlideSize(clamp(width, SlideSize.DEFAULT.widthEmu), clamp(height, SlideSize.DEFAULT.heightEmu))
    }

    private fun clamp(value: Long?, fallback: Long): Long {
        if (value == null || value <= 0) return fallback
        return value.coerceAtMost(limits.maxSlideEdgeInches * EMU_PER_INCH_LONG)
    }

    private fun slidesOf(node: XNode?): List<SlideRef> = node
        ?.childrenOf(OoxmlNamespace.P, "sldId")
        .orEmpty()
        .mapNotNull { entry ->
            val id = entry.attribute("id")?.toLongOrNull() ?: return@mapNotNull null
            val relationship = entry.attribute(OoxmlNamespace.R, "id") ?: return@mapNotNull null
            SlideRef(id, relationship)
        }
        .take(limits.maxSlides)

    private fun sectionsOf(node: XNode?): List<Section> = node
        ?.childrenOf(OoxmlNamespace.P14, "section")
        .orEmpty()
        .map { entry ->
            Section(
                id = entry.attribute("id"),
                name = entry.attribute("name"),
                slideIds = entry.child(OoxmlNamespace.P14, "sldIdLst")
                    ?.childrenOf(OoxmlNamespace.P14, "sldId")
                    .orEmpty()
                    .mapNotNull { it.attribute("id")?.toLongOrNull() },
            )
        }

    private companion object {
        const val DEFAULT_FIRST_SLIDE = 1

        const val EMU_PER_INCH_LONG = 914_400L
    }
}
