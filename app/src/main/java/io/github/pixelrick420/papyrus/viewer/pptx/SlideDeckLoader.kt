package io.github.pixelrick420.papyrus.viewer.pptx

import android.content.Context
import android.net.Uri
import io.github.pixelrick420.papyrus.R
import io.github.pixelrick420.papyrus.viewer.OutlineEntry
import io.github.pixelrick420.papyrus.viewer.PdfPageLink
import io.github.pixelrick420.papyrus.viewer.pptx.text.FontChoice
import io.github.pixelrick420.papyrus.viewer.pptx.text.FontSubstitution
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class SlideDeck(
    val source: SlidePageSource,
    val aspectRatios: List<Float>,
    val outline: List<OutlineEntry>,
    val hidden: Set<Int>,
    val incomplete: Boolean,
    val titles: List<String>,
    val slideSize: String?,
    val fontSubstitutions: List<String>,
    val textIndex: SlideTextIndex = SlideTextIndex(emptyList()),
    val links: List<List<PdfPageLink>> = emptyList(),
    val semanticTexts: List<String> = emptyList(),
)

internal object SlideDeckLoader {

    @Suppress("TooGenericExceptionCaught")
    suspend fun load(context: Context, uri: Uri): SlideDeck = withContext(Dispatchers.IO) {
        val source = SlidePageSource.open(context, uri, buildTextIndex = false, labels = pictureLabels(context))
        try {
            build(context, source)
        } catch (e: Exception) {
            source.close()
            throw e
        }
    }

    @Suppress("TooGenericExceptionCaught")
    suspend fun loadFile(context: Context, file: File): SlideDeck = withContext(Dispatchers.IO) {
        val source = SlidePageSource.openFile(context, file, buildTextIndex = false, labels = pictureLabels(context))
        try {
            build(context, source)
        } catch (e: Exception) {
            source.close()
            throw e
        }
    }

    private suspend fun build(context: Context, source: SlidePageSource): SlideDeck {
        val aspectRatios = recoverSuspend(emptyList<Float>()) { source.loadAspectRatios() }
        val textIndex = source.textIndex
        val texts = textIndex.pages.map { it.text }
        val titles = textIndex.titles.takeIf { it.size == texts.size && it.any { name -> name.isNotBlank() } }
            ?: texts.map { text -> text.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty() }
        val info = source.presentationInfo
        val substitutions = fontSubstitutions(source.pkg, source.fontSubstitution, context)
        val size = context.getString(
            R.string.viewer_info_slide_size_value,
            info.size.widthInches,
            info.size.heightInches,
        )
        return SlideDeck(
            source = source,
            aspectRatios = aspectRatios,
            outline = slideOutline(info, titles) { index -> context.getString(R.string.viewer_outline_slide, index + 1) },
            hidden = source.hidden,
            incomplete = source.isIncomplete(),
            titles = titles,
            slideSize = size,
            fontSubstitutions = substitutions,
            textIndex = textIndex,
            links = textIndex.links,
            semanticTexts = textIndex.semanticTexts,
        )
    }

    private fun fontSubstitutions(
        pkg: PptxPackage,
        substitution: FontSubstitution,
        context: Context,
    ): List<String> = recover(emptyList()) {
        pkg.requestedTypefaces().mapNotNull { name ->
            val choice = substitution.substitute(name)
            if (choice.substituted && !choice.compatible) "$name → ${labelOf(choice, context)}" else null
        }.distinct()
    }

    private fun labelOf(choice: FontChoice, context: Context): String = when (choice) {
        is FontChoice.Bundled -> choice.font.family
        is FontChoice.Embedded -> choice.font.family
        is FontChoice.System -> choice.family
        is FontChoice.Platform -> context.getString(R.string.viewer_info_font_system_fallback)
    }

    internal fun pictureLabels(context: Context): PictureLabels = PictureLabels(
        unavailable = context.getString(R.string.viewer_picture_unavailable),
        unsupported = context.getString(R.string.viewer_picture_unsupported),
        extensionImage = { context.getString(R.string.viewer_picture_extension, it) },
        svg = context.getString(R.string.viewer_picture_svg),
    )
}
