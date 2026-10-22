package com.papyrus.app.ui.viewer

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import com.papyrus.app.viewer.CodeTokenStyles
import com.papyrus.app.viewer.SyntaxTokens
import com.papyrus.app.viewer.TokenKind

/** One style per [TokenKind]; two colour sets, since pale-on-dark and dark-on-pale differ. */
@Immutable
class SyntaxPalette private constructor(private val styles: Array<SpanStyle>) {

    fun styleOf(kind: TokenKind): SpanStyle = styles[kind.ordinal]

    companion object {

        fun forSurface(surface: Color): SyntaxPalette = if (surface.luminance() < DARK_SURFACE_LUMINANCE) Dark else Light

        // Close to GitHub's light and dark code colours: familiar, and checked for contrast on both themes.
        private val Light = build(
            comment = 0xFF6E7781, string = 0xFF0A3069, number = 0xFF0550AE, keyword = 0xFFCF222E, literal = 0xFF0550AE,
            type = 0xFF953800, function = 0xFF8250DF, annotation = 0xFF116329, variable = 0xFF953800,
            property = 0xFF0550AE, tag = 0xFF116329, attribute = 0xFF8250DF,
        )

        private val Dark = build(
            comment = 0xFF8B949E, string = 0xFFA5D6FF, number = 0xFF79C0FF, keyword = 0xFFFF7B72, literal = 0xFF79C0FF,
            type = 0xFFFFA657, function = 0xFFD2A8FF, annotation = 0xFF7EE787, variable = 0xFFFFA657,
            property = 0xFF79C0FF, tag = 0xFF7EE787, attribute = 0xFFD2A8FF,
        )

        private fun build(
            comment: Long, string: Long, number: Long, keyword: Long, literal: Long, type: Long,
            function: Long, annotation: Long, variable: Long, property: Long, tag: Long, attribute: Long,
        ): SyntaxPalette {
            fun style(argb: Long, italic: Boolean = false) =
                SpanStyle(color = Color(argb), fontStyle = if (italic) FontStyle.Italic else null)
            // Indexed by TokenKind.ordinal, so the `when` makes the compiler check that every kind has a colour.
            return SyntaxPalette(
                Array(TokenKind.entries.size) { index ->
                    when (TokenKind.entries[index]) {
                        TokenKind.COMMENT -> style(comment, italic = true)
                        TokenKind.STRING -> style(string)
                        TokenKind.NUMBER -> style(number)
                        TokenKind.KEYWORD -> style(keyword)
                        TokenKind.LITERAL -> style(literal)
                        TokenKind.TYPE -> style(type)
                        TokenKind.FUNCTION -> style(function)
                        TokenKind.ANNOTATION -> style(annotation)
                        TokenKind.VARIABLE -> style(variable)
                        TokenKind.PROPERTY -> style(property)
                        TokenKind.TAG -> style(tag)
                        TokenKind.ATTRIBUTE -> style(attribute)
                    }
                },
            )
        }

        private const val DARK_SURFACE_LUMINANCE = 0.5f
    }
}

/** The same colours for a Markdown code block, which a `TextView` draws with spans and not Compose styles. */
internal fun SyntaxPalette.codeStyles(): CodeTokenStyles {
    val kinds = TokenKind.entries
    return CodeTokenStyles(
        colors = IntArray(kinds.size) { styleOf(kinds[it]).color.toArgb() },
        italics = BooleanArray(kinds.size) { styleOf(kinds[it]).fontStyle == FontStyle.Italic },
    )
}

/** A chunk's tokens with the palette that colours them; one object so it can key a `remember`. */
@Immutable
class SyntaxStyling(val tokens: SyntaxTokens, val palette: SyntaxPalette)

/**
 * [text] with syntax colours and find highlights, highlights added last so a match wins over the
 * colour and gets exactly one style, matching [formattedText]'s Office layering.
 */
internal fun syntaxHighlightedText(text: String, syntax: SyntaxStyling, highlight: FindHighlight?): AnnotatedString {
    val ranges = if (highlight == null) emptyList<IntRange>() else findMatchRanges(text, highlight.query)
    val tokens = syntax.tokens
    if (tokens.size == 0 && ranges.isEmpty()) return AnnotatedString(text)
    return buildAnnotatedString {
        append(text)
        for (i in 0 until tokens.size) {
            val start = tokens.start(i).coerceIn(0, text.length)
            val end = tokens.end(i).coerceIn(start, text.length)
            if (end > start) addStyle(syntax.palette.styleOf(tokens.kind(i)), start, end)
        }
        ranges.forEachIndexed { index, range ->
            addStyle(if (index == highlight?.activeOccurrence) ActiveSpan else OtherSpan, range.first, range.last + 1)
        }
    }
}
