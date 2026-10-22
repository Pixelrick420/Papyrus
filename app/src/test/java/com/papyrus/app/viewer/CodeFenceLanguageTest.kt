package com.papyrus.app.viewer

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class CodeFenceLanguageTest {

    private fun forFile(name: String): CodeLanguage = CodeLanguages.forFileName(name) ?: error("no language for $name")

    @Test
    fun `a fence names a language by its full name or by the short name files use`() {
        assertSame(forFile("A.kt"), CodeLanguages.forFence("kotlin"))
        assertSame(forFile("A.kt"), CodeLanguages.forFence("kt"))
        assertSame(forFile("a.py"), CodeLanguages.forFence("python"))
        assertSame(forFile("a.py"), CodeLanguages.forFence("py"))
        assertSame(forFile("a.js"), CodeLanguages.forFence("javascript"))
        assertSame(forFile("a.ts"), CodeLanguages.forFence("typescript"))
        assertSame(forFile("a.rs"), CodeLanguages.forFence("rust"))
        assertSame(forFile("a.go"), CodeLanguages.forFence("golang"))
        assertSame(forFile("a.json"), CodeLanguages.forFence("json"))
        assertSame(forFile("a.yml"), CodeLanguages.forFence("yaml"))
    }

    @Test
    fun `names that are not extensions`() {
        assertSame(forFile("a.cpp"), CodeLanguages.forFence("c++"))
        assertSame(forFile("a.cs"), CodeLanguages.forFence("c#"))
        assertSame(forFile("a.cs"), CodeLanguages.forFence("csharp"))
        assertSame(forFile("a.sh"), CodeLanguages.forFence("shell"))
        assertSame(forFile("a.sh"), CodeLanguages.forFence("console"))
        assertSame(forFile("a.ps1"), CodeLanguages.forFence("pwsh"))
        assertSame(forFile("Dockerfile"), CodeLanguages.forFence("docker"))
    }

    @Test
    fun `only the first word of the info string counts, in any case`() {
        val kotlin = forFile("A.kt")
        assertSame(kotlin, CodeLanguages.forFence("Kotlin"))
        assertSame(kotlin, CodeLanguages.forFence("  kotlin  "))
        assertSame(kotlin, CodeLanguages.forFence("kotlin title=\"A.kt\""))
        assertSame(kotlin, CodeLanguages.forFence("kotlin{1,3}"))
        assertSame(kotlin, CodeLanguages.forFence("kotlin,linenos"))
    }

    @Test
    fun `pandoc and prism spellings`() {
        val python = forFile("a.py")
        assertSame(python, CodeLanguages.forFence("{.python .numberLines}"))
        assertSame(python, CodeLanguages.forFence("language-python"))
        assertSame(python, CodeLanguages.forFence("lang-py"))
    }

    @Test
    fun `no tag, plain text or an unknown language shows the block plain`() {
        assertNull(CodeLanguages.forFence(null))
        assertNull(CodeLanguages.forFence(""))
        assertNull(CodeLanguages.forFence("   "))
        assertNull(CodeLanguages.forFence("{.}"))
        assertNull(CodeLanguages.forFence("text"))
        assertNull(CodeLanguages.forFence("plaintext"))
        assertNull(CodeLanguages.forFence("diff"))
        assertNull(CodeLanguages.forFence("brainfuck"))
    }

    @Test
    fun `a file name still resolves as before`() {
        assertNotNull(CodeLanguages.forFileName("main.kt"))
        assertNull(CodeLanguages.forFileName("notes.txt"))
    }
}
