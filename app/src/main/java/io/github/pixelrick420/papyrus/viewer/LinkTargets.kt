package io.github.pixelrick420.papyrus.viewer

private val SCHEME_PREFIX = Regex("^[A-Za-z][A-Za-z0-9+.-]*:")

// Scheme-less host-like text is completed to https; other schemes, fragments and relative paths are refused. Survivors pass [safeLink].
internal fun resolvedLink(raw: String?): String? {
    val trimmed = raw?.trim().orEmpty()
    if (trimmed.isEmpty()) return null
    safeLink(trimmed)?.let { return it }
    // Names some other scheme: refused, and deliberately not completed to https.
    if (SCHEME_PREFIX.containsMatchIn(trimmed)) return null
    // Scheme-less: only a host-like first segment (it has a dot) is worth completing.
    if (!trimmed.substringBefore('/').contains('.')) return null
    return safeLink("https://$trimmed")
}
