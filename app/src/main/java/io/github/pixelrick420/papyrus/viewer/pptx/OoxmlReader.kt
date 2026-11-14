package io.github.pixelrick420.papyrus.viewer.pptx

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import java.io.IOException
import java.io.InputStream

internal data class OoxmlName(val namespace: String, val localName: String) {
    fun matches(namespace: String, localName: String): Boolean =
        this.namespace == namespace && this.localName == localName
}

internal class OoxmlAttribute(val namespace: String, val localName: String, val value: String)

internal object OoxmlNamespace {
    const val MC = "http://schemas.openxmlformats.org/markup-compatibility/2006"
    const val P = "http://schemas.openxmlformats.org/presentationml/2006/main"
    const val A = "http://schemas.openxmlformats.org/drawingml/2006/main"
    const val R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    const val P14 = "http://schemas.microsoft.com/office/powerpoint/2010/main"

    val CORE: Set<String> = setOf(MC, P, A, R)
}

/** A subtree held whole, for the few parts a parser has to look at twice: `lstStyle`, theme, table styles. */
internal class XNode(val name: OoxmlName, val attributes: List<OoxmlAttribute> = emptyList()) {

    val children: MutableList<XNode> = mutableListOf()

    var text: String = ""

    fun attribute(namespace: String, localName: String): String? =
        attributes.firstOrNull { it.namespace == namespace && it.localName == localName }?.value

    fun attribute(localName: String): String? =
        attributes.firstOrNull { it.namespace.isEmpty() && it.localName == localName }?.value

    fun child(namespace: String, localName: String): XNode? =
        children.firstOrNull { it.name.matches(namespace, localName) }

    fun childrenOf(namespace: String, localName: String): List<XNode> =
        children.filter { it.name.matches(namespace, localName) }
}

internal class OoxmlReader(
    /** Injected because [Xml.newPullParser] is a platform stub that returns null in JVM unit tests. */
    private val newParserFactory: () -> XmlPullParser = { Xml.newPullParser() },
    /** Namespaces this reader's parsers can actually consume; a `Choice` requiring anything else is skipped. */
    private val implemented: Set<String> = OoxmlNamespace.CORE,
    private val limits: PptxLimits = PptxLimits(),
) {

    fun open(stream: InputStream, part: String = "xml"): OoxmlCursor = OoxmlCursor(
        newParserFactory().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
            // kxml2 refuses this feature and leaves entity references unresolved, which is the same outcome.
            runCatching { setFeature(FEATURE_PROCESS_DOCDECL, false) }
            setInput(stream, null)
        },
        part,
        implemented,
        limits,
    )

    fun readTree(stream: InputStream, part: String = "xml"): XNode? {
        val cursor = open(stream, part)
        while (cursor.next() != XmlPullParser.END_DOCUMENT) {
            if (cursor.eventType == XmlPullParser.START_TAG) return cursor.readTree()
        }
        return null
    }
}

internal class OoxmlCursor internal constructor(
    private val parser: XmlPullParser,
    private val part: String,
    private val implemented: Set<String>,
    private val limits: PptxLimits,
) {

    private val pending = ArrayDeque<Event>()

    private val ignorable = HashSet<String>()

    private var elements = 0

    var eventType: Int = parser.eventType
        private set

    var name: OoxmlName = OoxmlName("", "")
        private set

    var text: String = ""
        private set

    var attributes: List<OoxmlAttribute> = emptyList()
        private set

    var depth: Int = 0
        private set

    fun next(): Int {
        while (true) {
            pending.removeFirstOrNull()?.let { return emit(it) }
            when (val event = advance()) {
                XmlPullParser.START_TAG -> {
                    val found = nameOf(parser)
                    readIgnorable()
                    // A namespace the reader declares it can consume is not ignorable to it.
                    if (found.namespace in ignorable && found.namespace !in implemented) {
                        skipParserSubtree()
                        continue
                    }
                    if (found.matches(OoxmlNamespace.MC, "AlternateContent")) {
                        enqueueAlternateContent()
                        continue
                    }
                    return start(found, attributesOf(parser))
                }
                XmlPullParser.END_TAG -> return end(nameOf(parser))
                XmlPullParser.TEXT, XmlPullParser.CDSECT, XmlPullParser.ENTITY_REF -> return text(parser.text.orEmpty())
                XmlPullParser.END_DOCUMENT -> {
                    eventType = event
                    attributes = emptyList()
                    return eventType
                }
                else -> Unit
            }
        }
    }

    fun attribute(namespace: String, localName: String): String? =
        attributes.firstOrNull { it.namespace == namespace && it.localName == localName }?.value

    fun attribute(localName: String): String? =
        attributes.firstOrNull { it.namespace.isEmpty() && it.localName == localName }?.value

    fun skipSubtree() {
        if (eventType != XmlPullParser.START_TAG) return
        val target = depth - 1
        while (depth > target) {
            if (next() == XmlPullParser.END_DOCUMENT) return
        }
    }

    fun readTree(): XNode {
        if (eventType != XmlPullParser.START_TAG) throw IllegalStateException("readTree needs a start tag")
        val root = XNode(name, attributes)
        val stack = ArrayDeque<XNode>().apply { addLast(root) }
        val target = depth - 1
        while (depth > target) {
            when (next()) {
                XmlPullParser.START_TAG -> XNode(name, attributes).also {
                    stack.last().children += it
                    stack.addLast(it)
                }
                XmlPullParser.TEXT -> stack.last().text += text
                XmlPullParser.END_TAG -> stack.removeLast()
                XmlPullParser.END_DOCUMENT -> return root
            }
        }
        return root
    }

    private fun start(found: OoxmlName, foundAttributes: List<OoxmlAttribute>): Int {
        name = found
        attributes = foundAttributes
        depth++
        eventType = XmlPullParser.START_TAG
        return eventType
    }

    private fun end(found: OoxmlName): Int {
        name = found
        attributes = emptyList()
        depth--
        eventType = XmlPullParser.END_TAG
        return eventType
    }

    private fun text(value: String): Int {
        text = value
        attributes = emptyList()
        eventType = XmlPullParser.TEXT
        return eventType
    }

    private fun emit(event: Event): Int = when (event) {
        is Event.Start -> start(event.name, event.attributes)
        is Event.Text -> text(event.text)
        is Event.End -> end(event.name)
    }

    private fun advance(): Int {
        val event = try {
            parser.next()
        } catch (e: XmlPullParserException) {
            throw PptxFormatException("$part is not valid xml", e)
        } catch (e: IOException) {
            throw PptxFormatException("$part is not valid xml", e)
        }
        if (event == XmlPullParser.START_TAG) {
            elements++
            if (elements > limits.maxXmlElements) {
                throw PptxFormatException("$part has more than ${limits.maxXmlElements} elements")
            }
            if (parser.depth > limits.maxXmlDepth) {
                throw PptxFormatException("$part is nested deeper than ${limits.maxXmlDepth}")
            }
        }
        return event
    }

    private fun readIgnorable() {
        val prefixes = attributesOf(parser)
            .firstOrNull { it.namespace == OoxmlNamespace.MC && it.localName == "Ignorable" }
            ?.value ?: return
        for (prefix in prefixes.split(' ')) {
            if (prefix.isBlank()) continue
            parser.getNamespace(prefix)?.let { ignorable += transitionalNamespace(it) }
        }
    }

    private fun skipParserSubtree() {
        val target = parser.depth - 1
        while (parser.depth > target) {
            if (advance() == XmlPullParser.END_DOCUMENT) return
        }
    }

    private fun enqueueAlternateContent() {
        val scope = namespaceScope()
        val alternate = readTreeFromParser()
        val branch = choose(alternate, scope) ?: return
        for (child in branch.children) enqueue(child, scope)
    }

    private fun choose(alternate: XNode, scope: Map<String, String>): XNode? =
        alternate.children.firstOrNull { it.name.matches(OoxmlNamespace.MC, "Choice") && isImplemented(it, scope) }
            ?: alternate.children.firstOrNull { it.name.matches(OoxmlNamespace.MC, "Fallback") }

    private fun isImplemented(choice: XNode, scope: Map<String, String>): Boolean {
        val requires = choice.attribute("Requires") ?: return false
        return requires.split(' ').filter { it.isNotBlank() }.all { prefix ->
            val uri = scope[prefix] ?: return@all false
            transitionalNamespace(uri) in implemented
        }
    }

    private fun enqueue(node: XNode, scope: Map<String, String>) {
        if (node.name.matches(OoxmlNamespace.MC, "AlternateContent")) {
            choose(node, scope)?.let { chosen -> chosen.children.forEach { enqueue(it, scope) } }
            return
        }
        if (node.name.namespace in ignorable && node.name.namespace !in implemented) return
        pending.addLast(Event.Start(node.name, node.attributes))
        if (node.text.isNotEmpty()) pending.addLast(Event.Text(node.text))
        node.children.forEach { enqueue(it, scope) }
        pending.addLast(Event.End(node.name))
    }

    private fun readTreeFromParser(): XNode {
        val root = XNode(nameOf(parser), attributesOf(parser))
        val stack = ArrayDeque<XNode>().apply { addLast(root) }
        while (true) {
            when (advance()) {
                XmlPullParser.START_TAG -> XNode(nameOf(parser), attributesOf(parser)).also {
                    stack.last().children += it
                    stack.addLast(it)
                }
                XmlPullParser.TEXT, XmlPullParser.CDSECT, XmlPullParser.ENTITY_REF -> stack.last().text += parser.text.orEmpty()
                XmlPullParser.END_TAG -> {
                    stack.removeLast()
                    if (stack.isEmpty()) return root
                }
                XmlPullParser.END_DOCUMENT -> return root
            }
        }
    }

    private fun namespaceScope(): Map<String, String> {
        val scope = HashMap<String, String>()
        for (index in 0 until parser.getNamespaceCount(parser.depth)) {
            scope[parser.getNamespacePrefix(index) ?: ""] = parser.getNamespaceUri(index) ?: ""
        }
        return scope
    }
}

internal fun nameOf(parser: XmlPullParser): OoxmlName = OoxmlName(transitionalNamespace(parser.namespace), parser.name)

internal fun attributesOf(parser: XmlPullParser): List<OoxmlAttribute> = buildList(parser.attributeCount) {
    for (index in 0 until parser.attributeCount) {
        add(
            OoxmlAttribute(
                transitionalNamespace(parser.getAttributeNamespace(index)),
                parser.getAttributeName(index),
                parser.getAttributeValue(index).orEmpty(),
            ),
        )
    }
}

private sealed interface Event {
    class Start(val name: OoxmlName, val attributes: List<OoxmlAttribute>) : Event
    class Text(val text: String) : Event
    class End(val name: OoxmlName) : Event
}

private const val FEATURE_PROCESS_DOCDECL = "http://xmlpull.org/v1/doc/features.html#process-docdecl"

private const val STRICT_PREFIX = "http://purl.oclc.org/ooxml/"

private const val TRANSITIONAL_PREFIX = "http://schemas.openxmlformats.org/"

/** Strict puts no year in the path; Transitional has one, so the year is inserted before the last segment. */
internal fun transitionalNamespace(uri: String?): String {
    if (uri.isNullOrEmpty() || !uri.startsWith(STRICT_PREFIX)) return uri.orEmpty()
    val rest = uri.removePrefix(STRICT_PREFIX)
    val cut = rest.lastIndexOf('/')
    return if (cut < 0) {
        TRANSITIONAL_PREFIX + rest
    } else {
        TRANSITIONAL_PREFIX + rest.substring(0, cut + 1) + "2006/" + rest.substring(cut + 1)
    }
}
