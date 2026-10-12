package com.papyrus.app.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class SyntaxHighlighterTest {

    private fun language(fileName: String): CodeLanguage = CodeLanguages.forFileName(fileName)
        ?: error("no language for $fileName")

    /** `KIND:text` per token, so an expectation reads like the source it describes. */
    private fun tokens(fileName: String, text: String, state: Int = 0): List<String> {
        val found = SyntaxHighlighter.tokenize(language(fileName), text, state)
        return (0 until found.size).map { "${found.kind(it)}:${text.substring(found.start(it), found.end(it))}" }
    }

    @Test
    fun `kotlin keywords, calls, types, numbers, strings and comments`() {
        assertEquals(
            listOf("KEYWORD:fun", "FUNCTION:greet", "TYPE:String", "KEYWORD:val", "NUMBER:42", "STRING:\"hi\"", "COMMENT:// done"),
            tokens("A.kt", "fun greet(name: String) { val n = 42 + \"hi\" // done"),
        )
    }

    @Test
    fun `annotations and constants`() {
        assertEquals(listOf("ANNOTATION:@Test", "KEYWORD:fun", "FUNCTION:run", "LITERAL:MAX_SIZE"), tokens("A.kt", "@Test fun run() = MAX_SIZE"))
    }

    @Test
    fun `numbers keep their prefixes, separators, suffixes and exponents but not a range`() {
        assertEquals(listOf("NUMBER:0xFF", "NUMBER:1_000", "NUMBER:1.5e-3", "NUMBER:10L"), tokens("A.kt", "0xFF 1_000 1.5e-3 10L"))
        assertEquals(listOf("NUMBER:1", "NUMBER:5"), tokens("A.kt", "1..5"))
        assertEquals(listOf("NUMBER:3.14"), tokens("A.kt", "3.14"))
    }

    @Test
    fun `a string stops at the end of its line and honours escapes`() {
        assertEquals(listOf("STRING:\"a\\\"b\"", "KEYWORD:val"), tokens("A.kt", "\"a\\\"b\" val"))
        // Unterminated: ends at the newline, so the next line is code again.
        assertEquals(listOf("STRING:\"open", "KEYWORD:val"), tokens("A.kt", "\"open\nval"))
    }

    @Test
    fun `a block comment left open carries into the next chunk`() {
        val java = language("A.java")
        val chunks = listOf("int a; /* start", "still comment", "end */ int b;")

        val states = SyntaxHighlighter.chunkStartStates(java, chunks)

        assertEquals(0, states[0])
        assertEquals(1, states[1])
        assertEquals(1, states[2])
        assertEquals(listOf("COMMENT:still comment"), tokens("A.java", chunks[1], states[1]))
        assertEquals(listOf("COMMENT:end */", "KEYWORD:int"), tokens("A.java", chunks[2], states[2]))
    }

    @Test
    fun `a construct that closes inside its own chunk leaves the next one in plain code`() {
        val states = SyntaxHighlighter.chunkStartStates(language("A.java"), listOf("/* a */ int x;", "int y;"))

        assertEquals(0, states[1])
    }

    @Test
    fun `python triple-quoted strings span chunks and a one-line one closes`() {
        val python = language("a.py")
        val states = SyntaxHighlighter.chunkStartStates(python, listOf("x = \"\"\"doc", "more", "end\"\"\"", "y = \"\"\"one\"\"\""))

        assertEquals(listOf(0, 1, 1, 0), (0..3).map { states[it] })
        assertEquals(listOf("STRING:more"), tokens("a.py", "more", states[1]))
        assertEquals(listOf("STRING:end\"\"\"", "NUMBER:1"), tokens("a.py", "end\"\"\"1", states[2]))
        assertEquals(listOf("STRING:\"\"\"one\"\"\""), tokens("a.py", "\"\"\"one\"\"\"", 0))
    }

    @Test
    fun `python hash comments and decorators`() {
        assertEquals(listOf("ANNOTATION:@cache", "KEYWORD:def", "FUNCTION:f", "COMMENT:# note"), tokens("a.py", "@cache\ndef f(): # note"))
    }

    @Test
    fun `json keys are properties and values are strings, numbers and literals`() {
        assertEquals(
            listOf("PROPERTY:\"a\"", "NUMBER:1", "LITERAL:true", "LITERAL:null", "PROPERTY:\"b\"", "STRING:\"x\""),
            tokens("a.json", "{\"a\": [1, true, null], \"b\" : \"x\"}"),
        )
    }

    @Test
    fun `a slash pair inside a json string is not a comment`() {
        assertEquals(listOf("PROPERTY:\"u\"", "STRING:\"http://x\""), tokens("a.json", "{\"u\": \"http://x\"}"))
    }

    @Test
    fun `yaml keys, list markers, scalars and comments`() {
        assertEquals(
            listOf("PROPERTY:name", "STRING:\"app\"", "PROPERTY:replicas", "NUMBER:3", "COMMENT:# c"),
            tokens("a.yml", "name: \"app\"\nreplicas: 3 # c"),
        )
        assertEquals(listOf("PROPERTY:key", "LITERAL:true"), tokens("a.yml", "  - key: true"))
        // A colon with no space after it is part of the scalar, not a key separator.
        assertEquals(emptyList<String>(), tokens("a.yml", "url:http"))
    }

    @Test
    fun `ini and toml sections and keys`() {
        assertEquals(listOf("TAG:[server]", "PROPERTY:port", "NUMBER:8080", "COMMENT:; note"), tokens("a.ini", "[server]\nport = 8080\n; note"))
        assertEquals(listOf("TAG:[[bin]]", "PROPERTY:name", "STRING:\"x\""), tokens("Cargo.toml", "[[bin]]\nname = \"x\""))
    }

    @Test
    fun `html tags, attributes, entities and comments`() {
        assertEquals(
            listOf("TAG:<div", "ATTRIBUTE:class", "STRING:\"a\"", "TAG:>", "LITERAL:&amp;", "TAG:</div", "TAG:>", "COMMENT:<!-- c -->"),
            tokens("a.html", "<div class=\"a\">x &amp; y</div><!-- c -->"),
        )
        assertEquals(listOf("TAG:<br", "TAG:/>"), tokens("a.html", "<br/>"))
        // A bare less-than in text is not a tag.
        assertEquals(emptyList<String>(), tokens("a.html", "if a < b then"))
    }

    @Test
    fun `an html tag split across chunks resumes inside the tag`() {
        val html = language("a.html")
        val chunks = listOf("<a", "href=\"x\"", "> text")

        val states = SyntaxHighlighter.chunkStartStates(html, chunks)

        assertEquals(listOf(0, html.tagState, html.tagState), (0..2).map { states[it] })
        assertEquals(listOf("ATTRIBUTE:href", "STRING:\"x\""), tokens("a.html", chunks[1], states[1]))
        assertEquals(listOf("TAG:>"), tokens("a.html", chunks[2], states[2]))
    }

    @Test
    fun `css properties, hex colours, units and at-rules, but not pseudo-classes`() {
        assertEquals(
            listOf("TYPE:.box", "PROPERTY:color", "NUMBER:#fff", "PROPERTY:margin", "NUMBER:10px", "KEYWORD:!important"),
            tokens("a.css", ".box:hover { color: #fff; margin: 10px !important; }"),
        )
        assertEquals(listOf("ANNOTATION:@media", "PROPERTY:min-width", "NUMBER:600px"), tokens("a.css", "@media (min-width: 600px)"))
        assertEquals(listOf("TYPE:#header"), tokens("a.css", "#header"))
    }

    @Test
    fun `rust lifetimes are not strings but character literals are`() {
        assertEquals(
            listOf("KEYWORD:fn", "KEYWORD:let", "STRING:'x'", "STRING:'\\n'"),
            tokens("a.rs", "fn f<'a>(s: &'a str) { let c = 'x'; '\\n' }"),
        )
    }

    @Test
    fun `c preprocessor directives are annotations only at the start of a line`() {
        assertEquals(listOf("ANNOTATION:#include", "KEYWORD:int", "NUMBER:1"), tokens("a.c", "#include <stdio.h>\nint x = 1;"))
    }

    @Test
    fun `shell variables and single-quoted strings with no escapes`() {
        assertEquals(listOf("VARIABLE:\$HOME", "VARIABLE:\${PATH}", "VARIABLE:\$1"), tokens("a.sh", "\$HOME \${PATH} \$1"))
        // A backslash does not escape inside single quotes, so the string ends at the second quote.
        assertEquals(listOf("STRING:'a\\'", "KEYWORD:if"), tokens("a.sh", "'a\\' if"))
    }

    @Test
    fun `make variables run to their closing parenthesis`() {
        assertEquals(listOf("VARIABLE:\$(CC)", "VARIABLE:\$(foo \$(bar))", "COMMENT:# x"), tokens("Makefile", "\$(CC) \$(foo \$(bar)) # x"))
    }

    @Test
    fun `lua block comments, line comments and long strings`() {
        assertEquals(listOf("COMMENT:--[[ a ]]", "KEYWORD:local", "COMMENT:-- b"), tokens("a.lua", "--[[ a ]] local -- b"))
        assertEquals(listOf("STRING:[[x]]"), tokens("a.lua", "[[x]]"))
    }

    @Test
    fun `sql keywords match in any case and a doubled quote stays one string`() {
        assertEquals(listOf("KEYWORD:SELECT", "KEYWORD:from", "STRING:'it''s'"), tokens("a.sql", "SELECT x from 'it''s'"))
    }

    @Test
    fun `javascript template literals span lines`() {
        val js = language("a.js")
        val states = SyntaxHighlighter.chunkStartStates(js, listOf("const s = `one", "two`;"))

        // The template literal is the second multi-line construct JavaScript defines, after the block comment.
        assertEquals(2, states[1])
        assertEquals(listOf("STRING:two`"), tokens("a.js", "two`;", states[1]))
    }

    @Test
    fun `the language follows the file name`() {
        assertEquals("kotlin", language("Main.kt").id)
        assertEquals("kotlin", language("build.gradle.kts").id)
        assertEquals("groovy", language("build.gradle").id)
        assertEquals("json", language("DATA.JSON").id)
        assertEquals("dockerfile", language("Dockerfile").id)
        assertEquals("dockerfile", language("Dockerfile.dev").id)
        assertEquals("makefile", language("Makefile").id)
        assertEquals("markup", language("index.htm").id)
        assertEquals("cpp", language("util.h").id)
        assertNull(CodeLanguages.forFileName("notes.txt"))
        assertNull(CodeLanguages.forFileName("changes.patch"))
        assertNull(CodeLanguages.forFileName(".gitignore"))
        assertNull(CodeLanguages.forFileName(""))
        assertNull(CodeLanguages.forFileName(null))
    }

    @Test
    fun `every extension the app calls code that has a language resolves one`() {
        val sample = listOf(
            "c", "h", "cc", "cpp", "cxx", "hpp", "cs", "java", "kt", "kts", "scala", "groovy", "js", "jsx", "mjs", "cjs",
            "ts", "tsx", "py", "pyw", "rb", "rake", "go", "rs", "php", "swift", "dart", "lua", "r", "sh", "bash", "zsh",
            "fish", "ksh", "ps1", "sql", "graphql", "gql", "json", "jsonc", "json5", "yaml", "yml", "toml", "ini", "cfg",
            "conf", "env", "properties", "xml", "svg", "html", "htm", "css", "scss", "sass", "less", "vue", "svelte",
            "gradle", "cmake", "mk", "makefile", "dockerfile",
        )
        for (extension in sample) assertNotNull(extension, CodeLanguages.forFileName("file.$extension"))
    }

    /** Whatever the input, tokens come out in order, inside the text and never overlapping, and the scan ends. */
    @Test
    fun `every language survives garbage and keeps its tokens ordered and in range`() {
        val pieces = listOf(
            "\"", "'", "`", "\\", "/*", "*/", "//", "#", "<", ">", "</", "<!--", "-->", "\"\"\"", "[[", "]]", "--", "\n", " ",
            "\t", "\r", "$", "\${", "$(", ")", "@", ":", "=", "[", "]", "{", "}", "0x", "1.", "e+", "é", "日", "\uD83D", "\uDE00",
            "foo", "Foo", "FOO", "if", "fun", "true", "&amp", ";", "!important", "#fff", ".",
        )
        val extensions = listOf(
            "kt", "java", "cpp", "cs", "js", "ts", "py", "rb", "go", "rs", "swift", "dart", "php", "lua", "groovy", "scala",
            "sh", "ps1", "sql", "graphql", "r", "json", "yml", "toml", "ini", "html", "css", "scss", "dockerfile", "mk", "cmake",
        )
        val random = Random(42)
        for (extension in extensions) {
            val lang = language("x.$extension")
            repeat(60) {
                val text = buildString { repeat(random.nextInt(80)) { append(pieces[random.nextInt(pieces.size)]) } }
                val found = SyntaxHighlighter.tokenize(lang, text)
                var previousEnd = 0
                for (i in 0 until found.size) {
                    val label = "${lang.id} #$i of ${text.replace("\n", "\\n")}"
                    assertTrue(label, found.start(i) >= previousEnd)
                    assertTrue(label, found.end(i) > found.start(i))
                    assertTrue(label, found.end(i) <= text.length)
                    previousEnd = found.end(i)
                }
                // The state-only pass must agree with the token pass about where constructs stay open.
                val states = SyntaxHighlighter.chunkStartStates(lang, listOf(text, text))
                assertEquals(SyntaxHighlighter.endState(lang, text, 0), states[1])
            }
        }
    }
}
