package com.papyrus.app.viewer

/** Which [CodeLanguage] a file's name selects. Names only: the content is never sniffed for a language. */
object CodeLanguages {

    /** Null for anything without a definition, which then shows as plain text. */
    fun forFileName(displayName: String?): CodeLanguage? {
        val name = displayName?.lowercase().orEmpty()
        if (name.isEmpty()) return null
        // Dockerfile.dev, Dockerfile-prod: the name leads, the extension is a suffix of it.
        if (name.startsWith("dockerfile")) return DOCKERFILE
        val extension = if (name.contains('.')) name.substringAfterLast('.') else name
        return byExtension[extension]
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
        keywords = "alignas alignof and asm auto bool break case catch char class concept const consteval constexpr " +
            "constinit const_cast continue co_await co_return co_yield decltype default delete do double " +
            "dynamic_cast else enum explicit export extern float for friend goto if inline int long mutable " +
            "namespace new noexcept not operator or private protected public register reinterpret_cast requires " +
            "return short signed sizeof static static_assert static_cast struct switch template this thread_local " +
            "throw try typedef typeid typename union unsigned using virtual void volatile wchar_t while xor",
        literals = "true false nullptr NULL",
        lineComments = SLASH_COMMENTS,
        multiline = listOf(BLOCK_COMMENT),
        preprocessor = true,
    )

    private val CSHARP = CodeLanguage(
        id = "csharp",
        keywords = "abstract as async await base bool break byte case catch char checked class const continue decimal " +
            "default delegate do double dynamic else enum event explicit extern finally fixed float for foreach get " +
            "goto if implicit in init int interface internal is lock long namespace new nameof object operator out " +
            "override params partial private protected public readonly record ref return sbyte sealed set short " +
            "sizeof stackalloc static string struct switch this throw try typeof uint ulong unchecked unsafe ushort " +
            "using var virtual void volatile when where while with yield",
        literals = "true false null",
        lineComments = SLASH_COMMENTS,
        multiline = listOf(BLOCK_COMMENT),
        preprocessor = true,
    )

    private val JAVASCRIPT = CodeLanguage(
        id = "javascript",
        keywords = "async await break case catch class const continue debugger default delete do else export extends " +
            "finally for from function get if import in instanceof let new of return set static super switch this " +
            "throw try typeof var void while with yield",
        literals = "true false null undefined NaN Infinity",
        lineComments = SLASH_COMMENTS,
        multiline = listOf(BLOCK_COMMENT, BACKTICK),
    )

    private val TYPESCRIPT = CodeLanguage(
        id = "typescript",
        keywords = "abstract any as async await boolean break case catch class const continue debugger declare default " +
            "delete do else enum export extends finally for from function get if implements import in infer " +
            "instanceof interface is keyof let module namespace never new number object of override private " +
            "protected public readonly return satisfies set static string super switch symbol this throw try type " +
            "typeof unique unknown var void while with yield",
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
        keywords = "alias and begin break case class def do else elsif end ensure for if in module next not or redo " +
            "rescue retry return self super then undef unless until when while yield",
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
        keywords = "as async await break const continue crate dyn else enum extern fn for if impl in let loop match mod " +
            "move mut pub ref return self Self static struct super trait type unsafe use where while",
        literals = "true false",
        lineComments = SLASH_COMMENTS,
        multiline = listOf(BLOCK_COMMENT),
        quotes = "\"",
        // `'a` is a lifetime far more often than a character, so a quote counts only when it closes.
        charLiteralQuotes = "'",
    )

    private val SWIFT = CodeLanguage(
        id = "swift",
        keywords = "actor any as associatedtype async await break case catch class continue default defer deinit do " +
            "else enum extension fallthrough fileprivate for func guard if import in init inout internal is let " +
            "nonisolated open operator private protocol public repeat rethrows return self Self static struct " +
            "subscript super switch throw throws try typealias var where while",
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
        keywords = "abstract and array as break callable case catch class clone const continue declare default do echo " +
            "else elseif empty enum extends final finally fn for foreach function global goto if implements include " +
            "include_once instanceof insteadof interface isset list match namespace new or print private protected " +
            "public readonly require require_once return static switch throw trait try unset use var while xor yield",
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
        keywords = "abstract as assert boolean break byte case catch char class const continue def default do double " +
            "else enum extends final finally float for goto if implements import in instanceof int interface long " +
            "native new package private protected public return short static strictfp super switch synchronized " +
            "this throw throws trait transient try var void volatile while",
        literals = "true false null",
        lineComments = SLASH_COMMENTS,
        multiline = listOf(BLOCK_COMMENT, TRIPLE_DOUBLE, TRIPLE_SINGLE),
        annotations = true,
    )

    private val SCALA = CodeLanguage(
        id = "scala",
        keywords = "abstract case catch class def do else enum export extends final finally for forSome given if " +
            "implicit import lazy match new object override package private protected return sealed super then " +
            "this throw trait try type using val var while with yield",
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
            "export local read return exit set unset shift source alias eval exec test trap",
        literals = "true false",
        lineComments = listOf("#"),
        rawQuotes = "'",
        dollarVariables = true,
        capitalizedTypes = false,
        functionCalls = false,
    )

    private val POWERSHELL = CodeLanguage(
        id = "powershell",
        keywords = "begin break catch class continue data do dynamicparam else elseif end enum exit filter finally for " +
            "foreach from function hidden if in param process return static switch throw trap try until using var " +
            "while workflow",
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
        keywords = "add all alter and any as asc backup begin between by cascade case check column commit constraint " +
            "create cross database declare default delete desc distinct drop else end exec exists fetch foreign " +
            "from full function grant group having if in index inner insert into is join key left like limit " +
            "materialized natural next not offset on only or order outer over partition primary procedure " +
            "recursive references replace restrict return returns revoke right rollback rows select set table " +
            "temp temporary top transaction trigger truncate union unique update using values view where while " +
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
        literals = "TRUE FALSE NULL NA NA_integer_ NA_real_ NA_character_ Inf NaN",
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
        literals = "true false",
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
        keywords = "ifeq ifneq ifdef ifndef else endif include define endef export override unexport vpath",
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
}
