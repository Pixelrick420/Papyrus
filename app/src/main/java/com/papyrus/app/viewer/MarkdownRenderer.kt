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

internal const val MARKDOWN_BASE_TEXT_SP = 16f

internal class CodeTokenStyles(val colors: IntArray, val italics: BooleanArray)

internal data class MarkdownRenderResult(
    val markwon: Markwon,
    val latexFactory: LatexSpanFactory,
)

internal object MarkdownRenderer {

    /** An image decoded larger than this is scaled down first: every image in a document loads at once. */
    private const val MAX_IMAGE_PX = 1536

    // A mid-tone set that reads on the light and the dark page alike, because these are fixed when the document
    // is parsed and the theme can change afterwards (the checkbox is a plain Drawable, with no draw-time hook).
    private const val CHECKBOX_FILLED = 0xFF1F6FEB.toInt()
    private const val CHECKBOX_OUTLINE = 0xFF8B949E.toInt()
    private const val CHECKBOX_MARK = 0xFFFFFFFF.toInt()

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
                        // Markwon has no SVG, so the subset renderer the EPUB reader draws with handles `data:image/svg+xml`; every other `data:` URI is Markwon's.
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

// Theme-dependent token colours are applied after parsing because the theme can change while the document is open.
// Code is coloured by the app's own tokenizer, so a block looks the same as in a `.kt` file; no name, or one with no definition, shows plain.
internal class MarkdownStylePlugin : AbstractMarkwonPlugin(), SyntaxHighlight {

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
    }

    fun applyTheme(view: TextView, styles: CodeTokenStyles) {
        if (styles === codeStyles) return
        codeStyles = styles
        view.invalidate()
    }

    private companion object {
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
