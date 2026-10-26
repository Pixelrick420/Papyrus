package com.papyrus.app.viewer

/** An `href` split into the part that names a file and the part that names a place in it. */
internal class Href(val path: String, val fragment: String?)

/**
 * Splits [href] at its first `#`: the path half loses a `?query`, the fragment is percent-decoded and is
 * null when it is empty or absent. The path half is returned as written, so the caller decides what it
 * may point at.
 */
internal fun splitHref(href: String): Href {
    val trimmed = href.trim()
    val hash = trimmed.indexOf('#')
    val path = (if (hash < 0) trimmed else trimmed.substring(0, hash)).substringBefore('?')
    val fragment = if (hash < 0) null else percentDecode(trimmed.substring(hash + 1)).takeIf { it.isNotEmpty() }
    return Href(path, fragment)
}

/**
 * The place in the book that [href], written in a chapter whose directory is [baseDir] and whose own path is
 * [currentChapter], points at; null when it goes nowhere or anywhere but inside the book. A link to a
 * website is not this function's to accept ([safeLink] is), and [resolveEpubPath] still refuses every
 * scheme, absolute path and climb above the root.
 */
internal fun bookLinkOf(baseDir: String, href: String?, currentChapter: String): BookLink? {
    val target = splitHref(href ?: return null)
    if (target.path.isEmpty()) return target.fragment?.let { BookLink(currentChapter, it) }
    return resolveEpubPath(baseDir, target.path)?.let { BookLink(it, target.fragment) }
}
