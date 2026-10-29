package com.papyrus.app.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The guesses made when a name says nothing: a wrong one colours a file wrongly, so null is the answer to doubt. */
class LanguageDetectorTest {

    /** A name no table knows, so only the content-based rules apply. */
    private fun detect(head: String, extension: String = "") = LanguageDetector.detect(head, extension)

    @Test
    fun `a shebang names the interpreter`() {
        assertEquals(ContentKind.PYTHON, detect("#!/usr/bin/env python3\nprint(1)\n"))
        assertEquals(ContentKind.SHELL, detect("#!/bin/bash\necho hi\n"))
        assertEquals(ContentKind.SHELL, detect("#!/bin/sh\n"))
        assertEquals(ContentKind.JAVASCRIPT, detect("#!/usr/bin/env node\nconsole.log(1)\n"))
        assertEquals(ContentKind.RUBY, detect("#!/usr/bin/ruby\nputs 1\n"))
        assertEquals(ContentKind.PERL, detect("#!/usr/bin/perl -w\nprint 1;\n"))
        assertEquals(ContentKind.PHP, detect("#!/usr/bin/env php\n<?php echo 1;\n"))
        assertEquals(ContentKind.POWERSHELL, detect("#!/usr/bin/env pwsh\nWrite-Host 1\n"))
        assertEquals(ContentKind.LUA, detect("#!/usr/bin/lua\nprint(1)\n"))
        assertEquals(ContentKind.R, detect("#!/usr/bin/env Rscript\nprint(1)\n"))
    }

    @Test
    fun `a shebang is read through a version, env flags, assignments and a space after the marks`() {
        assertEquals(ContentKind.PYTHON, detect("#!/usr/bin/python3.11\n"))
        assertEquals(ContentKind.RUBY, detect("#!/usr/bin/ruby3.2\n"))
        assertEquals(ContentKind.PYTHON, detect("#!/usr/bin/env -S python3 -u\n"))
        assertEquals(ContentKind.JAVASCRIPT, detect("#!/usr/bin/env NODE_ENV=production node\n"))
        assertEquals(ContentKind.SHELL, detect("#! /bin/bash\n"))
        assertEquals(ContentKind.SHELL, detect("#!/bin/bash\r\necho hi\r\n"))
    }

    @Test
    fun `a shebang that names nothing known, or is not on the first line, says nothing`() {
        assertNull(detect("#!/usr/bin/awk -f\n{ print }\n"))
        assertNull(detect("#!/usr/bin/env\n"))
        assertNull(detect("#!\n"))
        assertNull(detect("echo hi\n#!/bin/bash\n"))
        assertNull(detect("# !/bin/bash\n"))
    }

    @Test
    fun `an xml declaration or a doctype says markup whatever the root is`() {
        assertEquals(ContentKind.MARKUP, detect("<?xml version=\"1.0\"?>\n<anything/>"))
        assertEquals(ContentKind.MARKUP, detect("<!DOCTYPE html>\n<p>hi</p>"))
        assertEquals(ContentKind.MARKUP, detect("<!doctype html>"))
    }

    @Test
    fun `a known root element says markup`() {
        assertEquals(ContentKind.MARKUP, detect("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 1 1\"/>"))
        assertEquals(ContentKind.MARKUP, detect("<html lang=\"en\">"))
        assertEquals(ContentKind.MARKUP, detect("<HTML>"))
        assertEquals(ContentKind.MARKUP, detect("<project xmlns=\"http://maven.apache.org/POM/4.0.0\">"))
        assertEquals(ContentKind.MARKUP, detect("<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\">"))
        assertEquals(ContentKind.MARKUP, detect("<resources>\n<string name=\"a\">b</string>"))
        assertEquals(ContentKind.MARKUP, detect("<rss version=\"2.0\">"))
        assertEquals(ContentKind.MARKUP, detect("<xsl:stylesheet version=\"1.0\">"))
        assertEquals(ContentKind.MARKUP, detect("\uFEFF  \n<feed xmlns=\"http://www.w3.org/2005/Atom\">"))
    }

    @Test
    fun `comments and processing instructions before the root are skipped`() {
        assertEquals(ContentKind.MARKUP, detect("<!-- generated -->\n<!-- twice -->\n<manifest>"))
        assertEquals(ContentKind.MARKUP, detect("<?php /* tpl */ ?>\n<html>"))
    }

    @Test
    fun `an unknown root, an unterminated comment, or text that does not open with a tag says nothing`() {
        assertNull(detect("<foo><bar/></foo>"))
        assertNull(detect("<!-- never closed\n<html>"))
        assertNull(detect("hello <html>"))
        assertNull(detect("<"))
        assertNull(detect("<3 you"))
    }

    @Test
    fun `plain json is json`() {
        assertEquals(ContentKind.JSON, detect("{\"a\": 1, \"b\": [true, false, null]}"))
        assertEquals(ContentKind.JSON, detect("[\n  {\"id\": 1},\n  {\"id\": 2}\n]"))
        assertEquals(ContentKind.JSON, detect("{}"))
        assertEquals(ContentKind.JSON, detect("[]"))
        assertEquals(ContentKind.JSON, detect("[1, 2, 3]"))
        assertEquals(ContentKind.JSON, detect("\uFEFF{\"bom\": true}"))
    }

    @Test
    fun `comments make json jsonc, in a line or a block`() {
        assertEquals(ContentKind.JSONC, detect("{\n  // the name\n  \"a\": 1\n}"))
        assertEquals(ContentKind.JSONC, detect("/* header */\n{\"a\": 1}"))
        assertEquals(ContentKind.JSONC, detect("{ \"a\": /* inline */ 1 }"))
    }

    @Test
    fun `a trailing comma makes json jsonc`() {
        assertEquals(ContentKind.JSONC, detect("{\"a\": 1,}"))
        assertEquals(ContentKind.JSONC, detect("[1, 2, 3,\n]"))
        assertEquals(ContentKind.JSONC, detect("{\"a\": [1, 2,], \"b\": 2}"))
    }

    @Test
    fun `an unquoted key or a single quoted string makes json5, which wins over comments`() {
        assertEquals(ContentKind.JSON5, detect("{a: 1, \"b\": 2}"))
        assertEquals(ContentKind.JSON5, detect("{\"a\": 1, b: 2}"))
        assertEquals(ContentKind.JSON5, detect("{\n  name: 'x',\n}"))
        assertEquals(ContentKind.JSON5, detect("{\"a\": 'single'}"))
        assertEquals(ContentKind.JSON5, detect("{ // note\n  key: 1 }"))
    }

    @Test
    fun `a slash pair, a comma or a brace inside a string is not what it looks like`() {
        assertEquals(ContentKind.JSON, detect("{\"url\": \"http://example.com/a//b\"}"))
        assertEquals(ContentKind.JSON, detect("{\"a\": \"x,}\", \"b\": \"/* not */\"}"))
        assertEquals(ContentKind.JSON, detect("{\"q\": \"it's\"}"))
        assertEquals(ContentKind.JSON, detect("{\"esc\": \"a\\\"//b\"}"))
    }

    @Test
    fun `a bare word that is a value, or in an array, is not a key`() {
        assertEquals(ContentKind.JSON, detect("{\"a\": true, \"b\": null}"))
        assertEquals(ContentKind.JSON, detect("[true, false, null]"))
    }

    @Test
    fun `text that opens with a brace or bracket but is not json says nothing`() {
        assertNull(detect("[section]\nkey=value\n"))
        assertNull(detect("[core]\n\trepositoryformatversion = 0\n"))
        assertNull(detect("{{ template }}\n"))
        assertNull(detect("{% if x %}\n"))
        assertNull(detect("{\\rtf1\\ansi}"))
        assertNull(detect("{ not json at all }"))
        assertNull(detect("{"))
        assertNull(detect("["))
    }

    @Test
    fun `plain prose and bare words are not json, though they start like a literal`() {
        assertNull(detect("hello world\n"))
        assertNull(detect("Atrue story\n"))
        assertNull(detect("true\n"))
        assertNull(detect("null"))
        assertNull(detect("1234\n"))
        assertNull(detect("[true_color]\nenabled = 1\n"))
        assertNull(detect("[null_values]\n"))
        assertEquals(ContentKind.JSON, detect("[true]"))
        assertEquals(ContentKind.JSON, detect("[ null, 1 ]"))
    }

    @Test
    fun `a file that opens with comments before its brace is jsonc, and one with only comments says nothing`() {
        assertEquals(ContentKind.JSONC, detect("// config\n// more\n{\"a\": 1}"))
        assertEquals(ContentKind.JSONC, detect("/* one */ /* two */\n[1]"))
        assertEquals(ContentKind.JSON5, detect("// config\n{a: 1}"))
        assertNull(detect("// just a note\n"))
        assertNull(detect("/* never closed\n{\"a\": 1}"))
        assertNull(detect("// note\nhello\n"))
    }

    @Test
    fun `an unterminated string stops at its line, so a stray quote does not swallow the rest`() {
        // The apostrophe is never closed; the comment two lines down is still a comment.
        assertEquals(ContentKind.JSON5, detect("{\"a\": 'oops\n// still seen\n}"))
    }

    @Test
    fun `an empty or blank head says nothing`() {
        assertNull(detect(""))
        assertNull(detect("   \n\n"))
        assertNull(detect("", "h"))
        assertNull(detect("", "m"))
    }

    @Test
    fun `a header with nothing but c in it is c`() {
        val header = "#ifndef ADD_H\n#define ADD_H\n\nint add(int a, int b);\n\n#endif\n"
        assertEquals(ContentKind.C, detect(header, "h"))
    }

    @Test
    fun `a c header that guards itself for c plus plus is still c`() {
        val header = "#ifdef __cplusplus\nextern \"C\" {\n#endif\nint add(int a, int b);\n#ifdef __cplusplus\n}\n#endif\n"
        assertEquals(ContentKind.C, detect(header, "h"))
    }

    @Test
    fun `a header with c plus plus constructs is c plus plus`() {
        assertEquals(ContentKind.CPP, detect("namespace util {\nint f();\n}\n", "h"))
        assertEquals(ContentKind.CPP, detect("template <typename T>\nT id(T t);\n", "h"))
        assertEquals(ContentKind.CPP, detect("class Widget {\npublic:\n  Widget();\n};\n", "h"))
        assertEquals(ContentKind.CPP, detect("struct S {\npublic:\n  int a;\n};\n", "h"))
        assertEquals(ContentKind.CPP, detect("#include <vector>\n", "h"))
        assertEquals(ContentKind.CPP, detect("void f(std::string s);\n", "h"))
        assertEquals(ContentKind.CPP, detect("auto p = nullptr;\n", "h"))
        assertEquals(ContentKind.CPP, detect("constexpr int N = 3;\n", "h"))
    }

    @Test
    fun `a c include of a dotted header is not a c plus plus marker, and a word in a comment is not a class`() {
        assertEquals(ContentKind.C, detect("#include <stdio.h>\n#include <sys/types.h>\n", "h"))
        assertEquals(ContentKind.C, detect("/*\n * A class of problems.\n */\nint f(void);\n", "h"))
    }

    @Test
    fun `a header with objective c constructs is objective c, even beside c plus plus ones`() {
        assertEquals(ContentKind.OBJC, detect("#import <Foundation/Foundation.h>\n@interface Foo : NSObject\n@end\n", "h"))
        assertEquals(ContentKind.OBJC, detect("@protocol Delegate <NSObject>\n@end\n", "h"))
        assertEquals(ContentKind.OBJC, detect("@property (nonatomic) int a;\n", "h"))
        assertEquals(ContentKind.OBJC, detect("static NSString *const Key = @\"key\";\n", "h"))
        assertEquals(ContentKind.OBJC, detect("NSString *name(void);\n", "h"))
        assertEquals(ContentKind.OBJC, detect("namespace x {}\n@interface Mixed\n@end\n", "h"))
    }

    @Test
    fun `an nsec style macro is not a foundation name`() {
        assertEquals(ContentKind.C, detect("#define NSEC_PER_SEC 1000000000\n", "h"))
    }

    @Test
    fun `a dot m file with objective c constructs is objective c`() {
        assertEquals(ContentKind.OBJC, detect("#import \"Foo.h\"\n@implementation Foo\n@end\n", "m"))
        assertEquals(ContentKind.OBJC, detect("@autoreleasepool { NSLog(@\"hi\"); }\n", "m"))
        assertEquals(ContentKind.OBJC, detect("#include <stdio.h>\nint main(void) { return 0; }\n", "m"))
        assertEquals(ContentKind.OBJC, detect("int main(int argc, char **argv) {\n  return 0;\n}\n", "m"))
    }

    @Test
    fun `a dot m file with matlab or octave constructs is matlab`() {
        assertEquals(ContentKind.MATLAB, detect("% Solve the system\nfunction y = f(x)\n  y = x.^2;\nend\n", "m"))
        assertEquals(ContentKind.MATLAB, detect("clc; clear all;\nx = linspace(0, 1);\n", "m"))
        assertEquals(ContentKind.MATLAB, detect("classdef Shape\n  properties\n  end\nend\n", "m"))
        assertEquals(ContentKind.MATLAB, detect("1;\nfunction f()\nendfunction\n", "m"))
        assertEquals(ContentKind.MATLAB, detect("total = a + ...\n        b;\n", "m"))
        // An anonymous function handle has an at sign and is not an Objective-C directive.
        assertEquals(ContentKind.MATLAB, detect("% handle\nsq = @(x) x.^2;\n", "m"))
    }

    @Test
    fun `a dot m file with no marker either way says nothing`() {
        assertNull(detect("x = 1;\ny = 2;\n", "m"))
    }

    @Test
    fun `a dot h or dot m file is settled between its own candidates, not by a shebang or json`() {
        assertEquals(ContentKind.C, detect("#!/bin/bash\nint x;\n", "h"))
        assertEquals(ContentKind.C, detect("{\"a\": 1}", "h"))
        assertNull(detect("#!/bin/bash\necho\n", "m"))
    }

    @Test
    fun `only the start of a long file is read`() {
        val filler = "\n".repeat(TextSniffer.MAX_SNIFF_BYTES + 10)
        assertEquals(ContentKind.OBJC, detect("@interface Foo\n@end\n$filler", "m"))
        // The same marker past the limit is not seen.
        assertNull(detect("$filler@interface Foo\n@end\n", "m"))
        // Nor is a comment that comes after it: this is plain json as far as the head goes.
        assertEquals(ContentKind.JSON, detect("{\"a\": 1" + " ".repeat(TextSniffer.MAX_SNIFF_BYTES) + "// late\n}"))
    }

    @Test
    fun `a head of blank lines is cheap to look at`() {
        // Line-anchored markers once read across the blank lines from every line start: over a second for 8 KB.
        val blank = "\n".repeat(TextSniffer.MAX_SNIFF_BYTES)
        val started = System.nanoTime()
        for (extension in listOf("m", "h", "")) detect(blank, extension)
        val millis = (System.nanoTime() - started) / 1_000_000
        assertTrue("took $millis ms", millis < 500)
    }

    @Test
    fun `the head of a file is its first chunks, cut at the limit`() {
        assertEquals("a\nb\n", LanguageDetector.headOf(listOf("a\n", "b\n")))
        assertEquals("", LanguageDetector.headOf(emptyList()))

        val chunks = List(40) { "x".repeat(1000) + "\n" }
        val head = LanguageDetector.headOf(chunks)
        assertEquals(TextSniffer.MAX_SNIFF_BYTES, head.length)
        assertEquals(chunks.joinToString("").take(TextSniffer.MAX_SNIFF_BYTES), head)
    }
}
