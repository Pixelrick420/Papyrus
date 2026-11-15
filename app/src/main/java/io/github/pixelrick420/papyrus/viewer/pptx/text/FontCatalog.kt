package io.github.pixelrick420.papyrus.viewer.pptx.text

import android.content.res.AssetManager
import android.graphics.Typeface

/** The four faces a font file can carry; [suffix] is the last part of its asset name. */
internal enum class FaceStyle(val suffix: String) {
    REGULAR("Regular"),
    BOLD("Bold"),
    ITALIC("Italic"),
    BOLD_ITALIC("BoldItalic"),
    ;

    companion object {
        fun of(bold: Boolean, italic: Boolean): FaceStyle = when {
            bold && italic -> BOLD_ITALIC
            bold -> BOLD
            italic -> ITALIC
            else -> REGULAR
        }
    }
}

/** An asset path plus whether drawing it in [FaceStyle] needs a synthetic weight or slant. */
internal data class FontFace(val asset: String, val synthetic: Boolean = false)

/**
 * A bundled open family. The faces are renamed (`Pptx Humanist`, not Carlito) because the OFL reserves
 * those names for unmodified copies, so lookups by the original typeface name go through
 * [FontSubstitution] instead.
 */
internal class BundledFont(
    val family: String,
    private val directory: String,
    private val prefix: String,
    private val styles: Set<FaceStyle> = FaceStyle.entries.toSet(),
) {

    fun face(style: FaceStyle): FontFace {
        val base = baseFor(style)
        return FontFace("ooxml/fonts/$directory/$prefix-${base.suffix}.ttf", synthetic = base != style)
    }

    private fun baseFor(style: FaceStyle): FaceStyle = when {
        style in styles -> style
        style == FaceStyle.BOLD_ITALIC ->
            listOf(FaceStyle.BOLD, FaceStyle.ITALIC, FaceStyle.REGULAR).firstOrNull { it in styles }
        else -> FaceStyle.REGULAR.takeIf { it in styles }
    } ?: styles.first()

    companion object {

        val CARLITO = BundledFont("Pptx Humanist", "humanist", "PptxHumanist")

        val ARIMO = BundledFont("Pptx Sans", "metric", "PptxSans")

        val TINOS = BundledFont("Pptx Serif", "metric", "PptxSerif")

        val COUSINE = BundledFont("Pptx Mono", "metric", "PptxMono")

        val CALADEA = BundledFont("Caladea", "caladea", "Caladea")

        val ALL = listOf(CARLITO, ARIMO, TINOS, COUSINE, CALADEA)
    }
}

/**
 * Resolves a face to a [Typeface]. Bundled faces load from assets on first use and are cached for the
 * life of the deck, which is what keeps a slide with one family off the asset path per glyph run.
 */
internal class FontCatalog(
    private val assets: AssetManager,
    private val system: (String, Int) -> Typeface = { family, style -> Typeface.create(family, style) },
) {

    private val cache = LazyCache<String, Typeface>()

    fun bundled(font: BundledFont, bold: Boolean, italic: Boolean): Typeface {
        val style = FaceStyle.of(bold, italic)
        val face = font.face(style)
        val base = cache.get(face.asset) {
            runCatching { Typeface.createFromAsset(assets, it) }.getOrNull()
        } ?: return Typeface.DEFAULT
        return if (face.synthetic) Typeface.create(base, style.android()) else base
    }

    fun system(family: String, bold: Boolean, italic: Boolean): Typeface {
        val style = FaceStyle.of(bold, italic).android()
        return cache.get("family:$family:$style") { system(family, style) } ?: Typeface.DEFAULT
    }
}

/** Caches a null result too: a font that failed to load must not be retried for every glyph run. */
internal class LazyCache<K, V> {

    private val values = HashMap<K, V?>()

    fun get(key: K, load: (K) -> V?): V? =
        if (values.containsKey(key)) values[key] else load(key).also { values[key] = it }
}

private fun FaceStyle.android(): Int = when (this) {
    FaceStyle.REGULAR -> Typeface.NORMAL
    FaceStyle.BOLD -> Typeface.BOLD
    FaceStyle.ITALIC -> Typeface.ITALIC
    FaceStyle.BOLD_ITALIC -> Typeface.BOLD_ITALIC
}
