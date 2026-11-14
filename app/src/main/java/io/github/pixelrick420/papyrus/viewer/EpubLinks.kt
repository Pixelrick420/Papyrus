package io.github.pixelrick420.papyrus.viewer

internal class Href(val path: String, val fragment: String?)

// The path half is returned as written, so the caller decides what it may point at.
internal fun splitHref(href: String): Href {
    val trimmed = href.trim()
    val hash = trimmed.indexOf('#')
    val path = (if (hash < 0) trimmed else trimmed.substring(0, hash)).substringBefore('?')
    val fragment = if (hash < 0) null else percentDecode(trimmed.substring(hash + 1)).takeIf { it.isNotEmpty() }
    return Href(path, fragment)
}

// [safeLink] accepts website links; [resolveEpubPath] still refuses every scheme, absolute path and climb above the root.
internal fun bookLinkOf(baseDir: String, href: String?, currentChapter: String): BookLink? {
    val target = splitHref(href ?: return null)
    if (target.path.isEmpty()) return target.fragment?.let { BookLink(currentChapter, it) }
    return resolveEpubPath(baseDir, target.path)?.let { BookLink(it, target.fragment) }
}
