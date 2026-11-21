package io.github.pixelrick420.papyrus.viewer

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSFloat
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class PdfAspectRatioTest {

    @Test
    fun `a page with no width gets a usable ratio`() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "zero-width.pdf")
        try {
            PDDocument().use { document ->
                document.addPage(PDPage(zeroWidthRect()))
                document.save(file)
            }
            val source = PdfPageSource.openFile(file)
            try {
                val ratios = source.loadAspectRatios()
                assertTrue(ratios.isNotEmpty())
                assertTrue(ratios.all { it.isFinite() && it > 0f })
            } finally {
                source.close()
            }
        } finally {
            file.delete()
        }
    }

    private fun zeroWidthRect(): PDRectangle = PDRectangle(COSArray().apply {
        add(COSFloat(0f))
        add(COSFloat(0f))
        add(COSFloat(0f))
        add(COSFloat(100f))
    })
}
