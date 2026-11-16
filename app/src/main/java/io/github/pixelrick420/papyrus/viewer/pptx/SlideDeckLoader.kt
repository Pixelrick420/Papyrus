package io.github.pixelrick420.papyrus.viewer.pptx

import android.content.Context
import android.net.Uri
import android.util.Xml
import io.github.pixelrick420.papyrus.viewer.OutlineEntry
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
    val slideSize: String?,
    val fontSubstitutions: List<String>,
)

internal object SlideDeckLoader {

    suspend fun load(context: Context, uri: Uri): SlideDeck = withContext(Dispatchers.IO) {
        val source = SlidePageSource.open(context, uri)
        try {
            build(context, source) {
                context.contentResolver.openInputStream(uri) ?: throw FileNotFoundException(uri.toString())
            }
        } catch (e: Exception) {
            source.close()
            throw e
        }
    }

    suspend fun loadFile(context: Context, file: File): SlideDeck = withContext(Dispatchers.IO) {
        val source = SlidePageSource.openFile(context, file)
        try {
            build(context, source) { file.inputStream() }
        } catch (e: Exception) {
            source.close()
            throw e
        }
    }

    private suspend fun build(
        context: Context,
        source: SlidePageSource,
        openStream: () -> InputStream,
    ): SlideDeck {
        val aspectRatios = runCatching { source.loadAspectRatios() }.getOrDefault(emptyList())
        val texts = runCatching { SlideTextExtractor().extract(openStream) }.getOrDefault(emptyList())
        val titles = texts.map { text -> text.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty() }
        val size = slideSize(openStream)
        val substitutions = fontSubstitutions(openStream)
        return SlideDeck(
            source = source,
            aspectRatios = aspectRatios,
            outline = emptyList(),
            hidden = emptySet(),
            incomplete = false,
            titles = titles,
            slideSize = size,
            fontSubstitutions = substitutions,
        )
    }

    private fun slideSize(openStream: () -> InputStream): String? = runCatching {
        val pkg = PptxPackage.read(openStream, mediaDir = null)
        val bytes = pkg.part(pkg.mainPart) ?: return@runCatching null
        val info = PresentationParser().parse(bytes, pkg.mainPart)
        String.format(Locale.US, "%.2f × %.2f in", info.size.widthInches, info.size.heightInches)
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
        val substitution = FontSubstitution()
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
        is FontChoice.System -> choice.family
        is FontChoice.Platform -> "System fallback"
    }

    private val TYPEFACE_TAGS = setOf("latin", "ea", "cs", "sym", "buFont")
}
