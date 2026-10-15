package cc.savely.android.data

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener
import java.math.BigDecimal

/** Android JSONTokener rounds decimal tokens through Double; keep the server's decimal value. */
internal object ExactJson {
    fun parse(text: String): Any = Parser(text).parse()

    private class Parser(private val text: String) {
        private var index = 0

        fun parse(): Any {
            if (text.length > MAX_CHARACTERS) invalid()
            val value = value(0)
            whitespace()
            if (index != text.length) invalid()
            return value
        }

        private fun value(depth: Int): Any {
            if (depth > MAX_DEPTH) invalid()
            whitespace()
            return when (text.getOrNull(index)) {
                '{' -> objectValue(depth + 1)
                '[' -> arrayValue(depth + 1)
                '"' -> stringValue()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", JSONObject.NULL)
                '-', in '0'..'9' -> numberValue()
                else -> invalid()
            }
        }

        private fun objectValue(depth: Int): JSONObject {
            if (depth > MAX_DEPTH) invalid()
            index++
            val result = JSONObject()
            whitespace()
            if (consume('}')) return result
            while (true) {
                whitespace()
                if (text.getOrNull(index) != '"') invalid()
                val key = stringValue()
                if (result.has(key)) invalid()
                whitespace()
                if (!consume(':')) invalid()
                result.put(key, value(depth))
                whitespace()
                if (consume('}')) return result
                if (!consume(',')) invalid()
            }
        }

        private fun arrayValue(depth: Int): JSONArray {
            if (depth > MAX_DEPTH) invalid()
            index++
            val result = JSONArray()
            whitespace()
            if (consume(']')) return result
            while (true) {
                result.put(value(depth))
                whitespace()
                if (consume(']')) return result
                if (!consume(',')) invalid()
            }
        }

        private fun stringValue(): String {
            val start = index++
            while (index < text.length) {
                when (val char = text[index++]) {
                    '"' -> return JSONTokener(text.substring(start, index)).nextValue() as? String ?: invalid()
                    '\\' -> {
                        when (text.getOrNull(index++)) {
                            '"', '\\', '/', 'b', 'f', 'n', 'r', 't' -> Unit
                            'u' -> {
                                if (index + 4 > text.length || text.substring(index, index + 4).any { it.digitToIntOrNull(16) == null }) invalid()
                                index += 4
                            }
                            else -> invalid()
                        }
                    }
                    else -> if (char.code < 0x20) invalid()
                }
            }
            invalid()
        }

        private fun numberValue(): Number {
            val start = index
            while (text.getOrNull(index)?.let { it in "-+0123456789.eE" } == true) index++
            val token = text.substring(start, index)
            if (token.length > MAX_NUMBER_CHARACTERS || !NUMBER.matches(token)) invalid()
            val decimal = try { BigDecimal(token) } catch (_: NumberFormatException) { invalid() }
            if (decimal.precision() > MAX_NUMBER_PRECISION || decimal.scale() !in -MAX_NUMBER_SCALE..MAX_NUMBER_SCALE) invalid()
            return if (token.none { it == '.' || it == 'e' || it == 'E' }) token.toLongOrNull() ?: decimal else decimal
        }

        private fun literal(expected: String, result: Any): Any {
            if (!text.startsWith(expected, index)) invalid()
            index += expected.length
            return result
        }

        private fun consume(char: Char): Boolean {
            if (text.getOrNull(index) != char) return false
            index++
            return true
        }

        private fun whitespace() {
            while (text.getOrNull(index)?.let { it in " \r\n\t" } == true) index++
        }

        private fun invalid(): Nothing = throw JSONException("Invalid JSON")
    }

    private const val MAX_DEPTH = 64
    private const val MAX_CHARACTERS = 32 * 1024 * 1024
    private const val MAX_NUMBER_CHARACTERS = 256
    private const val MAX_NUMBER_PRECISION = 128
    private const val MAX_NUMBER_SCALE = 1000
    private val NUMBER = Regex("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?")
}
