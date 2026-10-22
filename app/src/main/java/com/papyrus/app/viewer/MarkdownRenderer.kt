package com.papyrus.app.viewer

import android.content.Context
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.style.CharacterStyle
import android.text.style.UpdateAppearance
import android.util.TypedValue
import android.widget.TextView
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.Markwon
import io.noties.markwon.MarkwonConfiguration
import io.noties.markwon.ext.latex.JLatexAsyncDrawableSpan
import io.noties.markwon.ext.latex.JLatexMathPlugin
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.ext.tasklist.TaskListPlugin
import io.noties.markwon.image.DefaultDownScalingMediaDecoder
import io.noties.markwon.image.ImagesPlugin
import io.noties.markwon.inlineparser.MarkwonInlineParserPlugin
import io.noties.markwon.syntax.SyntaxHighlight
import org.commonmark.node.Node
import ru.noties.jlatexmath.JLatexMathDrawable
import ru.noties.jlatexmath.awt.Color as FormulaColor

/** The body size Markdown is laid out at before zoom, in sp; math is typeset to match it. */
internal const val MARKDOWN_BASE_TEXT_SP = 16f

/** How each [TokenKind] looks inside a Markdown code block: an ARGB colour and whether it is slanted. */
internal class CodeTokenStyles(val colors: IntArray, val italics: BooleanArray)

/**
 * Builds the [Markwon] for one document. A plugin is added only when the document can use it, so a
 * document without math never loads the inline parser or the formula fonts.
 */
internal object MarkdownRenderer {

    /** An image decoded larger than this is scaled down first: every image in a document loads at once. */
    private const val MAX_IMAGE_PX = 1536

    // A mid-tone set that reads on the light and the dark page alike, because these are fixed when the document
    // is parsed and the theme can change afterwards (the checkbox is a plain Drawable, with no draw-time hook).
    private const val CHECKBOX_FILLED = 0xFF1F6FEB.toInt()
    private const val CHECKBOX_OUTLINE = 0xFF8B949E.toInt()
    private const val CHECKBOX_MARK = 0xFFFFFFFF.toInt()

    fun create(context: Context, source: String): Markwon {
        val math = hasMath(source)
        val builder = Markwon.builder(context)
            .usePlugin(StrikethroughPlugin.create())
            .usePlugin(TablePlugin.create(context))
            .usePlugin(TaskListPlugin.create(CHECKBOX_FILLED, CHECKBOX_OUTLINE, CHECKBOX_MARK))
            .usePlugin(AutolinkPlugin())
            .usePlugin(
                ImagesPlugin.create { images ->
                    // The reader is offline: an image is shown only when the document carries it as a `data:` URI.
                    // Anything else shows its alt text. The handlers are removed, not left to fail for want of the
                    // INTERNET permission, so no build of this app can fetch from a document.
                    images.removeSchemeHandler("http")
                    images.removeSchemeHandler("https")
                    images.defaultMediaDecoder(DefaultDownScalingMediaDecoder.create(context.resources, MAX_IMAGE_PX, MAX_IMAGE_PX))
                    images.errorHandler { _, _ -> null }
                },
            )
            .usePlugin(MarkdownStylePlugin(formulas = math))
        if (math) {
            val textPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, MARKDOWN_BASE_TEXT_SP, context.resources.displayMetrics)
            builder
                // Required for the inline form; it replaces the parser's inline stage, which is why it is not always on.
                .usePlugin(MarkwonInlineParserPlugin.create())
                .usePlugin(JLatexMathPlugin.create(textPx) { it.inlinesEnabled(true) })
        }
        return builder.build()
    }

    /**
     * Markwon's math is `$$ ... $$`, inline or as a block on lines of its own; a single `$` is never math, so
     * prices and shell variables stay text. Without a `$$` there is nothing for the math plugin to find.
     */
    fun hasMath(source: String): Boolean = source.contains("\$\$")
}

/**
 * The parts of a rendered document that depend on the theme, which can change while the document is open and
 * after it was parsed: the colours of the tokens in fenced code blocks, and of formulas.
 *
 * Code is coloured by the app's own tokenizer, the one behind source files, so a block in Markdown looks the
 * same as the same code in a `.kt` file. A fence is coloured by the language it names; no name, or one with no
 * definition, shows the block plain.
 */
internal class MarkdownStylePlugin(private val formulas: Boolean) : AbstractMarkwonPlugin(), SyntaxHighlight {

    /** Null until the viewer supplies a palette; the tokens then draw in the surrounding text colour. */
    @Volatile
    var codeStyles: CodeTokenStyles? = null
        private set

    private var formulaColor = 0

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
        recolorFormulas(markdown, textView.currentTextColor)
    }

    /**
     * Brings the theme-dependent colours up to date for the text already in [view]; the viewer calls it
     * whenever it composes, so it does nothing unless the palette or the text colour actually changed.
     */
    fun applyTheme(view: TextView, styles: CodeTokenStyles) {
        val textColor = view.currentTextColor
        if (styles === codeStyles && textColor == formulaColor) return
        codeStyles = styles
        formulaColor = textColor
        (view.text as? Spanned)?.let { recolorFormulas(it, textColor) }
        view.invalidate()
    }

    /**
     * A formula takes the text colour once, when it first draws, and keeps it. Left alone, a formula drawn
     * under the light theme would stay dark on a page that has since turned dark.
     */
    private fun recolorFormulas(text: Spanned, color: Int) {
        if (!formulas) return
        val spans = text.getSpans(0, text.length, JLatexAsyncDrawableSpan::class.java)
        if (spans.isEmpty()) return
        val foreground = FormulaColor(color)
        for (span in spans) {
            // Explicit getters: Kotlin would resolve `.result` to AsyncDrawable's private field, and the
            // span's own `drawable()` returns a package-private type.
            val icon = (span.getDrawable().getResult() as? JLatexMathDrawable)?.icon() ?: continue
            icon.setForeground(foreground)
        }
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
