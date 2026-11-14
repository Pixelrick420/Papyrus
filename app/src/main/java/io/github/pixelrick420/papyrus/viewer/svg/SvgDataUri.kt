package io.github.pixelrick420.papyrus.viewer.svg

import io.github.pixelrick420.papyrus.viewer.ParserFactory
import org.xmlpull.v1.XmlPullParserException
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.Base64

// Never throws: a URI that is not an SVG data URI, is malformed or is too big has no bytes.
object SvgDataUri {

    const val MAX_BYTES = 1024 * 1024

    private const val SCHEME = "data:"
    private const val MEDIA_TYPE = "image/svg+xml"
    private const val HEX_RADIX = 16

    private class Header(val mediaType: String, val base64: Boolean, val dataStart: Int)

    fun isSvg(raw: String): Boolean = header(raw)?.mediaType == MEDIA_TYPE

    fun decode(raw: String, maxBytes: Int = MAX_BYTES): ByteArray? {
        val header = header(raw)?.takeIf { it.mediaType == MEDIA_TYPE } ?: return null
        val data = raw.substring(header.dataStart)
        // Cheap bound first, so a huge string is not walked: a byte takes at most three characters to write
        // (`%3C`), so more than that is over the limit whatever it holds. The exact check follows the decode.
        if (data.length.toLong() > maxBytes.toLong() * CHARS_PER_BYTE + SLACK) return null
        return if (header.base64) base64(data, maxBytes) else percent(data, maxBytes)
    }

    private fun header(raw: String): Header? {
        if (!raw.startsWith(SCHEME, ignoreCase = true)) return null
        val comma = raw.indexOf(',')
        if (comma < 0) return null
        val parts = raw.substring(SCHEME.length, comma).split(';')
        val mediaType = parts.first().trim().lowercase()
        val base64 = parts.drop(1).any { it.trim().equals("base64", ignoreCase = true) }
        return Header(mediaType, base64, comma + 1)
    }

    private fun base64(data: String, maxBytes: Int): ByteArray? {
        // A character of the alphabet may be escaped (`%2B`, and `%3D` for the padding) in a URI.
        val text = if ('%' in data) percent(data, Int.MAX_VALUE)?.toString(Charsets.ISO_8859_1) ?: return null else data
        // Line breaks and spaces a long URI may have been wrapped with are not data; anything else off the
        // alphabet is, and fails the decode.
        val clean = text.filterNot { it.isWhitespace() }
        if (clean.length.toLong() > maxBytes.toLong() * 4 / 3 + SLACK) return null
        val bytes = try {
            Base64.getDecoder().decode(clean)
        } catch (_: IllegalArgumentException) {
            return null
        }
        return bytes.takeIf { it.size <= maxBytes }
    }

    private fun percent(data: String, maxBytes: Int): ByteArray? {
        val out = ByteArrayOutputStream(data.length.coerceAtMost(maxBytes.coerceAtMost(INITIAL_CAPACITY)))
        var literalFrom = 0
        var i = 0
        while (i < data.length) {
            val high = if (data[i] == '%' && i + 2 < data.length) hex(data[i + 1]) else -1
            val low = if (high >= 0) hex(data[i + 2]) else -1
            if (low < 0) {
                i++
                continue
            }
            out.write(data.substring(literalFrom, i).toByteArray(Charsets.UTF_8))
            out.write(high * HEX_RADIX + low)
            i += ESCAPE_LENGTH
            literalFrom = i
            if (out.size() > maxBytes) return null
        }
        out.write(data.substring(literalFrom).toByteArray(Charsets.UTF_8))
        return if (out.size() > maxBytes) null else out.toByteArray()
    }

    private fun hex(c: Char): Int = Character.digit(c, HEX_RADIX)

    private const val SLACK = 16
    private const val CHARS_PER_BYTE = 3
    private const val INITIAL_CAPACITY = 8 * 1024
    private const val ESCAPE_LENGTH = 3
}

// [factory] and [rasteriser] are injected because the platform's parser and canvas are stubs in a JVM unit test.
internal fun renderSvgDataUri(
    raw: String,
    factory: ParserFactory,
    rasteriser: SvgRasteriser,
    limits: SvgLimits = SvgLimits(),
): ByteArray? {
    val bytes = SvgDataUri.decode(raw) ?: return null
    val scene = try {
        parseSvg(bytes, factory, limits)
    } catch (_: XmlPullParserException) {
        null
    } catch (_: IOException) {
        null
    } ?: return null
    return rasteriser.rasterise(scene)
}
