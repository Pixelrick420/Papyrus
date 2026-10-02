package com.papyrus.app.scanner

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import kotlin.math.max

object PdfExporter {
    private const val A4_LONG_SIDE_PT = 842f
    private const val JPEG_QUALITY = 0.85f

    suspend fun export(context: Context, pages: List<File>, filter: ScanFilter, target: Uri) =
        withContext(Dispatchers.IO) {
            PDFBoxResourceLoader.init(context.applicationContext)
            PDDocument().use { doc ->
                for (file in pages) {
                    ensureActive()
                    val source = BitmapFactory.decodeFile(file.absolutePath)
                        ?: throw IOException("Unreadable page image: ${file.name}")
                    val filtered = DocumentProcessor.applyFilter(source, filter)
                    try {
                        addPage(doc, filtered)
                    } finally {
                        if (filtered !== source) filtered.recycle()
                        source.recycle()
                    }
                }
                val out = context.contentResolver.openOutputStream(target, "wt")
                    ?: throw IOException("Cannot open $target for writing")
                out.use { doc.save(it) }
            }
        }

    private fun addPage(doc: PDDocument, bitmap: Bitmap) {
        bitmap.setHasAlpha(false) // avoids an extra soft-mask stream per page
        val scale = A4_LONG_SIDE_PT / max(bitmap.width, bitmap.height)
        val w = bitmap.width * scale
        val h = bitmap.height * scale
        val page = PDPage(PDRectangle(w, h))
        doc.addPage(page)
        val image = JPEGFactory.createFromImage(doc, bitmap, JPEG_QUALITY)
        PDPageContentStream(doc, page).use { it.drawImage(image, 0f, 0f, w, h) }
    }
}
