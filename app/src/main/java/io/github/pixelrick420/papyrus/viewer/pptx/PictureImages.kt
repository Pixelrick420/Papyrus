package io.github.pixelrick420.papyrus.viewer.pptx

import java.io.File

/** The raster file a picture resolves to, with the source pixel size a display list needs to crop it. */
internal class RasterPicture(val file: File, val bounds: ImageBounds)

internal sealed interface PictureContent {

    class Image(val image: PictureImage) : PictureContent

    class Placeholder(val label: String) : PictureContent

    object Missing : PictureContent
}

/**
 * Resolves a picture's relationship to content the paint pass can draw. Metafiles and SVGs fall back to the
 * raster PowerPoint writes beside them; formats the platform cannot decode become a labelled placeholder.
 */
internal class PictureImages(
    private val pkg: PptxPackage,
    private val part: String,
    private val cache: ImageCache,
) {

    fun bounds(source: PictureSource?): ImageBounds? = when (val resolved = resolve(source)) {
        is Resolved.Raster -> cache.bounds(resolved.file)
        else -> null
    }

    fun raster(source: PictureSource?): RasterPicture? = when (val resolved = resolve(source)) {
        is Resolved.Raster -> cache.bounds(resolved.file)?.let { RasterPicture(resolved.file, it) }
        else -> null
    }

    fun content(source: PictureSource?, targetWidthPx: Int): PictureContent =
        when (val resolved = resolve(source)) {
            is Resolved.Raster -> cache.load(resolved.file, targetWidthPx)
                ?.let { PictureContent.Image(it) }
                ?: PictureContent.Placeholder(UNAVAILABLE)
            is Resolved.Fixed -> PictureContent.Placeholder(resolved.label)
            Resolved.Missing -> PictureContent.Missing
        }

    private fun resolve(source: PictureSource?): Resolved {
        val id = source?.relationshipId ?: return Resolved.Missing
        val relationship = pkg.relationship(part, id)?.takeUnless { it.external } ?: return Resolved.Missing
        val target = relationship.target
        val extension = target.substringAfterLast('.', "").lowercase()
        return when (extension) {
            in NATIVE -> pkg.mediaFile(target)?.let { Resolved.Raster(it) } ?: Resolved.Fixed(UNAVAILABLE)
            in METAFILES -> siblingRaster(target)?.let { Resolved.Raster(it) }
                ?: Resolved.Fixed("${extension.uppercase()} image")
            "svg" -> siblingRaster(target)?.let { Resolved.Raster(it) } ?: Resolved.Missing
            in NOT_DECODABLE -> Resolved.Fixed("${extension.uppercase()} image")
            else -> Resolved.Fixed(UNSUPPORTED)
        }
    }

    private fun siblingRaster(target: String): File? {
        val base = target.substringAfterLast('/').substringBeforeLast('.').lowercase()
        val targets = pkg.relationshipsOf(part).map { it.target }
        for (extension in RASTER_SIBLINGS) {
            val sibling = targets.firstOrNull {
                it.substringBeforeLast('.').substringAfterLast('/').equals(base, ignoreCase = true) &&
                    it.substringAfterLast('.', "").equals(extension, ignoreCase = true)
            } ?: continue
            pkg.mediaFile(sibling)?.let { return it }
        }
        return null
    }

    private sealed interface Resolved {

        class Raster(val file: File) : Resolved

        class Fixed(val label: String) : Resolved

        object Missing : Resolved
    }

    private companion object {
        const val UNAVAILABLE = "Image unavailable"
        const val UNSUPPORTED = "Unsupported image"

        val NATIVE = setOf("png", "jpg", "jpeg", "gif", "bmp", "webp")
        val METAFILES = setOf("emf", "wmf")
        val NOT_DECODABLE = setOf("tif", "tiff")
        val RASTER_SIBLINGS = listOf("png", "jpg", "jpeg", "gif", "bmp", "webp")
    }
}
