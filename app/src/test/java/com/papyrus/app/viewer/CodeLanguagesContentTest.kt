package com.papyrus.app.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/** The name stays authoritative; the content is a second opinion for the files it cannot place. */
class CodeLanguagesContentTest {

    private fun forContent(name: String?, head: String) = CodeLanguages.forContent(name, head)

    private fun id(name: String?, head: String) = forContent(name, head)?.id

    @Test
    fun `a file with no extension is placed by its shebang`() {
        assertEquals("shell", id("deploy", "#!/bin/bash\nset -e\n"))
        assertEquals("python", id("tool", "#!/usr/bin/env python3\nimport os\n"))
        assertEquals("javascript", id("cli", "#!/usr/bin/env node\n"))
        assertEquals("ruby", id("rake-task", "#!/usr/bin/ruby\n"))
        assertEquals("powershell", id("setup", "#!/usr/bin/env pwsh\n"))
    }

    @Test
    fun `an extension no definition knows is placed by its content`() {
        assertEquals("shell", id("run.cmd", "#!/bin/sh\n"))
        assertEquals("markup", id("icon.xyz", "<svg xmlns=\"http://www.w3.org/2000/svg\"/>"))
        assertEquals("markup", id("pom.bak", "<project>\n</project>"))
        assertEquals("json", id("places.geojson", "{\"type\": \"FeatureCollection\"}"))
        assertEquals("json", id(".eslintrc", "{\n  // rules\n  \"semi\": 2\n}"))
        assertEquals("json", id("settings.hjson", "{a: 1}"))
        assertEquals("json", id("notebook", "[1, 2]"))
    }

    @Test
    fun `a name with no definition and a head that says nothing stays plain`() {
        assertNull(forContent("notes.xyz", "hello world\n"))
        assertNull(forContent("changes.patch", "--- a/x\n+++ b/x\n"))
        assertNull(forContent("script", "echo hi\n"))
        assertNull(forContent(null, ""))
        assertNull(forContent("", "plain"))
    }

    @Test
    fun `an unambiguous extension is never second-guessed by the content`() {
        // Every head here would be read as something else if the content were consulted.
        assertEquals("python", id("tool.py", "#!/bin/bash\necho hi\n"))
        assertEquals("kotlin", id("Main.kt", "<svg/>"))
        assertEquals("json", id("data.json", "<?xml version=\"1.0\"?>\n<a/>"))
        assertEquals("yaml", id("ci.yml", "{\"a\": 1}"))
        assertEquals("go", id("main.go", "#!/usr/bin/env python3\n"))
        assertEquals("cpp", id("main.cpp", "@interface Foo\n@end\n"))
        assertEquals("markup", id("index.html", "#!/bin/sh\n"))
        assertEquals("dockerfile", id("Dockerfile", "#!/bin/sh\n"))
        assertEquals("dockerfile", id("Dockerfile.dev", "{\"a\": 1}"))
        assertEquals("makefile", id("Makefile", "#!/bin/sh\n"))
    }

    @Test
    fun `a name that resolves gives the same answer as forFileName whatever the head`() {
        val heads = listOf("", "#!/bin/bash\n", "<svg/>", "{\"a\": 1}", "@interface X\n@end\n", "% matlab\n")
        for (name in listOf("a.kt", "a.java", "a.cs", "a.js", "a.ts", "a.py", "a.rb", "a.go", "a.rs", "a.sh", "a.json", "a.xml", "a.css")) {
            for (head in heads) assertSame("$name / ${head.take(12)}", CodeLanguages.forFileName(name), forContent(name, head))
        }
    }

    @Test
    fun `a dot h file keeps the c family definition whatever it holds`() {
        val cFamily = CodeLanguages.forFileName("a.cpp")
        assertSame(cFamily, forContent("a.h", "#ifndef X\n#define X\nint f(void);\n#endif\n"))
        assertSame(cFamily, forContent("a.h", "namespace n { int f(); }\n"))
        assertSame(cFamily, forContent("a.h", "#import <Foundation/Foundation.h>\n@interface Foo\n@end\n"))
        assertSame(cFamily, forContent("a.h", ""))
        assertSame(cFamily, forContent("A.H", "template <class T> T id(T);\n"))
    }

    @Test
    fun `a dot m file with objective c gets the c family definition and one with matlab stays plain`() {
        assertSame(CodeLanguages.forFileName("a.cpp"), forContent("a.m", "#import \"Foo.h\"\n@implementation Foo\n@end\n"))
        assertSame(CodeLanguages.forFileName("a.cpp"), forContent("A.M", "#include <stdio.h>\nint main(void) { return 0; }\n"))
        // MATLAB has no definition, so the name's answer, which is none, stands.
        assertNull(forContent("a.m", "% solve\nfunction y = f(x)\n  y = x.^2;\nend\n"))
        assertNull(forContent("a.m", "x = 1;\n"))
        // As before: with no head at all, a .m file is plain.
        assertNull(CodeLanguages.forFileName("a.m"))
    }

    @Test
    fun `perl and matlab are detected but have no definition, so they stay plain`() {
        assertNull(forContent("script", "#!/usr/bin/perl\nprint 1;\n"))
        assertNull(forContent("a.m", "% header\nclc\n"))
    }

    @Test
    fun `the three json kinds colour with the one json definition`() {
        val json = CodeLanguages.forFileName("a.json")
        assertSame(json, forContent("a.unknown", "{\"a\": 1}"))
        assertSame(json, forContent("a.unknown", "{\"a\": 1, // c\n}"))
        assertSame(json, forContent("a.unknown", "{a: 'x'}"))
    }

    @Test
    fun `forFileName is as it was, so the content never reaches it`() {
        assertNull(CodeLanguages.forFileName("deploy"))
        assertNull(CodeLanguages.forFileName("icon.xyz"))
        assertEquals("cpp", CodeLanguages.forFileName("util.h")?.id)
        assertEquals("dockerfile", CodeLanguages.forFileName("Dockerfile.dev")?.id)
        assertEquals("makefile", CodeLanguages.forFileName("makefile")?.id)
    }
}
