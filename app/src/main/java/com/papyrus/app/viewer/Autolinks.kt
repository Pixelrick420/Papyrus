package com.papyrus.app.viewer

/** A web or e-mail address found in a run of text: the range it covers and what a tap should open. */
internal class Autolink(val start: Int, val end: Int, val destination: String)

/**
 * Finds the addresses in prose that are worth turning into links: `http://` and `https://` URLs, `www.`
 * hosts and e-mail addresses, as GitHub does. Deliberately narrower than Android's `Linkify.WEB_URLS`,
 * which links any `name.tld` and so would turn the file names a README is full of (`README.md`, `setup.sh`,
 * `main.py`: `.md`, `.sh` and `.py` are country domains) into web links.
 */
internal object Autolinks {

    private val URL = Regex("""(?<![\w@])(?:https?://|www\.)[^\s<>\u00a0]+""", RegexOption.IGNORE_CASE)
    private val EMAIL = Regex("""[A-Za-z0-9._%+\-]+@[A-Za-z0-9\-]+(?:\.[A-Za-z0-9\-]+)*\.[A-Za-z]{2,}""")
    private const val WWW = "www."

    /** The links in [text], in order and not overlapping. Most text has none, which this settles without a regex. */
    fun find(text: String): List<Autolink> {
        val mayHaveUrl = text.contains("://") || text.contains(WWW, ignoreCase = true)
        val mayHaveEmail = text.indexOf('@') >= 0
        if (!mayHaveUrl && !mayHaveEmail) return emptyList()
        val found = ArrayList<Autolink>()
        if (mayHaveUrl) findUrls(text, found)
        if (mayHaveEmail) findEmails(text, found)
        found.sortBy { it.start }
        return found
    }

    private fun findUrls(text: String, into: MutableList<Autolink>) {
        for (match in URL.findAll(text)) {
            val start = match.range.first
            val end = trimTrailing(text, start, match.range.last + 1)
            val address = text.substring(start, end)
            if (address.startsWith(WWW, ignoreCase = true)) {
                // A bare host needs a dot after the `www.`, so a word like "www.localhost" is left alone.
                val host = address.substring(WWW.length).takeWhile { it != '/' && it != '?' && it != '#' && it != ':' }
                if (host.contains('.') && host.any { it.isLetterOrDigit() }) into.add(Autolink(start, end, "https://$address"))
            } else {
                val host = address.substringAfter("://").takeWhile { it != '/' && it != '?' && it != '#' }
                if (host.any { it.isLetterOrDigit() }) into.add(Autolink(start, end, address))
            }
        }
    }

    private fun findEmails(text: String, into: MutableList<Autolink>) {
        val urls = into.toList()
        for (match in EMAIL.findAll(text)) {
            val start = match.range.first
            val end = match.range.last + 1
            // `https://user@host.com/` is one URL, not a URL and an address.
            if (urls.none { start < it.end && it.start < end }) into.add(Autolink(start, end, "mailto:${match.value}"))
        }
    }

    /** Sentence punctuation after an address is not part of it, and neither is a closing bracket it never opened. */
    private fun trimTrailing(text: String, start: Int, rawEnd: Int): Int {
        var end = rawEnd
        while (end > start) {
            val drop = when (text[end - 1]) {
                '.', ',', ':', ';', '!', '?', '\'', '"', '*', '_', '~' -> true
                ')' -> hasMoreClosers(text, start, end, '(', ')')
                ']' -> hasMoreClosers(text, start, end, '[', ']')
                '}' -> hasMoreClosers(text, start, end, '{', '}')
                else -> false
            }
            if (!drop) break
            end--
        }
        return end
    }

    private fun hasMoreClosers(text: String, start: Int, end: Int, open: Char, close: Char): Boolean {
        var depth = 0
        for (i in start until end) {
            if (text[i] == open) depth++ else if (text[i] == close) depth--
        }
        return depth < 0
    }
}
