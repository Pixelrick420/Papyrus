package io.github.pixelrick420.papyrus.viewer

/** Just enough JSON for the oracle and calibration files: objects, arrays, strings, numbers, booleans and null. */
internal class Json(private val text: String) {

    private var index = 0

    fun parse(): Any? {
        val value = value()
        skip()
        require(index == text.length) { "trailing input at $index" }
        return value
    }

    private fun value(): Any? {
        skip()
        return when (text[index]) {
            '{' -> obj()
            '[' -> array()
            '"' -> string()
            't' -> literal("true", true)
            'f' -> literal("false", false)
            'n' -> literal("null", null)
            else -> number()
        }
    }

    private fun obj(): Map<String, Any?> {
        index++
        val map = LinkedHashMap<String, Any?>()
        skip()
        if (text[index] == '}') {
            index++
            return map
        }
        while (true) {
            skip()
            val key = string()
            skip()
            expect(':')
            map[key] = value()
            skip()
            if (text[index] == ',') index++ else {
                expect('}')
                return map
            }
        }
    }

    private fun array(): List<Any?> {
        index++
        val list = mutableListOf<Any?>()
        skip()
        if (text[index] == ']') {
            index++
            return list
        }
        while (true) {
            list += value()
            skip()
            if (text[index] == ',') index++ else {
                expect(']')
                return list
            }
        }
    }

    private fun string(): String {
        expect('"')
        val builder = StringBuilder()
        while (true) {
            val char = text[index++]
            when (char) {
                '"' -> return builder.toString()
                '\\' -> builder.append(escape())
                else -> builder.append(char)
            }
        }
    }

    private fun escape(): Char {
        val char = text[index++]
        return when (char) {
            'n' -> '\n'
            't' -> '\t'
            'r' -> '\r'
            'b' -> '\b'
            'f' -> '\u000C'
            'u' -> {
                index += 4
                text.substring(index - 4, index).toInt(16).toChar()
            }
            else -> char
        }
    }

    private fun number(): Double {
        val start = index
        while (index < text.length && text[index] in "+-0123456789.eE") index++
        return text.substring(start, index).toDouble()
    }

    private fun literal(token: String, value: Any?): Any? {
        require(text.startsWith(token, index)) { "expected $token at $index" }
        index += token.length
        return value
    }

    private fun expect(char: Char) {
        require(text[index] == char) { "expected $char at $index" }
        index++
    }

    private fun skip() {
        while (index < text.length && text[index].isWhitespace()) index++
    }
}
