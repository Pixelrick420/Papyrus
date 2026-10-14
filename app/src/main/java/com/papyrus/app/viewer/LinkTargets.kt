package com.papyrus.app.viewer

/** A scheme prefix as a document writes it (`javascript:`, `https:`, `mailto:`). */
private val SCHEME_PREFIX = Regex("^[A-Za-z][A-Za-z0-9+.-]*:")

/**
 * The URL to actually open for a link the document supplied, or null when nothing should open.
 *
 * A link written without a scheme (`www.example.com`) is completed to https, the way a browser
 * resolves something that looks like a bare host. A link naming any other scheme — `javascript:`,
 * `file:`, `intent:`, `content:`, or one already filtered out by [safeLink] — is refused. A
 * fragment (`#note`) or a relative path is not an address at all, so it is refused too rather than
 * becoming a nonsense `https://#note`. Everything that survives is a scheme [safeLink] allows.
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
