package io.github.pixelrick420.papyrus.viewer.pptx

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import io.github.pixelrick420.papyrus.viewer.pptx.meta.AndroidMetafileDecoder
import io.github.pixelrick420.papyrus.viewer.pptx.meta.MetafileDecoder
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import kotlin.math.min
import kotlin.math.roundToInt

/** The raster file a picture resolves to, with the source pixel size a display list needs to crop it. */
internal class RasterPicture(val file: File, val bounds: ImageBounds)

internal sealed interface PictureContent {

    class Image(val image: PictureImage) : PictureContent

    class Placeholder(val label: String) : PictureContent

    object Missing : PictureContent
}

/**
 * Resolves a picture's relationship to content the paint pass can draw. Metafiles and undecodable formats become
 * bounded PNGs in the media dir, so the display list keeps one image path for every picture it can show.
 */
internal class PictureImages(
    private val pkg: PptxPackage,
    private val part: String,
    private val cache: ImageCache,
    private val mediaDir: File,
    private val maxEdgePx: Int = PptxLimits().maxRenderEdgePx,
    private val metafiles: MetafileDecoder = AndroidMetafileDecoder(maxEdgePx),
    private val generated: MutableMap<String, RasterPicture> = HashMap(),
) {

    fun bounds(source: PictureSource?): ImageBounds? = when (val resolved = resolve(source)) {
        is Resolved.Raster -> cache.bounds(resolved.file)
        is Resolved.Metafile -> metafiles.bounds(resolved.file)
        else -> null
    }

    fun raster(source: PictureSource?, frame: SceneRect? = null): RasterPicture? = when (val resolved = resolve(source)) {
        is Resolved.Raster -> cache.bounds(resolved.file)?.let { RasterPicture(resolved.file, it) }
        is Resolved.Metafile -> {
            val width = metafiles.bounds(resolved.file)?.width?.coerceAtMost(RASTER_EDGE) ?: RASTER_EDGE
            metafiles.load(resolved.file, width)?.let { generated("metafile:${resolved.file.path}:${it.bounds.width}", it) }
        }
        is Resolved.Fixed -> generated("placeholder:${resolved.label}:${frame?.width}:${frame?.height}", placeholder(resolved.label, frame))
        Resolved.Missing -> null
    }

    fun content(source: PictureSource?, targetWidthPx: Int): PictureContent =
        when (val resolved = resolve(source)) {
            is Resolved.Raster -> cache.load(resolved.file, targetWidthPx)
                ?.let { PictureContent.Image(it) }
                ?: PictureContent.Placeholder(UNAVAILABLE)
            is Resolved.Metafile -> metafiles.load(resolved.file, targetWidthPx)
                ?.let { PictureContent.Image(it) }
                ?: PictureContent.Placeholder(UNAVAILABLE)
            is Resolved.Fixed -> PictureContent.Placeholder(resolved.label)
            Resolved.Missing -> PictureContent.Missing
        }

    fun trimMemory(evictAll: Boolean) = metafiles.trimMemory(evictAll)

    private fun resolve(source: PictureSource?): Resolved {
        val id = source?.relationshipId ?: return Resolved.Missing
        val resolvePart = source.part ?: part
        val relationship = pkg.relationship(resolvePart, id)?.takeUnless { it.external } ?: return Resolved.Missing
        val target = relationship.target
        val extension = target.substringAfterLast('.', "").lowercase()
        return when (extension) {
            in NATIVE -> pkg.mediaFile(target)?.let { Resolved.Raster(it) } ?: Resolved.Fixed(UNAVAILABLE)
            in METAFILES -> {
                val raster = siblingRaster(resolvePart, target)
                if (raster != null) Resolved.Raster(raster)
                else {
                    val file = pkg.mediaFile(target)
                    val label = file?.let(metafiles::placeholder)
                    when {
                        label != null -> Resolved.Fixed(label)
                        file != null && metafiles.bounds(file) != null -> Resolved.Metafile(file)
                        else -> Resolved.Fixed("${extension.uppercase()} image")
                    }
                }
            }
            "svg" -> siblingRaster(resolvePart, target)?.let { Resolved.Raster(it) } ?: Resolved.Fixed("SVG image")
            in NOT_DECODABLE -> Resolved.Fixed("${extension.uppercase()} image")
            else -> Resolved.Fixed(UNSUPPORTED)
        }
    }

    private fun siblingRaster(resolvePart: String, target: String): File? {
        val base = target.substringAfterLast('/').substringBeforeLast('.').lowercase()
        val targets = pkg.relationshipsOf(resolvePart).map { it.target }
        for (extension in RASTER_SIBLINGS) {
            val sibling = targets.firstOrNull {
                it.substringBeforeLast('.').substringAfterLast('/').equals(base, ignoreCase = true) &&
                    it.substringAfterLast('.', "").equals(extension, ignoreCase = true)
            } ?: continue
            pkg.mediaFile(sibling)?.let { return it }
        }
        return null
    }

    private fun generated(key: String, image: PictureImage): RasterPicture? {
        synchronized(generated) {
            generated[key]?.let { return it }
            val file = File(mediaDir, "gen-${digest(key)}.png")
            val written = try {
                FileOutputStream(file).use { image.bitmap.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, it) }
            } catch (_: Exception) {
                false
            }
            if (!written) return null
            val raster = RasterPicture(file, ImageBounds(image.bitmap.width, image.bitmap.height))
            generated[key] = raster
            return raster
        }
    }

    private fun placeholder(label: String, frame: SceneRect?): PictureImage {
        val aspect = if (frame != null && frame.width > 0.0 && frame.height > 0.0) {
            (frame.height / frame.width).coerceIn(MIN_ASPECT, MAX_ASPECT)
        } else 1.0
        val edge = min(PLACEHOLDER_EDGE, maxEdgePx)
        val width = maxOf(1, if (aspect >= 1.0) (edge / aspect).roundToInt() else edge)
        val height = maxOf(1, (width * aspect).roundToInt())
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = PLACEHOLDER_FILL
            style = Paint.Style.FILL
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fill)
        val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = PLACEHOLDER_BORDER
            style = Paint.Style.STROKE
            strokeWidth = (min(width, height) * BORDER_FRACTION).coerceAtLeast(1f)
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), border)
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = PLACEHOLDER_TEXT
            textAlign = Paint.Align.CENTER
            textSize = (min(width, height) * TEXT_FRACTION).coerceAtLeast(1f)
        }
        val available = width * TEXT_WIDTH_FRACTION
        val measured = text.measureText(label)
        if (measured > available && measured > 0f) text.textSize *= available / measured
        val metrics = text.fontMetrics
        canvas.drawText(label, width / 2f, height / 2f - (metrics.ascent + metrics.descent) / 2f, text)
        return PictureImage(bitmap, ImageBounds(width, height))
    }

    private fun digest(value: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        val out = StringBuilder(bytes.size * 2)
        for (byte in bytes.take(DIGEST_BYTES)) {
            out.append(HEX[(byte.toInt() shr 4) and 0xF])
            out.append(HEX[byte.toInt() and 0xF])
        }
        return out.toString()
    }

    private sealed interface Resolved {

        class Raster(val file: File) : Resolved

        class Metafile(val file: File) : Resolved

        class Fixed(val label: String) : Resolved

        object Missing : Resolved
    }

    private companion object {
        const val UNAVAILABLE = "Image unavailable"
        const val UNSUPPORTED = "Unsupported image"

        const val PNG_QUALITY = 100
        const val RASTER_EDGE = 2_048
        const val PLACEHOLDER_EDGE = 512
        const val MIN_ASPECT = 0.05
        const val MAX_ASPECT = 20.0
        const val BORDER_FRACTION = 0.004f
        const val TEXT_FRACTION = 0.14f
        const val TEXT_WIDTH_FRACTION = 0.8f
        const val DIGEST_BYTES = 16
        const val PLACEHOLDER_FILL = 0xFFEFEFEF.toInt()
        const val PLACEHOLDER_BORDER = 0xFFB0B0B0.toInt()
        const val PLACEHOLDER_TEXT = 0xFF616161.toInt()
        val HEX = "0123456789abcdef".toCharArray()

        val NATIVE = setOf("png", "jpg", "jpeg", "gif", "bmp", "webp")
        val METAFILES = setOf("emf", "wmf")
        val NOT_DECODABLE = setOf("tif", "tiff")
        val RASTER_SIBLINGS = listOf("png", "jpg", "jpeg", "gif", "bmp", "webp")
    }
}
