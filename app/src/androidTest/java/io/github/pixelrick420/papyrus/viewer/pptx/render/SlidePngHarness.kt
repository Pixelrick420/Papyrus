package io.github.pixelrick420.papyrus.viewer.pptx.render

import android.content.Context
import android.graphics.Bitmap
import io.github.pixelrick420.papyrus.viewer.pptx.PresetGeometry
import io.github.pixelrick420.papyrus.viewer.pptx.SlidePageSource
import java.io.File

/** Test-only bridge from a deck's bytes to a rendered slide, for the raster goldens. */
internal object SlidePngHarness {

    suspend fun render(context: Context, deck: ByteArray, slide: Int, widthPx: Int): Bitmap? {
        val media = File(context.cacheDir, "golden-media").apply {
            deleteRecursively()
            mkdirs()
        }
        val source = SlidePageSource.openStream(
            open = { deck.inputStream() },
            mediaDir = media,
            presets = PresetGeometry(open = { context.assets.open("ooxml/presetShapeDefinitions.xml") }),
        )
        return try {
            source.render(slide, widthPx)
        } finally {
            source.close()
        }
    }
}
