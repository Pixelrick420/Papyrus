package com.papyrus.app.viewer

import android.net.Uri
import android.util.Xml
import com.papyrus.app.viewer.svg.SvgDataUri
import com.papyrus.app.viewer.svg.SvgRaster
import com.papyrus.app.viewer.svg.renderSvgDataUri
import io.noties.markwon.image.ImageItem
import io.noties.markwon.image.SchemeHandler
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.io.InputStream

private const val PNG_TYPE = "image/png"

/**
 * Markwon's `data:` handler, except that a `data:image/svg+xml` URI is drawn by [renderSvgDataUri], which is
 * how an EPUB's drawings are drawn, and handed on as the PNG it makes. Markwon decodes a bitmap and has no
 * SVG, and its own handler does not unescape the text form. A URI that cannot be drawn throws, which Markwon
 * turns into the picture's alt text, as it does for any image the reader cannot show; every other URI is the
 * [standard] handler's.
 */
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
