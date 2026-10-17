package com.papyrus.app.viewer

private val SCHEME_PREFIX = Regex("^[A-Za-z][A-Za-z0-9+.-]*:")

/**
 * The URL to open for a document-supplied link, or null. Scheme-less host-like text is completed
 * to https; other schemes, fragments and relative paths are refused. Survivors pass [safeLink].
 */
internal fun resolvedLink(raw: String?): String? {
    val trimmed = raw?.trim().orEmpty()
    if (trimmed.isEmpty()) return null
    // Already a safe absolute URL: http(s), mailto or tel.
    safeLink(trimmed)?.let { return it }
    // Names some other scheme: refused, and deliberately not completed to https.
    if (SCHEME_PREFIX.containsMatchIn(trimmed)) return null
    // Scheme-less: only a host-like first segment (it has a dot) is worth completing.
    if (!trimmed.substringBefore('/').contains('.')) return null
    return safeLink("https://$trimmed")
}
