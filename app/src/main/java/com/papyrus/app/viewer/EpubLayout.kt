package com.papyrus.app.viewer

private const val MIN_ASPECT = 0.1f
private const val MAX_ASPECT = 10f

/** The head of a chapter is always well inside this; a viewport meta further in is not one. */
private const val HEAD_SCAN_BYTES = 64 * 1024

private val META_TAG = Regex("<meta\\b[^>]*>", RegexOption.IGNORE_CASE)
private val ATTRIBUTE = Regex("([A-Za-z][A-Za-z0-9:_-]*)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')")
private val NUMBER = "(\\d+(?:\\.\\d+)?)"
private val WIDTH = Regex("(?:^|[,;\\s])width\\s*=\\s*$NUMBER", RegexOption.IGNORE_CASE)
private val HEIGHT = Regex("(?:^|[,;\\s])height\\s*=\\s*$NUMBER", RegexOption.IGNORE_CASE)
private val SIZE_X = Regex("^\\s*$NUMBER\\s*[xX]\\s*$NUMBER\\s*$")

/**
 * Width over height of the page a fixed-layout chapter says it is, from `<meta name="viewport"
 * content="width=1200, height=1600">`, held between 0.1 and 10; null when there is none or it is unusable. A
 * plain scan of the start of the file, case-insensitive and tolerant of attribute order and quotes: no XML
 * parse, never throws, and the head is not something the markup reader sees.
 */
internal fun pageAspect(xhtml: ByteArray): Float? {
    val head = String(xhtml, 0, minOf(xhtml.size, HEAD_SCAN_BYTES), Charsets.ISO_8859_1)
    for (tag in META_TAG.findAll(head)) {
        val attributes = ATTRIBUTE.findAll(tag.value)
            .associate { it.groupValues[1].lowercase() to (it.groups[2]?.value ?: it.groupValues[3]) }
        if (attributes["name"]?.trim()?.lowercase() != "viewport") continue
        val content = attributes["content"] ?: continue
        val size = SIZE_X.find(content)?.let { it.groupValues[1].toFloat() to it.groupValues[2].toFloat() }
            ?: WIDTH.find(content)?.groupValues?.get(1)?.toFloat()?.let { w ->
                HEIGHT.find(content)?.groupValues?.get(1)?.toFloat()?.let { h -> w to h }
            }
        if (size != null && size.first > 0f && size.second > 0f) {
            return (size.first / size.second).coerceIn(MIN_ASPECT, MAX_ASPECT)
        }
    }
    return null
}
