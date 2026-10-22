package com.papyrus.app.viewer

import org.commonmark.node.AbstractVisitor
import org.commonmark.node.Link
import org.commonmark.node.Text
import org.commonmark.parser.Parser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownAutolinkTest {

    private val parser = Parser.builder().postProcessor(AutolinkPostProcessor).build()

    /** The destination of every link in [markdown], whether the author wrote it or the post-processor added it. */
    private fun links(markdown: String): List<String> {
        val found = ArrayList<String>()
        parser.parse(markdown).accept(object : AbstractVisitor() {
            override fun visit(link: Link) {
                found.add(link.destination)
                // Into the label too, so a link nested in another one is seen.
                visitChildren(link)
            }
        })
        return found
    }

    /** All the text in reading order, which links must not change. */
    private fun text(markdown: String): String {
        val out = StringBuilder()
        parser.parse(markdown).accept(object : AbstractVisitor() {
            override fun visit(text: Text) {
                out.append(text.literal)
            }
        })
        return out.toString()
    }

    @Test
    fun `bare addresses in a paragraph become links`() {
        assertEquals(
            listOf("https://example.com/docs", "mailto:me@x.org"),
            links("Docs at https://example.com/docs, mail me@x.org"),
        )
    }

    @Test
    fun `the text around a link is kept in order`() {
        val markdown = "before https://example.com/a. after, and www.b.org too"
        assertEquals(markdown, text(markdown))
    }

    @Test
    fun `an address in an author-written link label is not linked again`() {
        assertEquals(listOf("https://real.example"), links("[https://label.example](https://real.example)"))
    }

    @Test
    fun `code and alt text are left alone`() {
        assertTrue(links("`https://code.example` and ![https://alt.example](a.png)").isEmpty())
        assertTrue(links("```\nhttps://fenced.example\n```").isEmpty())
    }

    @Test
    fun `file names are not linked`() {
        assertTrue(links("Edit README.md and run setup.sh").isEmpty())
    }

    @Test
    fun `addresses in lists, headings and emphasis are linked`() {
        assertEquals(listOf("https://a.example", "https://b.example", "https://c.example"),
            links("- see https://a.example\n\n# https://b.example\n\n*https://c.example*"))
    }
}
