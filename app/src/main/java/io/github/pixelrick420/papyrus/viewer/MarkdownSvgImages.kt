package io.github.pixelrick420.papyrus.viewer

import android.net.Uri
import android.util.Xml
import io.github.pixelrick420.papyrus.viewer.svg.SvgDataUri
import io.github.pixelrick420.papyrus.viewer.svg.SvgRaster
import io.github.pixelrick420.papyrus.viewer.svg.renderSvgDataUri
import io.noties.markwon.image.ImageItem
import io.noties.markwon.image.SchemeHandler
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.io.InputStream

private const val PNG_TYPE = "image/png"

// A URI that cannot be drawn throws, which Markwon turns into the picture's alt text; Markwon's own handler does not unescape the text form.
internal class SvgDataUriSchemeHandler(
    private val standard: SchemeHandler,
    private val draw: (String) -> ByteArray? = ::drawOnPlatform,
) : SchemeHandler() {

    override fun supportedSchemes(): Collection<String> = standard.supportedSchemes()

    override fun handle(raw: String, uri: Uri): ImageItem {
        if (!SvgDataUri.isSvg(raw)) return standard.handle(raw, uri)
        val png = draw(raw) ?: throw IllegalStateException("An SVG data URI that cannot be drawn")
        return ImageItem.withDecodingNeeded(PNG_TYPE, ByteArrayInputStream(png))
    }
}

private fun drawOnPlatform(raw: String): ByteArray? = renderSvgDataUri(raw, ::platformParser, SvgRaster)

/** Namespaces stay in the raw name, as for an EPUB; DOCTYPE processing stays off, so nothing is ever fetched. */
private fun platformParser(stream: InputStream): XmlPullParser =
    Xml.newPullParser().apply {
        setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        setInput(stream, null)
    }
