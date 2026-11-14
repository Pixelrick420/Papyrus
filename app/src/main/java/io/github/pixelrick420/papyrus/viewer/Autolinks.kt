package io.github.pixelrick420.papyrus.viewer

internal class Autolink(val start: Int, val end: Int, val destination: String)

// Deliberately narrower than Android's Linkify.WEB_URLS, which links any `name.tld` and would turn README.md, setup.sh and main.py (.md, .sh and .py are country domains) into web links.
internal object Autolinks {

    private val URL = Regex("""(?<![\w@])(?:https?://|www\.)[^\s<>\u00a0]+""", RegexOption.IGNORE_CASE)
    private val EMAIL = Regex("""[A-Za-z0-9._%+\-]+@[A-Za-z0-9\-]+(?:\.[A-Za-z0-9\-]+)*\.[A-Za-z]{2,}""")
    private const val WWW = "www."

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
        // Both matches arrive in ascending order, so one pointer replaces a per-email scan of every URL.
        val urls = into.toList()
        var nextUrl = 0
        for (match in EMAIL.findAll(text)) {
            val start = match.range.first
            val end = match.range.last + 1
            while (nextUrl < urls.size && urls[nextUrl].end <= start) nextUrl++
            val overlapsUrl = nextUrl < urls.size && urls[nextUrl].start < end
            // `https://user@host.com/` is one URL, not a URL and an address.
            if (!overlapsUrl) into.add(Autolink(start, end, "mailto:${match.value}"))
        }
    }

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
