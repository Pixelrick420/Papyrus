package com.papyrus.app.viewer

/**
 * What a file's own text says it is. Finer than a [CodeLanguage] in places (C, C++ and Objective-C are one
 * definition today) and coarser in others (Perl and MATLAB have none), so [CodeLanguages.forContent] maps each
 * kind to the definition that exists, if one does. The three JSON kinds colour alike, and differ only in what a
 * strict parser would refuse.
 */
enum class ContentKind {
    SHELL, POWERSHELL, PYTHON, JAVASCRIPT, RUBY, PERL, PHP, LUA, GROOVY, R,
    C, CPP, OBJC, MATLAB,
    MARKUP,
    JSON, JSONC, JSON5,
}

/**
 * Guesses a [ContentKind] from the start of a file, for the cases its name cannot settle: an extension that is
 * unknown or missing, or one that fits more than one language. It looks for markers cheap to find (a shebang, an
 * XML root, `@interface`, a `%` comment) and answers null rather than guess, since a wrong colour is worse than none.
 *
 * Pure, and bounded: only the first [TextSniffer.MAX_SNIFF_BYTES] characters are read, whatever it is given.
 */
object LanguageDetector {

    private const val MAX_HEAD = TextSniffer.MAX_SNIFF_BYTES

    /** Extensions that fit more than one language, so the content gets a say although the name has an answer. */
    val AMBIGUOUS_EXTENSIONS: Set<String> = setOf("h", "m")

    private val INTERPRETERS: Map<String, ContentKind> = buildMap {
        fun add(kind: ContentKind, vararg names: String) = names.forEach { put(it, kind) }
        add(ContentKind.PYTHON, "python", "pypy")
        add(ContentKind.SHELL, "sh", "bash", "dash", "zsh", "ksh", "mksh", "ash", "fish")
        add(ContentKind.JAVASCRIPT, "node", "nodejs", "deno", "bun")
        add(ContentKind.RUBY, "ruby")
        add(ContentKind.PERL, "perl")
        add(ContentKind.PHP, "php")
        add(ContentKind.POWERSHELL, "pwsh", "powershell")
        add(ContentKind.LUA, "lua", "luajit")
        add(ContentKind.GROOVY, "groovy")
        add(ContentKind.R, "rscript")
    }

    // The root elements of files people open that carry no extension this app knows. Matched on the local name,
    // so `xsl:stylesheet` and `xs:schema` are found too. Any root passes when there is an XML declaration.
    private val MARKUP_ROOTS = setOf(
        "html", "svg", "project", "manifest", "rss", "feed", "plist", "configuration", "resources", "schema",
        "stylesheet", "transform", "urlset", "sitemapindex", "kml", "gpx", "testsuite", "testsuites",
    )

    private val ROOT_ELEMENT = Regex("""^<([A-Za-z][A-Za-z0-9_.:-]*)""")

    // A bare word and a colon: the first key of a JSON5 object.
    private val UNQUOTED_KEY = Regex("""^\w+\s*:""")

    // A JSON literal as a whole word, so the section `[true_color]` of an INI file is not one.
    private val LITERAL = Regex("""^(?:true|false|null)\b""")

    // What only Objective-C writes: `#import`, the `@` directives, an `@"` string and Foundation's NSThing names.
    private val OBJC_MARKER = Regex(
        """(?m)^[ \t]*#\s*import\b|@(?:interface|implementation|protocol|property|synthesize|autoreleasepool|class|end)\b|@"|\bNS[A-Z][a-z]""",
    )

    // What a `.m` file only has if it is C-like: a preprocessor line or a `main`. MATLAB writes neither.
    private val C_LIKE_MARKER = Regex("""(?m)^[ \t]*#(?:include|define|pragma|ifdef|ifndef)\b|\bint\s+main\s*\(""")

    // What C++ has and C does not. `extern "C"` and `__cplusplus` are left out on purpose: they are how a C header
    // makes itself usable from C++.
    private val CPP_MARKER = Regex(
        """(?m)^[ \t]*namespace\b|\btemplate\s*<|^[ \t]*class\s+[A-Za-z_]\w*|^[ \t]*(?:public|private|protected)\s*:|""" +
            """#\s*include\s*<[A-Za-z_]\w*>|\bstd::|\bnullptr\b|\bconstexpr\b|\busing\s+namespace\b|\bvirtual\b|""" +
            """\btypename\b|\bnoexcept\b""",
    )

    // Line comments with `%`, `function` at the start of a line, a bare `end`, Octave's `endfunction`, a
    // continuation with `...`: none of them starts a line of Objective-C.
    private val MATLAB_MARKER = Regex(
        """(?m)^[ \t]*%|^[ \t]*function\b|^[ \t]*end(?:function|if|for|while|switch)?[ \t]*;?[ \t]*$|\bclassdef\b|""" +
            """^[ \t]*(?:clc|clear|close all)\b|\.\.\.[ \t]*$""",
    )

    /**
     * The kind of [head], the start of a file whose name ends in [extension] (lower case, or the whole name when
     * it has no dot). A `.h` or `.m` file is settled between the languages that share the name; anything else is
     * looked at for a shebang, an XML root or JSON, in that order.
     */
    fun detect(head: String, extension: String): ContentKind? {
        val prefix = head.take(MAX_HEAD)
        return when (extension) {
            "h" -> headerKind(prefix)
            "m" -> objcOrMatlab(prefix)
            else -> shebangKind(prefix) ?: markupKind(prefix) ?: jsonKind(prefix)
        }
    }

    /** The start of a file read as [chunks], as much of it as [detect] will look at. */
    fun headOf(chunks: List<String>): String {
        val head = StringBuilder()
        for (chunk in chunks) {
            if (head.length >= MAX_HEAD) break
            head.append(chunk)
        }
        return head.substring(0, minOf(head.length, MAX_HEAD))
    }

    private fun headerKind(text: String): ContentKind? = when {
        text.isBlank() -> null
        OBJC_MARKER.containsMatchIn(text) -> ContentKind.OBJC
        CPP_MARKER.containsMatchIn(text) -> ContentKind.CPP
        else -> ContentKind.C
    }

    private fun objcOrMatlab(text: String): ContentKind? = when {
        OBJC_MARKER.containsMatchIn(text) || C_LIKE_MARKER.containsMatchIn(text) -> ContentKind.OBJC
        MATLAB_MARKER.containsMatchIn(text) -> ContentKind.MATLAB
        else -> null
    }

    private fun shebangKind(text: String): ContentKind? {
        if (!text.startsWith("#!")) return null
        val words = text.substring(2).substringBefore('\n').split(' ', '\t', '\r').filter { it.isNotEmpty() }
        var interpreter = words.firstOrNull()?.substringAfterLast('/') ?: return null
        if (interpreter == "env") {
            // `env -S python3 -u` and `env FOO=1 node`: the first word that is neither a flag nor an assignment.
            interpreter = words.drop(1).firstOrNull { !it.startsWith("-") && !it.contains('=') }
                ?.substringAfterLast('/') ?: return null
        }
        // python3.11, ruby3.2, node18: the version is not part of the name.
        return INTERPRETERS[interpreter.lowercase().trimEnd { it.isDigit() || it == '.' }]
    }

    private fun markupKind(text: String): ContentKind? {
        var rest = text.trimStart { it == '\uFEFF' || it.isWhitespace() }
        if (!rest.startsWith("<")) return null
        while (true) {
            when {
                // A declaration or a doctype says markup whatever the root is.
                rest.startsWith("<?xml") || rest.startsWith("<!doctype", ignoreCase = true) -> return ContentKind.MARKUP
                rest.startsWith("<!--") -> rest = skipPast(rest, "-->") ?: return null
                rest.startsWith("<?") -> rest = skipPast(rest, "?>") ?: return null
                else -> break
            }
            rest = rest.trimStart()
        }
        val name = ROOT_ELEMENT.find(rest)?.groupValues?.get(1) ?: return null
        return if (name.substringAfter(':').lowercase() in MARKUP_ROOTS) ContentKind.MARKUP else null
    }

    private fun skipPast(text: String, end: String): String? {
        val at = text.indexOf(end)
        return if (at < 0) null else text.substring(at + end.length)
    }

    /**
     * JSON, or the looser kinds. Read outside its strings, so a `//` in a URL is not a comment: a comment or a
     * trailing comma makes JSONC, and an unquoted key or a single-quoted string makes JSON5, which wins.
     */
    private fun jsonKind(text: String): ContentKind? {
        // A file may open with comments (a JSONC config often does) before the brace that tells it is JSON.
        var body = text.trimStart { it == '\uFEFF' || it.isWhitespace() }
        var comments = false
        while (true) {
            if (body.startsWith("//")) {
                body = body.substringAfter('\n', "")
            } else if (body.startsWith("/*")) {
                body = skipPast(body, "*/") ?: return null
            } else {
                break
            }
            comments = true
            body = body.trimStart()
        }
        if (body.isEmpty() || !startsLikeJson(body)) return null
        var trailingComma = false
        var unquotedKey = false
        var singleQuotes = false
        val open = ArrayList<Char>()
        var last = ' '
        var i = 0
        while (i < body.length) {
            val c = body[i]
            when {
                c == '"' || c == '\'' -> {
                    if (c == '\'') singleQuotes = true
                    i = endOfString(body, i)
                    last = '"'
                }
                c == '/' && body.startsWith("//", i) -> {
                    comments = true
                    i = body.indexOf('\n', i).let { if (it < 0) body.length else it }
                }
                c == '/' && body.startsWith("/*", i) -> {
                    comments = true
                    i = body.indexOf("*/", i + 2).let { if (it < 0) body.length else it + 2 }
                }
                c == '{' || c == '[' -> {
                    open += c
                    last = c
                    i++
                }
                c == '}' || c == ']' -> {
                    if (last == ',') trailingComma = true
                    if (open.isNotEmpty()) open.removeAt(open.lastIndex)
                    last = c
                    i++
                }
                c.isWhitespace() -> i++
                // A bare word where a key belongs: after `{` or `,` inside an object, and followed by a colon.
                isNameStart(c) && open.lastOrNull() == '{' && (last == '{' || last == ',') -> {
                    var end = i
                    while (end < body.length && isNamePart(body[end])) end++
                    var colon = end
                    while (colon < body.length && body[colon].isWhitespace()) colon++
                    if (colon < body.length && body[colon] == ':') unquotedKey = true
                    last = 'a'
                    i = end
                }
                else -> {
                    last = c
                    i++
                }
            }
        }
        return when {
            unquotedKey || singleQuotes -> ContentKind.JSON5
            comments || trailingComma -> ContentKind.JSONC
            else -> ContentKind.JSON
        }
    }

    /**
     * Whether text that opens with `{` or `[` goes on like JSON. It keeps out `[section]` of an INI file,
     * `{{ template }}`, `{% tag %}` and prose that happens to start with a brace.
     */
    private fun startsLikeJson(body: String): Boolean {
        if (body[0] != '{' && body[0] != '[') return false
        val next = body.substring(1).trimStart()
        if (next.isEmpty()) return false
        val c = next[0]
        return if (body[0] == '{') {
            c == '"' || c == '\'' || c == '}' || c == '/' || (isNameStart(c) && UNQUOTED_KEY.containsMatchIn(next))
        } else {
            c == '{' || c == '[' || c == ']' || c == '"' || c == '\'' || c == '-' || c == '/' || c.isDigit() ||
                LITERAL.containsMatchIn(next)
        }
    }

    /** The index after the string that opens at [start]; a string ends at its line, as an unclosed one would. */
    private fun endOfString(text: String, start: Int): Int {
        val quote = text[start]
        var i = start + 1
        while (i < text.length) {
            when (text[i]) {
                '\\' -> i += 2
                quote -> return i + 1
                '\n' -> return i
                else -> i++
            }
        }
        return text.length
    }

    private fun isNameStart(c: Char) = c.isLetter() || c == '_' || c == '$'

    private fun isNamePart(c: Char) = c.isLetterOrDigit() || c == '_' || c == '$'
}
