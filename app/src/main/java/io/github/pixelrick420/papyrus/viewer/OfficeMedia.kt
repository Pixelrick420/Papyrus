package io.github.pixelrick420.papyrus.viewer

import io.github.pixelrick420.papyrus.viewer.svg.SvgRaster
import io.github.pixelrick420.papyrus.viewer.svg.SvgRasteriser
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.IdentityHashMap
import java.util.zip.ZipInputStream

private const val PNG_MIME = "image/png"

internal const val MEDIA_COPY_BUFFER = 64 * 1024

internal class PreparedImage(val bytes: ByteArray, val mimeType: String, val tintable: Boolean = false)

// Images a parser noted as [PendingImage]s are read in a second walk, charged to one byte budget and written to [mediaDir]; an entry is changed by a transform (an SVG becomes a PNG) before it is charged, so the budget counts what is stored and a refused image leaves no file.
internal class OfficeMedia(
    private val mediaDir: File?,
    private val mimeByName: (String) -> String,
) {

    // [at] lets a caller holding block indices of its own shift them; [dropped] counts references that could not be placed.
    class Result(val at: List<Int>, val dropped: Int) {
        fun shifted(index: Int): Int = index + at.count { it < index }

        // A picture recorded at the same position came before the block, so it moves down past those too; [shifted] is for a place that starts with such a picture.
        fun shiftedPast(index: Int): Int = index + at.count { it <= index }
    }

    // Images go in at their recorded positions, not appended.
    fun insertImages(
        openStream: () -> InputStream,
        pending: List<PendingImage>,
        blocks: MutableList<OfficeBlock>,
        blockBudget: Int,
        transform: (zipName: String, bytes: ByteArray) -> PreparedImage? = { name, bytes -> PreparedImage(bytes, mimeByName(name)) },
        rasterise: SvgRasteriser = SvgRaster,
    ): Result {
        if (pending.isEmpty() || mediaDir == null) return Result(emptyList(), if (mediaDir == null) pending.size else 0)
        val wanted = pending.filter { it.scene == null }.mapTo(HashSet()) { it.zipName }
        val budget = MediaBudget()
        // zipName -> what was written for it, so one entry referenced from several places inserts one
        // block per *reference* while its bytes are read and written once.
        val written = HashMap<String, Written>()
        readMedia(openStream, wanted, budget, transform) { zipName, prepared ->
            val file = writeMedia(zipName, prepared.bytes) ?: return@readMedia
            written[zipName] = Written(prepared, file)
        }
        // Inline drawings have no entry: each is drawn here and charged like any other image.
        val drawn = IdentityHashMap<PendingImage, Written>()
        pending.forEach { image ->
            val scene = image.scene ?: return@forEach
            // As for an entry: a drawing the budget will never accept is not even drawn.
            if (!budget.canReserve(-1)) return@forEach
            val png = try {
                rasterise.rasterise(scene)
            } catch (_: RuntimeException) {
                null
            } ?: return@forEach
            if (!budget.consume(png.size)) return@forEach
            val file = writeMedia("inline-${drawn.size}.svg", png) ?: return@forEach
            drawn[image] = Written(PreparedImage(png, PNG_MIME, tintable = scene.tintable), file)
        }
        val at = ArrayList<Int>()
        var dropped = 0
        pending.forEach { image ->
            // A dangling relationship or a budget rejection lands here as null; the text still renders.
            val done = if (image.scene != null) drawn[image] else written[image.zipName]
            if (done == null || blocks.size >= blockBudget) {
                dropped++
                return@forEach
            }
            // `index` was recorded before any image went in, so each image already inserted pushed
            // it down by one; without the offset, images drifted up and clustered near the first.
            val block = OfficeBlock.Image(
                done.file,
                done.prepared.mimeType,
                image.widthDp,
                image.altText,
                tintable = done.prepared.tintable,
                aspectRatio = image.pageAspect,
            )
            blocks.add((image.index + at.size).coerceIn(0, blocks.size), block)
            at += image.index
        }
        return Result(at, dropped)
    }

    private class Written(val prepared: PreparedImage, val file: File)

    private class MediaBudget {
        private var remaining = OfficeTextExtractor.MAX_TOTAL_MEDIA_BYTES
        private var count = 0

        fun canReserve(declaredSize: Long): Boolean {
            if (count >= OfficeTextExtractor.MAX_IMAGES) return false
            if (declaredSize > OfficeTextExtractor.MAX_IMAGE_BYTES) return false
            if (declaredSize > remaining) return false
            return true
        }

        fun consume(size: Int): Boolean {
            if (size > OfficeTextExtractor.MAX_IMAGE_BYTES || size > remaining) return false
            remaining -= size
            count++
            return true
        }
    }

    private fun writeMedia(zipName: String, bytes: ByteArray): File? {
        val dir = mediaDir ?: return null
        if (!dir.exists() && !dir.mkdirs()) return null
        // Trailing separator, so the prefix covers a whole directory name.
        val root = dir.canonicalPath + File.separator
        // Two entries in one archive can sanitise to the same name; disambiguate rather than clobber.
        val target = File(dir, OfficeTextExtractor.sanitiseName(zipName))
        if (!target.exists()) return target.writeOrNull(bytes, root)
        val base = target.nameWithoutExtension
        val ext = target.extension.ifEmpty { "bin" }
        var suffix = 1
        while (true) {
            val candidate = File(dir, "$base-$suffix.$ext")
            if (!candidate.exists()) return candidate.writeOrNull(bytes, root)
            suffix++
        }
    }

    // Keep the write under the media directory.
    private fun File.writeOrNull(bytes: ByteArray, root: String): File? {
        if (!canonicalPath.startsWith(root)) return null
        return try {
            writeBytes(bytes)
            this
        } catch (_: IOException) {
            null
        }
    }

    // The per-entry cap counts bytes, not `entry.size`, which can be -1 when streamed; the final size is charged, so a change of format cannot get past the budget.
    private fun readMedia(
        openStream: () -> InputStream,
        wanted: Set<String>,
        budget: MediaBudget,
        transform: (zipName: String, bytes: ByteArray) -> PreparedImage?,
        sink: (zipName: String, prepared: PreparedImage) -> Unit,
    ) {
        if (wanted.isEmpty()) return
        openStream().use { input ->
            BoundedZipInputStream(input).use { zip ->
                val buffer = ByteArray(MEDIA_COPY_BUFFER)
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.isDirectory || entry.name !in wanted) continue
                    // Reserve before reading: an entry the budget will never accept must not be decoded at all.
                    if (!budget.canReserve(entry.size)) continue
                    val bytes = readBounded(zip, entry.size, buffer) ?: continue
                    val prepared = try {
                        transform(entry.name, bytes)
                    } catch (_: RuntimeException) {
                        null
                    } ?: continue
                    if (!budget.consume(prepared.bytes.size)) continue
                    sink(entry.name, prepared)
                }
            }
        }
    }

    private fun readBounded(zip: ZipInputStream, declaredSize: Long, buffer: ByteArray): ByteArray? {
        val out = ByteArrayOutputStream(
            if (declaredSize in 1..OfficeTextExtractor.MAX_IMAGE_BYTES) declaredSize.toInt() else MEDIA_COPY_BUFFER,
        )
        while (true) {
            // A sized entry that yields more than it declared is rejected, not truncated into a corrupt image.
            val overBudget = out.size() >= OfficeTextExtractor.MAX_IMAGE_BYTES
            val overDeclared = declaredSize >= 0 && out.size() >= declaredSize
            if (overBudget) return null
            if (overDeclared) return out.toByteArray()
            val n = zip.read(buffer)
            if (n < 0) return out.toByteArray()
            out.write(buffer, 0, n)
        }
    }
}
