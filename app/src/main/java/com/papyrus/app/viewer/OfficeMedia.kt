package com.papyrus.app.viewer

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipInputStream

/** Copy buffer for archive entries; also the first guess for the size of one whose length is unknown. */
internal const val MEDIA_COPY_BUFFER = 64 * 1024

/**
 * The image pass every ZIP-based format shares (DOCX, ODT, EPUB): the images a parser noted as
 * [PendingImage]s are read from the archive in a second walk, charged to one byte budget, written to
 * [mediaDir], and put back into the block list where they appeared. Without a directory there are no
 * images and the text renders alone.
 */
internal class OfficeMedia(
    private val mediaDir: File?,
    private val mimeByName: (String) -> String,
) {

    /**
     * What an insertion did: [at] is the original position (as recorded in [PendingImage.index]) of every
     * image that went in, ascending, so a caller holding block indices of its own can shift them; [dropped]
     * counts references that could not be placed (not in the archive, over budget, block limit reached).
     */
    class Result(val at: List<Int>, val dropped: Int) {
        /** [index] moved down by the images that went in before it, in the list as it is now. */
        fun shifted(index: Int): Int = index + at.count { it < index }
    }

    /**
     * [openStream] must return a fresh stream; the caller owns nothing it returns. Images go in at their
     * recorded positions, not appended, and a block list already at [blockBudget] takes none.
     */
    fun insertImages(
        openStream: () -> InputStream,
        pending: List<PendingImage>,
        blocks: MutableList<OfficeBlock>,
        blockBudget: Int,
    ): Result {
        if (pending.isEmpty() || mediaDir == null) return Result(emptyList(), if (mediaDir == null) pending.size else 0)
        val wanted = pending.mapTo(HashSet()) { it.zipName }
        val budget = MediaBudget()
        // zipName -> the file it was written to, so one entry referenced from several places inserts one
        // block per *reference* while its bytes are read and written once.
        val written = HashMap<String, File>()
        readMedia(openStream, wanted, budget) { zipName, bytes ->
            val file = writeMedia(zipName, bytes) ?: return@readMedia
            written[zipName] = file
        }
        val at = ArrayList<Int>()
        var dropped = 0
        pending.forEach { image ->
            // A dangling relationship or a budget rejection lands here as null; the text still renders.
            val file = written[image.zipName]
            if (file == null || blocks.size >= blockBudget) {
                dropped++
                return@forEach
            }
            // `index` was recorded before any image went in, so each image already inserted pushed
            // it down by one; without the offset, images drifted up and clustered near the first.
            val block = OfficeBlock.Image(file, mimeByName(image.zipName), image.widthDp, image.altText)
            blocks.add((image.index + at.size).coerceIn(0, blocks.size), block)
            at += image.index
        }
        return Result(at, dropped)
    }

    /** Byte budget so photographs cannot fill the disk or heap. Reserve then consume: [canReserve] is
     * the cheap pre-check, [consume] the authoritative charge. */
    private class MediaBudget {
        private var remaining = OfficeTextExtractor.MAX_TOTAL_MEDIA_BYTES
        private var count = 0

        /** True when an entry of [declaredSize] (-1 when unknown) could plausibly be accepted. */
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
        // Two entries in one archive can sanitise to the same name; disambiguate rather than clobber.
        val target = File(dir, OfficeTextExtractor.sanitiseName(zipName))
        if (!target.exists()) return target.writeOrNull(bytes)
        val base = target.nameWithoutExtension
        val ext = target.extension.ifEmpty { "bin" }
        var suffix = 1
        while (true) {
            val candidate = File(dir, "$base-$suffix.$ext")
            if (!candidate.exists()) return candidate.writeOrNull(bytes)
            suffix++
        }
    }

    private fun File.writeOrNull(bytes: ByteArray): File? = try {
        writeBytes(bytes)
        this
    } catch (_: IOException) {
        null
    }

    /** Streams wanted entries to [sink], charging the budget as each arrives rather than buffering
     * them all. The per-entry cap counts bytes, not `entry.size`, which can be -1 when streamed. */
    private fun readMedia(
        openStream: () -> InputStream,
        wanted: Set<String>,
        budget: MediaBudget,
        sink: (zipName: String, bytes: ByteArray) -> Unit,
    ) {
        if (wanted.isEmpty()) return
        openStream().use { input ->
            ZipInputStream(input).use { zip ->
                val buffer = ByteArray(MEDIA_COPY_BUFFER)
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.isDirectory || entry.name !in wanted) continue
                    // Reserve before reading: an entry the budget will never accept must not be decoded at all.
                    if (!budget.canReserve(entry.size)) continue
                    val bytes = readBounded(zip, entry.size, buffer) ?: continue
                    if (!budget.consume(bytes.size)) continue
                    sink(entry.name, bytes)
                }
            }
        }
    }

    /** Reads one entry, capped at the smaller of its declared size and [OfficeTextExtractor.MAX_IMAGE_BYTES]; null if over. */
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
