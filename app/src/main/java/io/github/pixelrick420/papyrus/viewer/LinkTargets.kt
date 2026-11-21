package io.github.pixelrick420.papyrus.viewer

import io.github.pixelrick420.papyrus.data.DocumentFormat

enum class Completion { WwwOnly, HostLike }

private val SCHEME_PREFIX = Regex("^[A-Za-z][A-Za-z0-9+.-]*:")

private val NON_HOST_LABELS: Set<String> = (
    DocumentFormat.entries.flatMap { it.extensions } + setOf(
        "markdown", "mdown", "docm", "dotx", "dotm", "ott", "pptm", "potx", "potm", "ppsx", "ppsm",
        "xls", "xlsx", "csv", "tsv", "gz", "tar", "bz2", "xz", "7z", "rar",
        "mp3", "mp4", "m4a", "mov", "avi", "mkv", "png", "jpg", "jpeg", "gif", "webp", "bmp",
        "exe", "apk", "deb", "rpm", "iso", "bin", "dat", "bak", "yaml", "toml", "ini",
    )
    ).toSet()

// Scheme-less text completes only under the caller's policy; every survivor passes [safeLink].
internal fun resolvedLink(raw: String?, completion: Completion = Completion.WwwOnly): String? {
    val trimmed = raw?.trim().orEmpty()
    if (trimmed.isEmpty()) return null
    safeLink(trimmed)?.let { return it }
    if (SCHEME_PREFIX.containsMatchIn(trimmed)) return null
    if (!completion.allows(trimmed)) return null
    return safeLink("https://$trimmed")
}

private fun Completion.allows(text: String): Boolean = when (this) {
    Completion.WwwOnly -> text.startsWith("www.", ignoreCase = true)
    Completion.HostLike -> {
        val host = text.substringBefore('/').substringBefore('?').substringBefore('#')
        val lastLabel = host.substringAfterLast('.')
        text.startsWith("www.", ignoreCase = true) ||
            (host.contains('.') && lastLabel.isNotEmpty() && lastLabel !in NON_HOST_LABELS)
    }
}
