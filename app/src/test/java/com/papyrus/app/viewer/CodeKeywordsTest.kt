package com.papyrus.app.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The keyword tables: a missing word is plain text, and a stray one recolours every identifier that spells it. */
class CodeKeywordsTest {

    private fun language(fileName: String): CodeLanguage = CodeLanguages.forFileName(fileName)
        ?: error("no language for $fileName")

    private fun keywords(fileName: String): Set<String> = language(fileName).keywordSet

    /** The words the scanner colours as keywords in [text], so an entry is checked through the highlighter and not only the set. */
    private fun keywordsIn(fileName: String, text: String): List<String> {
        val found = SyntaxHighlighter.tokenize(language(fileName), text)
        return (0 until found.size)
            .filter { found.kind(it) == TokenKind.KEYWORD }
            .map { text.substring(found.start(it), found.end(it)) }
    }

    private fun assertHas(fileName: String, words: String) {
        val table = keywords(fileName)
        for (word in words.split(' ')) assertTrue("$fileName should have $word", word in table)
    }

    @Test
    fun `the modern keywords the tables already had are still there`() {
        assertHas("a.cpp", "concept requires consteval constinit co_await co_yield co_return")
        assertHas("a.kt", "data sealed value fun interface typealias suspend")
        assertHas("a.java", "record sealed permits yield var")
        assertHas("a.cs", "record init with when yield async await")
        assertHas("a.py", "match case async await lambda")
        assertHas("a.rs", "async await dyn impl trait")
    }

    @Test
    fun `c and c++ gain the alternative operators, the character types, final, override and the c11 keywords`() {
        assertHas("a.cpp", "and_eq bitand bitor compl not_eq or_eq xor_eq char8_t char16_t char32_t final override")
        assertHas("a.c", "_Alignas _Alignof _Atomic _Bool _Complex _Generic _Imaginary _Noreturn _Static_assert _Thread_local")
        assertEquals(listOf("class", "final", "void", "override"), keywordsIn("a.cpp", "class A final : B { void f() override; };"))
        assertEquals(listOf("_Bool", "_Generic", "int"), keywordsIn("a.c", "_Bool ok = _Generic(x, int: 1);"))
    }

    @Test
    fun `c# gains native-sized integers and the pattern combinators`() {
        assertHas("a.cs", "nint nuint and or not")
        val found = keywordsIn("a.cs", "if (x is not null and y is 1 or 2) { nint n = 0; }")
        assertTrue(found.containsAll(listOf("not", "and", "or", "nint")))
    }

    @Test
    fun `javascript and typescript gain as, bigint and asserts`() {
        assertEquals(listOf("import", "as", "from"), keywordsIn("a.js", "import { a as b } from \"x\";").filter { it != "a" })
        assertHas("a.ts", "as asserts bigint")
        assertTrue(keywordsIn("a.ts", "let n: bigint; function f(x: unknown): asserts x is string {}").containsAll(listOf("bigint", "asserts")))
        // The table that already had `as` is not given a second one by being extended.
        assertEquals(1, keywords("a.ts").count { it == "as" })
    }

    @Test
    fun `ruby gains defined and the magic file constants`() {
        assertHas("a.rb", "defined __FILE__ __LINE__ __ENCODING__")
        assertTrue(keywordsIn("a.rb", "puts 1 if defined?(x) && __FILE__ == \$0").containsAll(listOf("if", "defined", "__FILE__")))
    }

    @Test
    fun `rust gains macro_rules`() {
        assertEquals(listOf("macro_rules"), keywordsIn("a.rs", "macro_rules! twice { () => {} }"))
    }

    @Test
    fun `swift gains its declaration modifiers, some and the property observers`() {
        assertHas("a.swift", "convenience dynamic final indirect lazy mutating nonmutating override precedencegroup some")
        assertHas("a.swift", "unowned weak willSet didSet")
        val found = keywordsIn("a.swift", "final class V: View { weak var d: P?; lazy var x = 1; var body: some View { Text() } }")
        assertTrue(found.containsAll(listOf("final", "class", "weak", "lazy", "some")))
    }

    @Test
    fun `php gains exit, die, eval and the alternative syntax endings`() {
        assertHas("a.php", "die eval exit enddeclare endfor endforeach endif endswitch endwhile")
        // Case-insensitive, so the capitalised spelling is found too, reported as it is written.
        assertTrue(keywordsIn("a.php", "if (\$a): EXIT; ENDIF;").containsAll(listOf("EXIT", "ENDIF")))
    }

    @Test
    fun `scala and groovy gain the keywords their newer versions added`() {
        assertHas("a.scala", "derives infix inline opaque transparent")
        assertHas("a.groovy", "sealed permits")
        assertTrue(keywordsIn("a.scala", "opaque type Id = Int").contains("opaque"))
    }

    @Test
    fun `shell gains the builtins that control flow and declare variables`() {
        assertHas("a.sh", "break continue declare readonly typeset printf let getopts")
        assertTrue(keywordsIn("a.sh", "declare -r x=1; printf \"%s\" a; break").containsAll(listOf("declare", "printf", "break")))
    }

    @Test
    fun `powershell gains default and configuration`() {
        assertHas("a.ps1", "default configuration")
        assertTrue(keywordsIn("a.ps1", "switch (\$x) { 1 { 'a' } Default { 'b' } }").contains("Default"))
    }

    @Test
    fun `sql gains case branches, set operators and the common statements`() {
        assertHas("a.sql", "when then cast except intersect lateral returning rename savepoint schema explain analyze vacuum")
        // Matches in any case, like the rest of the table.
        val found = keywordsIn("a.sql", "SELECT CASE WHEN a THEN 1 END FROM t EXCEPT SELECT 2")
        assertTrue(found.containsAll(listOf("SELECT", "CASE", "WHEN", "THEN", "END", "EXCEPT")))
    }

    @Test
    fun `make gains sinclude and undefine`() {
        assertHas("Makefile", "sinclude undefine")
    }

    @Test
    fun `r and toml gain the literals they were missing`() {
        assertTrue("NA_complex_" in language("a.r").literalSet)
        assertTrue("NA_integer_" in language("a.r").literalSet)
        assertTrue("inf" in language("a.toml").literalSet && "nan" in language("a.toml").literalSet)
        assertTrue("true" in language("a.toml").literalSet)
    }

    /**
     * Words that mean something only in one position and are ordinary names everywhere else. A highlighter with
     * no notion of position would recolour every variable called `context`, so these stay out of the tables.
     */
    @Test
    fun `contextual words that are common identifiers are not keywords`() {
        val stayPlain = mapOf(
            "a.kt" to "context field file param property receiver",
            "a.java" to "module open opens with when to uses requires exports",
            "a.cs" to "file required scoped global field extension",
            "a.rs" to "union gen try raw safe",
            "a.dart" to "base of when",
            "a.py" to "type",
            "a.ts" to "out global require accessor using",
            "a.scala" to "extension end as open",
            "a.swift" to "optional required package",
            "a.cpp" to "import module restrict",
        )
        for ((fileName, words) in stayPlain) {
            for (word in words.split(' ')) assertFalse("$fileName must not treat $word as a keyword", word in keywords(fileName))
        }
        assertEquals(listOf("val"), keywordsIn("a.kt", "val context = field + file"))
    }

    @Test
    fun `every keyword and literal in every table is a single plain word`() {
        val extensions = listOf(
            "c", "cs", "java", "kt", "scala", "groovy", "js", "ts", "py", "rb", "go", "rs", "php", "swift", "dart", "lua", "r",
            "sh", "ps1", "sql", "graphql", "json", "yaml", "toml", "ini", "xml", "css", "scss", "cmake", "mk", "dockerfile",
        )
        val word = Regex("[A-Za-z_][A-Za-z0-9_]*")
        for (extension in extensions) {
            val lang = language("file.$extension")
            for (entry in lang.keywordSet + lang.literalSet) {
                assertTrue("${lang.id}: \"$entry\" is not a word", word.matches(entry))
            }
        }
    }
}
