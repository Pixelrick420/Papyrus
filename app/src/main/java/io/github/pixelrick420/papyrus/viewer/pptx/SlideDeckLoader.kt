package io.github.pixelrick420.papyrus.viewer.pptx

import android.content.Context
import android.net.Uri
import android.util.Xml
import io.github.pixelrick420.papyrus.viewer.OutlineEntry
import io.github.pixelrick420.papyrus.viewer.pptx.text.EmbeddedFontDecoder
import io.github.pixelrick420.papyrus.viewer.pptx.text.FontChoice
import io.github.pixelrick420.papyrus.viewer.pptx.text.FontSubstitution
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class SlideDeck(
    val source: SlidePageSource,
    val aspectRatios: List<Float>,
    val outline: List<OutlineEntry>,
    val hidden: Set<Int>,
    val incomplete: Boolean,
    val titles: List<String>,
    val slideTexts: List<String>,
    val slideSize: String?,
    val fontSubstitutions: List<String>,
    val textIndex: SlideTextIndex = SlideTextIndex(emptyList()),
    val links: List<List<io.github.pixelrick420.papyrus.viewer.PdfPageLink>> = emptyList(),
    val semanticTexts: List<String> = emptyList(),
)

internal object SlideDeckLoader {

    @Suppress("TooGenericExceptionCaught")
    suspend fun load(context: Context, uri: Uri): SlideDeck = withContext(Dispatchers.IO) {
        val source = SlidePageSource.open(context, uri)
        try {
            build(source) {
                context.contentResolver.openInputStream(uri) ?: throw FileNotFoundException(uri.toString())
            }
        } catch (e: Exception) {
            source.close()
            throw e
        }
    }

    @Suppress("TooGenericExceptionCaught")
    suspend fun loadFile(context: Context, file: File): SlideDeck = withContext(Dispatchers.IO) {
        val source = SlidePageSource.openFile(context, file)
        try {
            build(source) { file.inputStream() }
        } catch (e: Exception) {
            source.close()
            throw e
        }
    }

    private suspend fun build(
        source: SlidePageSource,
        openStream: () -> InputStream,
    ): SlideDeck {
        val aspectRatios = runCatching { source.loadAspectRatios() }.getOrDefault(emptyList())
        val textIndex = source.textIndex
        val texts = if (textIndex.pages.isNotEmpty()) {
            textIndex.pages.map { it.text }
        } else {
            runCatching { SlideTextExtractor().extract(openStream) }.getOrDefault(emptyList())
        }
        val titles = texts.map { text -> text.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty() }
        val info = presentationInfo(openStream)
        val size = info?.let { String.format(Locale.US, "%.2f × %.2f in", it.size.widthInches, it.size.heightInches) }
        val substitutions = fontSubstitutions(openStream)
        return SlideDeck(
            source = source,
            aspectRatios = aspectRatios,
            outline = info?.let { slideOutline(it, titles) }.orEmpty(),
            hidden = source.hidden,
            incomplete = false,
            titles = titles,
            slideTexts = texts,
            slideSize = size,
            fontSubstitutions = substitutions,
            textIndex = textIndex,
            links = textIndex.links,
            semanticTexts = textIndex.semanticTexts,
        )
    }

    private fun presentationInfo(openStream: () -> InputStream): PresentationInfo? = runCatching {
        val pkg = PptxPackage.read(openStream, mediaDir = null)
        val bytes = pkg.part(pkg.mainPart) ?: return@runCatching null
        PresentationParser().parse(bytes, pkg.mainPart)
    }.getOrNull()

    private fun fontSubstitutions(openStream: () -> InputStream): List<String> = runCatching {
        val pkg = PptxPackage.read(openStream, mediaDir = null)
        val reader = OoxmlReader({ Xml.newPullParser() }, OoxmlNamespace.CORE, PptxLimits())
        val requested = linkedSetOf<String>()
        for (name in pkg.partNames) {
            if (!name.endsWith(".xml", ignoreCase = true)) continue
            val bytes = pkg.part(name) ?: continue
            val root = runCatching { reader.readTree(bytes.inputStream(), name) }.getOrNull() ?: continue
            collectTypefaces(root, requested)
        }
        val embedded = pkg.fontNames.mapNotNull { name -> pkg.font(name)?.let { EmbeddedFontDecoder.decode(name, it) } }
        val substitution = FontSubstitution(embedded = embedded)
        requested.mapNotNull { name ->
            val choice = substitution.substitute(name)
            if (choice.substituted && !choice.compatible) "$name → ${labelOf(choice)}" else null
        }.distinct()
    }.getOrDefault(emptyList())

    private fun collectTypefaces(node: XNode, out: MutableSet<String>) {
        if (node.name.localName in TYPEFACE_TAGS) {
            node.attribute("typeface")?.trim()?.takeIf { it.isNotEmpty() && !it.startsWith('+') }?.let(out::add)
        }
        for (child in node.children) collectTypefaces(child, out)
    }

    private fun labelOf(choice: FontChoice): String = when (choice) {
        is FontChoice.Bundled -> choice.font.family
        is FontChoice.Embedded -> choice.font.family
        is FontChoice.System -> choice.family
        is FontChoice.Platform -> "System fallback"
    }

    private val TYPEFACE_TAGS = setOf("latin", "ea", "cs", "sym", "buFont")
}
