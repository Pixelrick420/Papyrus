package io.github.pixelrick420.papyrus.testutil

import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSFloat
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import io.github.pixelrick420.papyrus.viewer.DocFixtures
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Builds large and hostile inputs at test time so fixtures never enter the repository. */
object GeneratedFiles {

    fun bigSingleLineText(bytes: Int): String {
        require(bytes >= 0)
        return String(CharArray(bytes) { ('a'.code + it % 26).toChar() })
    }

    fun corruptPdf(bytes: Int): ByteArray {
        require(bytes >= PDF_HEADER.size)
        return ByteArray(bytes) { index ->
            if (index < PDF_HEADER.size) PDF_HEADER[index] else ((index * 31 + 7) and 0xFF).toByte()
        }
    }

    fun pdfWithZeroSizePage(): ByteArray {
        val rectangle = PDRectangle(COSArray().apply {
            repeat(4) { add(COSFloat(0f)) }
        })
        return ByteArrayOutputStream().use { out ->
            PDDocument().use { document ->
                document.addPage(PDPage(rectangle))
                document.save(out)
            }
            out.toByteArray()
        }
    }

    fun docWithPieces(n: Int): ByteArray {
        require(n >= 0)
        return DocFixtures.doc(pieces = List(n) { DocFixtures.Piece("a") })
    }

    fun zipWithLargeUnusedEntry(bytes: Int): ByteArray {
        require(bytes >= 0)
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("needed.txt"))
            zip.write("hello".toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("unused.bin"))
            zip.write(ByteArray(bytes))
            zip.closeEntry()
        }
        return out.toByteArray()
    }

    private val PDF_HEADER = byteArrayOf(0x25, 0x50, 0x44, 0x46, 0x2D)
}
