package com.papyrus.app.viewer

import android.content.Context
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.style.CharacterStyle
import android.text.style.UpdateAppearance
import android.widget.TextView
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.Markwon
import io.noties.markwon.MarkwonConfiguration
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.ext.tasklist.TaskListPlugin
import io.noties.markwon.image.DefaultDownScalingMediaDecoder
import io.noties.markwon.image.ImagesPlugin
import io.noties.markwon.image.data.DataUriSchemeHandler
import io.noties.markwon.syntax.SyntaxHighlight
import org.commonmark.node.Node

/** The body size Markdown is laid out at before zoom, in sp. */
internal const val MARKDOWN_BASE_TEXT_SP = 16f

/** How each [TokenKind] looks inside a Markdown code block: an ARGB colour and whether it is slanted. */
internal class CodeTokenStyles(val colors: IntArray, val italics: BooleanArray)

/**
 * The result of [MarkdownRenderer.create]: the Markwon instance for synchronous rendering plus the
 * [LatexSpanFactory] holding detected $$...$$ ranges for asynchronous RaTeX span creation.
 */
internal data class MarkdownRenderResult(
    val markwon: Markwon,
    val latexFactory: LatexSpanFactory,
)

/** Builds the [Markwon] used to render one Markdown document. */
internal object MarkdownRenderer {

    /** An image decoded larger than this is scaled down first: every image in a document loads at once. */
    private const val MAX_IMAGE_PX = 1536

    // A mid-tone set that reads on the light and the dark page alike, because these are fixed when the document
    // is parsed and the theme can change afterwards (the checkbox is a plain Drawable, with no draw-time hook).
    private const val CHECKBOX_FILLED = 0xFF1F6FEB.toInt()
    private const val CHECKBOX_OUTLINE = 0xFF8B949E.toInt()
    private const val CHECKBOX_MARK = 0xFFFFFFFF.toInt()

    /**
     * Builds Markwon for [source] and detects any $$...$$ LaTeX formulas in it.
     *
     * @param source the full Markdown document text, scanned for math ranges.
     */
    fun create(context: Context, source: String): MarkdownRenderResult {
        return MarkdownRenderResult(
            markwon = Markwon.builder(context)
                .usePlugin(StrikethroughPlugin.create())
                .usePlugin(TablePlugin.create(context))
                .usePlugin(TaskListPlugin.create(CHECKBOX_FILLED, CHECKBOX_OUTLINE, CHECKBOX_MARK))
                .usePlugin(AutolinkPlugin())
                .usePlugin(
                    ImagesPlugin.create { images ->
                        // Offline security constraint: handlers are stripped so no build can fetch remote images.
                        images.removeSchemeHandler("http")
                        images.removeSchemeHandler("https")
                        // A drawing is a `data:image/svg+xml` URI. Markwon decodes bitmaps and has no SVG, so the subset
                        // renderer the EPUB reader draws with draws it; every other `data:` URI is Markwon's.
                        images.addSchemeHandler(SvgDataUriSchemeHandler(DataUriSchemeHandler.create()))
                        images.defaultMediaDecoder(DefaultDownScalingMediaDecoder.create(context.resources, MAX_IMAGE_PX, MAX_IMAGE_PX))
                        images.errorHandler { _, _ -> null }
                    },
                )
                .usePlugin(MarkdownStylePlugin())
                .build(),
            latexFactory = LatexSpanFactory(context, source),
        )
    }
}

/**
 * The parts of a rendered document that depend on the theme, which can change while the document is open and
 * after it was parsed: the colours of the tokens in fenced code blocks.
 *
 * Code is coloured by the app's own tokenizer, the one behind source files, so a block in Markdown looks the
 * same as the same code in a `.kt` file. A fence is coloured by the language it names; no name, or one with no
 * definition, shows the block plain.
 */
internal class MarkdownStylePlugin : AbstractMarkwonPlugin(), SyntaxHighlight {

    /** Null until the viewer supplies a palette; the tokens then draw in the surrounding text colour. */
    @Volatile
    var codeStyles: CodeTokenStyles? = null
        private set

    // A render can run on a worker thread while another runs on the main one, so the budget is per thread.
    private val budgets = ThreadLocal<IntArray>()

    private fun budget(): IntArray = budgets.get() ?: intArrayOf(HIGHLIGHT_BUDGET_CHARS).also { budgets.set(it) }

    override fun configureConfiguration(builder: MarkwonConfiguration.Builder) {
        builder.syntaxHighlight(this)
    }

    override fun beforeRender(node: Node) {
        budget()[0] = HIGHLIGHT_BUDGET_CHARS
    }

    override fun highlight(info: String?, code: String): CharSequence {
        val language = CodeLanguages.forFence(info) ?: return code
        val remaining = budget()
        // Every token is a span object. A block too long to colour is minified code, and past the document's
        // budget the rest stays plain rather than filling the heap on a file that is mostly code.
        if (code.length > SyntaxHighlighter.MAX_HIGHLIGHT_CHARS || code.length > remaining[0]) return code
        remaining[0] -= code.length
        val tokens = SyntaxHighlighter.tokenize(language, code)
        if (tokens.size == 0) return code
        val colored = SpannableStringBuilder(code)
        for (i in 0 until tokens.size) {
            val start = tokens.start(i)
            val end = tokens.end(i)
            if (end > start) colored.setSpan(TokenSpan(tokens.kind(i), this), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return colored
    }

    override fun beforeSetText(textView: TextView, markdown: Spanned) {
        // No per-document plugin state to apply here.
    }

    /**
     * Brings the theme-dependent colours up to date for the text already in [view]; the viewer calls it
     * whenever it composes, so it does nothing unless the code styles actually changed.
     */
    fun applyTheme(view: TextView, styles: CodeTokenStyles) {
        if (styles === codeStyles) return
        codeStyles = styles
        view.invalidate()
    }

    private companion object {
        /** Characters of fenced code coloured in one render; a few hundred thousand tokens is the heap this allows. */
        const val HIGHLIGHT_BUDGET_CHARS = 256 * 1024
    }
}

/** Colours a token when it draws, so a theme change shows without parsing the document again. */
private class TokenSpan(private val kind: TokenKind, private val owner: MarkdownStylePlugin) : CharacterStyle(), UpdateAppearance {

    override fun updateDrawState(ds: TextPaint) {
        val styles = owner.codeStyles ?: return
        ds.color = styles.colors[kind.ordinal]
        // A skew, not an italic face: the glyph advances, and so the layout, stay what they are.
        if (styles.italics[kind.ordinal]) ds.textSkewX = ITALIC_SKEW
    }

    private companion object {
        const val ITALIC_SKEW = -0.25f
    }
}
