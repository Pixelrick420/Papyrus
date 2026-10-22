package com.papyrus.app.viewer

import io.noties.markwon.AbstractMarkwonPlugin
import org.commonmark.node.AbstractVisitor
import org.commonmark.node.Image
import org.commonmark.node.Link
import org.commonmark.node.Node
import org.commonmark.node.Text
import org.commonmark.parser.Parser
import org.commonmark.parser.PostProcessor

/** Turns the bare web and e-mail addresses in a document's prose into links; see [Autolinks] for which. */
internal class AutolinkPlugin : AbstractMarkwonPlugin() {
    override fun configureParser(builder: Parser.Builder) {
        builder.postProcessor(AutolinkPostProcessor)
    }
}

/**
 * Works on the parsed tree rather than the rendered text, so an address inside a link's label, an image's
 * alt text or a code span is left alone and the links it adds render exactly like ones the author wrote.
 */
internal object AutolinkPostProcessor : PostProcessor {

    override fun process(node: Node): Node {
        val texts = ArrayList<Text>()
        node.accept(object : AbstractVisitor() {
            override fun visit(text: Text) {
                texts.add(text)
            }

            // Not descended into: a link's label is already a link, and alt text is not shown as text.
            override fun visit(link: Link) = Unit

            override fun visit(image: Image) = Unit
        })
        // After the walk: splitting a node while visiting it would make the walk visit the pieces.
        for (text in texts) split(text)
        return node
    }

    private fun split(text: Text) {
        val literal = text.literal
        val found = Autolinks.find(literal)
        if (found.isEmpty()) return
        var cursor = 0
        var last: Node = text
        for (match in found) {
            if (match.start > cursor) last = insertAfter(last, Text(literal.substring(cursor, match.start)))
            val link = Link(match.destination, null)
            link.appendChild(Text(literal.substring(match.start, match.end)))
            last = insertAfter(last, link)
            cursor = match.end
        }
        if (cursor < literal.length) insertAfter(last, Text(literal.substring(cursor)))
        text.unlink()
    }

    private fun insertAfter(anchor: Node, node: Node): Node {
        anchor.insertAfter(node)
        return node
    }
}
