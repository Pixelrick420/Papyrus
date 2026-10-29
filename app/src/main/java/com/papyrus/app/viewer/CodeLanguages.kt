package com.papyrus.app.viewer

/** Which [CodeLanguage] a file selects: its name decides, and its content only where the name cannot. */
object CodeLanguages {

    /** Null for anything without a definition, which then shows as plain text. */
    fun forFileName(displayName: String?): CodeLanguage? {
        val name = displayName?.lowercase().orEmpty()
        if (name.isEmpty()) return null
        // Dockerfile.dev, Dockerfile-prod: the name leads, the extension is a suffix of it.
        if (name.startsWith("dockerfile")) return DOCKERFILE
        return byExtension[extensionOf(name)]
    }

    /**
     * [forFileName], with the start of the file ([head], see [LanguageDetector.headOf]) as a second opinion for a
     * file opened as code. The name stays authoritative: the content is read only when the name selects no
     * definition (an unknown or missing extension) or is one of [LanguageDetector.AMBIGUOUS_EXTENSIONS], and when
     * it says nothing the name's answer, if any, stands.
     */
    fun forContent(displayName: String?, head: String): CodeLanguage? {
        val named = forFileName(displayName)
        val extension = extensionOf(displayName?.lowercase().orEmpty())
        if (named != null && extension !in LanguageDetector.AMBIGUOUS_EXTENSIONS) return named
        val kind = LanguageDetector.detect(head, extension) ?: return named
        return languageOf(kind) ?: named
    }

    /** The text after the last dot, or the whole name when it has none, which is how a name is matched everywhere. */
    private fun extensionOf(lowerCaseName: String): String =
        if (lowerCaseName.contains('.')) lowerCaseName.substringAfterLast('.') else lowerCaseName

    /**
     * The definition that colours [kind], or null when there is none. C, C++ and Objective-C share the one C-family
     * definition, which is also what `.c` and `.h` get by name, so telling them apart changes no colour for now.
     */
    private fun languageOf(kind: ContentKind): CodeLanguage? = when (kind) {
        ContentKind.SHELL -> SHELL
        ContentKind.POWERSHELL -> POWERSHELL
        ContentKind.PYTHON -> PYTHON
        ContentKind.JAVASCRIPT -> JAVASCRIPT
        ContentKind.RUBY -> RUBY
        ContentKind.PHP -> PHP
        ContentKind.LUA -> LUA
        ContentKind.GROOVY -> GROOVY
        ContentKind.R -> R
        ContentKind.C, ContentKind.CPP, ContentKind.OBJC -> CPP
        ContentKind.MARKUP -> MARKUP
        ContentKind.JSON, ContentKind.JSONC, ContentKind.JSON5 -> JSON
        ContentKind.PERL, ContentKind.MATLAB -> null
    }

    /**
     * The language a Markdown code fence names, or null to show the block plain. The tag is the first word of
     * the info string, so `kotlin`, `kotlin title="A.kt"`, `kotlin{1,3}`, `{.kotlin}` and `language-kotlin`
     * all select Kotlin, as do the short names `kt` and `py` that files use as extensions.
     */
    fun forFence(info: String?): CodeLanguage? {
        val tag = fenceTag(info) ?: return null
        return byName[tag] ?: byExtension[tag]
    }

    private fun fenceTag(info: String?): String? {
        val trimmed = info?.trim().orEmpty().trimStart('{', '.')
        val word = trimmed.takeWhile { !it.isWhitespace() && it != '{' && it != '}' && it != ',' && it != '=' }
        val tag = word.lowercase().removePrefix("language-").removePrefix("lang-")
        return tag.ifEmpty { null }
    }

    private val SLASH_COMMENTS = listOf("//")
    private val BLOCK_COMMENT = Delimited("/*", "*/", TokenKind.COMMENT)
    private val TRIPLE_DOUBLE = Delimited("\"\"\"", "\"\"\"", TokenKind.STRING)
    private val TRIPLE_SINGLE = Delimited("'''", "'''", TokenKind.STRING)
    private val BACKTICK = Delimited("`", "`", TokenKind.STRING)

    private val KOTLIN = CodeLanguage(
        id = "kotlin",
        keywords = "abstract actual annotation as break by catch class companion const constructor continue " +
            "crossinline data delegate do dynamic else enum expect external final finally for fun get if import in " +
            "infix init inline inner interface internal is lateinit noinline object open operator out override " +
            "package private protected public reified return sealed set super suspend tailrec this throw try " +
            "typealias typeof val value var vararg when where while",
        literals = "true false null",
        lineComments = SLASH_COMMENTS,
        multiline = listOf(BLOCK_COMMENT, TRIPLE_DOUBLE),
        annotations = true,
    )

    private val JAVA = CodeLanguage(
        id = "java",
        keywords = "abstract assert boolean break byte case catch char class const continue default do double else " +
            "enum extends final finally float for goto if implements import instanceof int interface long native new " +
            "package permits private protected public record return sealed short static strictfp super switch " +
            "synchronized this throw throws transient try var void volatile while yield",
        literals = "true false null",
        lineComments = SLASH_COMMENTS,
        multiline = listOf(BLOCK_COMMENT, TRIPLE_DOUBLE),
        annotations = true,
    )

    private val CPP = CodeLanguage(
        id = "cpp",
        keywords = "alignas alignof and and_eq asm auto bitand bitor bool break case catch char char8_t char16_t " +
            "char32_t class compl concept const consteval constexpr constinit const_cast continue co_await " +
            "co_return co_yield decltype default delete do double dynamic_cast else enum explicit export extern " +
            "final float for friend goto if inline int long mutable namespace new noexcept not not_eq operator or " +
            "or_eq override private protected public register reinterpret_cast requires return short signed " +
            "sizeof static static_assert static_cast struct switch template this thread_local throw try typedef " +
            "typeid typename union unsigned using virtual void volatile wchar_t while xor xor_eq _Alignas " +
            "_Alignof _Atomic _Bool _Complex _Generic _Imaginary _Noreturn _Static_assert _Thread_local",
        literals = "true false nullptr NULL",
        lineComments = SLASH_COMMENTS,
        multiline = listOf(BLOCK_COMMENT),
        preprocessor = true,
    )

    private val CSHARP = CodeLanguage(
        id = "csharp",
        keywords = "abstract and as async await base bool break byte case catch char checked class const continue " +
            "decimal default delegate do double dynamic else enum event explicit extern finally fixed float for " +
            "foreach get goto if implicit in init int interface internal is lock long namespace new nameof nint " +
            "not nuint object operator or out override params partial private protected public readonly record " +
            "ref return sbyte sealed set short sizeof stackalloc static string struct switch this throw try " +
            "typeof uint ulong unchecked unsafe ushort using var virtual void volatile when where while with " +
            "yield",
        literals = "true false null",
        lineComments = SLASH_COMMENTS,
        multiline = listOf(BLOCK_COMMENT),
        preprocessor = true,
    )

    private val JAVASCRIPT = CodeLanguage(
        id = "javascript",
        keywords = "as async await break case catch class const continue debugger default delete do else export " +
            "extends finally for from function get if import in instanceof let new of return set static super " +
            "switch this throw try typeof var void while with yield",
        literals = "true false null undefined NaN Infinity",
        lineComments = SLASH_COMMENTS,
        multiline = listOf(BLOCK_COMMENT, BACKTICK),
    )

    private val TYPESCRIPT = CodeLanguage(
        id = "typescript",
        keywords = "abstract any as asserts async await bigint boolean break case catch class const continue " +
            "debugger declare default delete do else enum export extends finally for from function get if " +
            "implements import in infer instanceof interface is keyof let module namespace never new number " +
            "object of override private protected public readonly return satisfies set static string super switch " +
            "symbol this throw try type typeof unique unknown var void while with yield",
        literals = "true false null undefined NaN Infinity",
        lineComments = SLASH_COMMENTS,
        multiline = listOf(BLOCK_COMMENT, BACKTICK),
        annotations = true,
    )

    private val PYTHON = CodeLanguage(
        id = "python",
        keywords = "and as assert async await break case class continue def del elif else except finally for from " +
            "global if import in is lambda match nonlocal not or pass raise return try while with yield",
        literals = "True False None self cls",
        lineComments = listOf("#"),
        multiline = listOf(TRIPLE_DOUBLE, TRIPLE_SINGLE),
        annotations = true,
    )

    private val RUBY = CodeLanguage(
        id = "ruby",
        keywords = "alias and begin break case class def defined do else elsif end ensure for if in module next " +
            "not or redo rescue retry return self super then undef unless until when while yield __ENCODING__ " +
            "__FILE__ __LINE__",
        literals = "true false nil",
        lineComments = listOf("#"),
    )

    private val GO = CodeLanguage(
        id = "go",
        keywords = "break case chan const continue default defer else fallthrough for func go goto if import " +
            "interface map package range return select struct switch type var",
        literals = "true false nil iota",
        lineComments = SLASH_COMMENTS,
        multiline = listOf(BLOCK_COMMENT, BACKTICK),
    )

    private val RUST = CodeLanguage(
        id = "rust",
        keywords = "as async await break const continue crate dyn else enum extern fn for if impl in let loop " +
            "macro_rules match mod move mut pub ref return self Self static struct super trait type unsafe use " +
            "where while",
        literals = "true false",
        lineComments = SLASH_COMMENTS,
        multiline = listOf(BLOCK_COMMENT),
        quotes = "\"",
        // `'a` is a lifetime far more often than a character, so a quote counts only when it closes.
        charLiteralQuotes = "'",
    )

    private val SWIFT = CodeLanguage(
        id = "swift",
        keywords = "actor any as associatedtype async await break case catch class continue convenience default " +
            "defer deinit didSet do dynamic else enum extension fallthrough fileprivate final for func guard if " +
            "import in indirect init inout internal is lazy let mutating nonisolated nonmutating open operator " +
            "override precedencegroup private protocol public repeat rethrows return self Self some static struct " +
            "subscript super switch throw throws try typealias unowned var weak where while willSet",
        literals = "true false nil",
        lineComments = SLASH_COMMENTS,
        multiline = listOf(BLOCK_COMMENT, TRIPLE_DOUBLE),
        annotations = true,
    )

    private val DART = CodeLanguage(
        id = "dart",
        keywords = "abstract as assert async await break case catch class const continue covariant default deferred do " +
            "dynamic else enum export extends extension external factory final finally for Function get hide if " +
            "implements import in interface is late library mixin new on operator part required rethrow return " +
            "sealed set show static super switch sync this throw try typedef var void while with yield",
        literals = "true false null",
        lineComments = SLASH_COMMENTS,
        multiline = listOf(BLOCK_COMMENT, TRIPLE_DOUBLE, TRIPLE_SINGLE),
        annotations = true,
    )

    private val PHP = CodeLanguage(
        id = "php",
        keywords = "abstract and array as break callable case catch class clone const continue declare default die " +
            "do echo else elseif empty enddeclare endfor endforeach endif endswitch endwhile enum eval exit " +
            "extends final finally fn for foreach function global goto if implements include include_once " +
            "instanceof insteadof interface isset list match namespace new or print private protected public " +
            "readonly require require_once return static switch throw trait try unset use var while xor yield",
        literals = "true false null",
        caseInsensitive = true,
        lineComments = listOf("//", "#"),
        multiline = listOf(BLOCK_COMMENT),
        dollarVariables = true,
    )

    private val LUA = CodeLanguage(
        id = "lua",
        keywords = "and break do else elseif end for function goto if in local not or repeat return then until while",
        literals = "true false nil",
        lineComments = listOf("--"),
        multiline = listOf(
            // Before the line comment: `--[[` is a block, `--` alone is a line.
            Delimited("--[[", "]]", TokenKind.COMMENT),
            Delimited("[[", "]]", TokenKind.STRING),
        ),
        capitalizedTypes = false,
    )

    private val GROOVY = CodeLanguage(
        id = "groovy",
        keywords = "abstract as assert boolean break byte case catch char class const continue def default do " +
            "double else enum extends final finally float for goto if implements import in instanceof int " +
            "interface long native new package permits private protected public return sealed short static " +
            "strictfp super switch synchronized this throw throws trait transient try var void volatile while",
        literals = "true false null",
        lineComments = SLASH_COMMENTS,
        multiline = listOf(BLOCK_COMMENT, TRIPLE_DOUBLE, TRIPLE_SINGLE),
        annotations = true,
    )

    private val SCALA = CodeLanguage(
        id = "scala",
        keywords = "abstract case catch class def derives do else enum export extends final finally for forSome " +
            "given if implicit import infix inline lazy match new object opaque override package private " +
            "protected return sealed super then this throw trait transparent try type using val var while with " +
            "yield",
        literals = "true false null",
        lineComments = SLASH_COMMENTS,
        multiline = listOf(BLOCK_COMMENT, TRIPLE_DOUBLE),
        quotes = "\"",
        charLiteralQuotes = "'",
        annotations = true,
    )

    private val SHELL = CodeLanguage(
        id = "shell",
        keywords = "if then else elif fi case esac for select while until do done in function time coproc echo cd " +
            "export local read return exit set unset shift source alias eval exec test trap break continue " +
            "declare readonly typeset printf let getopts",
        literals = "true false",
        lineComments = listOf("#"),
        rawQuotes = "'",
        dollarVariables = true,
        capitalizedTypes = false,
        functionCalls = false,
    )

    private val POWERSHELL = CodeLanguage(
        id = "powershell",
        keywords = "begin break catch class configuration continue data default do dynamicparam else elseif end " +
            "enum exit filter finally for foreach from function hidden if in param process return static switch " +
            "throw trap try until using var while workflow",
        literals = "true false null",
        caseInsensitive = true,
        lineComments = listOf("#"),
        multiline = listOf(
            Delimited("<#", "#>", TokenKind.COMMENT),
            Delimited("@\"", "\"@", TokenKind.STRING),
            Delimited("@'", "'@", TokenKind.STRING),
        ),
        rawQuotes = "'",
        escape = '`',
        dollarVariables = true,
        capitalizedTypes = false,
    )

    private val SQL = CodeLanguage(
        id = "sql",
        keywords = "add all alter analyze and any as asc backup begin between by cascade case cast check column " +
            "commit constraint create cross database declare default delete desc distinct drop else end except " +
            "exec exists explain fetch foreign from full function grant group having if in index inner insert " +
            "intersect into is join key lateral left like limit materialized natural next not offset on only or " +
            "order outer over partition primary procedure recursive references rename replace restrict return " +
            "returning returns revoke right rollback rows savepoint schema select set table temp temporary then " +
            "top transaction trigger truncate union unique update using vacuum values view when where while " +
            "window with int integer bigint smallint tinyint decimal numeric float real double precision varchar " +
            "char text boolean bool date time timestamp datetime blob serial",
        literals = "true false null",
        caseInsensitive = true,
        lineComments = listOf("--"),
        multiline = listOf(BLOCK_COMMENT),
        // Standard SQL doubles a quote to escape it; a backslash is an ordinary character.
        quotes = "\"'`",
        rawQuotes = "\"'`",
        capitalizedTypes = false,
    )

    private val GRAPHQL = CodeLanguage(
        id = "graphql",
        keywords = "query mutation subscription fragment on type interface enum input union scalar schema extend " +
            "implements directive repeatable",
        literals = "true false null",
        lineComments = listOf("#"),
        multiline = listOf(TRIPLE_DOUBLE),
        annotations = true,
        dollarVariables = true,
        functionCalls = false,
    )

    private val R = CodeLanguage(
        id = "r",
        keywords = "if else repeat while function for next break in",
        literals = "TRUE FALSE NULL NA NA_integer_ NA_real_ NA_complex_ NA_character_ Inf NaN",
        lineComments = listOf("#"),
        quotes = "\"'`",
        capitalizedTypes = false,
    )

    private val JSON = CodeLanguage(
        id = "json",
        literals = "true false null",
        // JSONC and JSON5 allow comments, and a `//` inside a string is consumed as part of the string first.
        lineComments = SLASH_COMMENTS,
        multiline = listOf(BLOCK_COMMENT),
        stringKeys = true,
        capitalizedTypes = false,
        functionCalls = false,
    )

    private val YAML = CodeLanguage(
        id = "yaml",
        literals = "true false null yes no on off True False Null",
        lineComments = listOf("#"),
        rawQuotes = "'",
        stringKeys = true,
        lineKeys = LineKeys.YAML,
        capitalizedTypes = false,
        functionCalls = false,
    )

    private val TOML = CodeLanguage(
        id = "toml",
        literals = "true false inf nan",
        lineComments = listOf("#"),
        multiline = listOf(TRIPLE_DOUBLE, TRIPLE_SINGLE),
        rawQuotes = "'",
        lineKeys = LineKeys.EQUALS,
        capitalizedTypes = false,
        functionCalls = false,
    )

    private val INI = CodeLanguage(
        id = "ini",
        literals = "true false yes no on off",
        lineComments = listOf("#", ";"),
        // Apostrophes are common in the free text of these files, so only double quotes open a string.
        quotes = "\"",
        lineKeys = LineKeys.EQUALS,
        capitalizedTypes = false,
        functionCalls = false,
    )

    private val MARKUP = CodeLanguage(
        id = "markup",
        multiline = listOf(Delimited("<!--", "-->", TokenKind.COMMENT)),
        capitalizedTypes = false,
        functionCalls = false,
        flavor = Flavor.MARKUP,
    )

    private val CSS = CodeLanguage(
        id = "css",
        multiline = listOf(BLOCK_COMMENT),
        annotations = true,
        capitalizedTypes = false,
        flavor = Flavor.CSS,
    )

    private val SCSS = CodeLanguage(
        id = "scss",
        lineComments = SLASH_COMMENTS,
        multiline = listOf(BLOCK_COMMENT),
        annotations = true,
        capitalizedTypes = false,
        flavor = Flavor.CSS,
    )

    private val DOCKERFILE = CodeLanguage(
        id = "dockerfile",
        keywords = "from run cmd label maintainer expose env add copy entrypoint volume user workdir arg onbuild " +
            "stopsignal healthcheck shell as",
        caseInsensitive = true,
        lineComments = listOf("#"),
        rawQuotes = "'",
        dollarVariables = true,
        capitalizedTypes = false,
        functionCalls = false,
    )

    private val MAKEFILE = CodeLanguage(
        id = "makefile",
        keywords = "ifeq ifneq ifdef ifndef else endif include define endef export override unexport vpath " +
            "sinclude undefine",
        lineComments = listOf("#"),
        dollarVariables = true,
        parenVariables = true,
        capitalizedTypes = false,
        functionCalls = false,
    )

    private val CMAKE = CodeLanguage(
        id = "cmake",
        keywords = "if elseif else endif foreach endforeach while endwhile function endfunction macro endmacro return " +
            "break continue block endblock",
        literals = "true false on off yes no",
        caseInsensitive = true,
        lineComments = listOf("#"),
        // Before the line comment: `#[[` opens a block.
        multiline = listOf(Delimited("#[[", "]]", TokenKind.COMMENT)),
        dollarVariables = true,
        capitalizedTypes = false,
    )

    /** Extensions from [com.papyrus.app.data.DocumentFormat.CODE] that have a definition; the rest show plain. */
    private val byExtension: Map<String, CodeLanguage> = buildMap {
        fun add(language: CodeLanguage, vararg extensions: String) = extensions.forEach { put(it, language) }
        add(KOTLIN, "kt", "kts")
        add(JAVA, "java")
        add(CPP, "c", "h", "cc", "cpp", "cxx", "hpp")
        add(CSHARP, "cs")
        add(JAVASCRIPT, "js", "jsx", "mjs", "cjs")
        add(TYPESCRIPT, "ts", "tsx")
        add(PYTHON, "py", "pyw")
        add(RUBY, "rb", "rake")
        add(GO, "go")
        add(RUST, "rs")
        add(SWIFT, "swift")
        add(DART, "dart")
        add(PHP, "php")
        add(LUA, "lua")
        add(GROOVY, "groovy", "gradle")
        add(SCALA, "scala")
        add(SHELL, "sh", "bash", "zsh", "ksh", "fish")
        add(POWERSHELL, "ps1")
        add(SQL, "sql")
        add(GRAPHQL, "graphql", "gql")
        add(R, "r")
        add(JSON, "json", "jsonc", "json5")
        add(YAML, "yaml", "yml")
        add(TOML, "toml")
        add(INI, "ini", "cfg", "conf", "env", "properties", "editorconfig")
        add(MARKUP, "xml", "svg", "html", "htm", "vue", "svelte")
        add(CSS, "css")
        add(SCSS, "scss", "sass", "less")
        add(DOCKERFILE, "dockerfile")
        add(MAKEFILE, "mk", "makefile")
        add(CMAKE, "cmake")
    }

    /** The names a code fence uses that are not file extensions; `text` and `plaintext` are left out on purpose. */
    private val byName: Map<String, CodeLanguage> = buildMap {
        fun add(language: CodeLanguage, vararg names: String) = names.forEach { put(it, language) }
        add(KOTLIN, "kotlin")
        add(CPP, "c++")
        add(CSHARP, "csharp", "c#")
        add(JAVASCRIPT, "javascript", "node", "nodejs")
        add(TYPESCRIPT, "typescript")
        add(PYTHON, "python", "python3", "py3")
        add(RUBY, "ruby")
        add(GO, "golang")
        add(RUST, "rust")
        add(SHELL, "shell", "shellsession", "console")
        add(POWERSHELL, "powershell", "pwsh")
        add(MARKUP, "xhtml")
        add(DOCKERFILE, "docker")
        add(MAKEFILE, "make")
    }
}
