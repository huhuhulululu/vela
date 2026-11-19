package vela.core.sync

/**
 * 同步文档专用的严格 JSON（第 7.3、23 节）：RFC 8259 的严格子集。
 * 不用 org.json。拒绝任何反斜杠转义、重复键、BOM、非法 UTF-8、控制字符；
 * 对象与数组嵌套不超过 3 层；明文不超过上限。
 */
object SyncJson {
    class Member(val key: String, val value: Value)

    sealed class Value {
        class Obj(val members: List<Member>) : Value()
        class Arr(val items: List<Value>) : Value()
        class Str(val text: String) : Value()
        class Num(val text: String) : Value()
        class Bool(val value: Boolean) : Value()
        data object Null : Value()
    }

    class ParseException(val reason: String) : Exception(reason)

    fun parse(input: ByteArray, limit: Int = SyncLimits.plaintextBytes, maxDepth: Int = 3): Value {
        if (input.size > limit) throw ParseException("tooLarge")
        if (input.size >= 3 && input[0] == 0xEF.toByte() && input[1] == 0xBB.toByte() && input[2] == 0xBF.toByte()) {
            throw ParseException("bom")
        }
        if (!isValidUtf8(input)) throw ParseException("invalidUTF8")
        val parser = Parser(input, maxDepth)
        parser.skipWhitespace()
        val value = parser.value(0)
        parser.skipWhitespace()
        if (!parser.atEnd) throw ParseException("syntax")
        return value
    }

    /** 严格 UTF-8：拒绝过长编码、代理码点、超过 U+10FFFF 与截断序列。 */
    fun isValidUtf8(b: ByteArray): Boolean {
        var i = 0
        val n = b.size
        while (i < n) {
            val c = b[i].toInt() and 0xFF
            if (c < 0x80) {
                i++
                continue
            }
            val need: Int
            var lo = 0x80
            var hi = 0xBF
            when (c) {
                in 0xC2..0xDF -> need = 1
                0xE0 -> {
                    need = 2
                    lo = 0xA0
                }
                in 0xE1..0xEC, in 0xEE..0xEF -> need = 2
                0xED -> {
                    need = 2
                    hi = 0x9F
                }
                0xF0 -> {
                    need = 3
                    lo = 0x90
                }
                in 0xF1..0xF3 -> need = 3
                0xF4 -> {
                    need = 3
                    hi = 0x8F
                }
                else -> return false
            }
            if (i + need >= n) return false
            val c1 = b[i + 1].toInt() and 0xFF
            if (c1 < lo || c1 > hi) return false
            var k = 2
            while (k <= need) {
                val ck = b[i + k].toInt() and 0xFF
                if (ck < 0x80 || ck > 0xBF) return false
                k++
            }
            i += need + 1
        }
        return true
    }

    private class Parser(val bytes: ByteArray, val maxDepth: Int) {
        var pos = 0
        val atEnd: Boolean get() = pos >= bytes.size

        fun skipWhitespace() {
            while (pos < bytes.size) {
                val c = bytes[pos].toInt() and 0xFF
                if (c != 0x20 && c != 0x09 && c != 0x0A && c != 0x0D) break
                pos++
            }
        }

        fun peek(): Int? = if (pos < bytes.size) bytes[pos].toInt() and 0xFF else null

        fun expect(c: Int) {
            if (peek() != c) throw ParseException("syntax")
            pos++
        }

        fun value(depth: Int): Value {
            val c = peek() ?: throw ParseException("syntax")
            return when (c) {
                '{'.code -> obj(depth + 1)
                '['.code -> arr(depth + 1)
                '"'.code -> Value.Str(string())
                't'.code -> {
                    literal("true")
                    Value.Bool(true)
                }
                'f'.code -> {
                    literal("false")
                    Value.Bool(false)
                }
                'n'.code -> {
                    literal("null")
                    Value.Null
                }
                else -> Value.Num(number())
            }
        }

        fun literal(word: String) {
            val w = word.toByteArray(Charsets.US_ASCII)
            if (pos + w.size > bytes.size) throw ParseException("syntax")
            for (i in w.indices) if (bytes[pos + i] != w[i]) throw ParseException("syntax")
            pos += w.size
        }

        fun obj(depth: Int): Value {
            if (depth > maxDepth) throw ParseException("tooDeep")
            expect('{'.code)
            val members = ArrayList<Member>()
            val seen = HashSet<String>()
            skipWhitespace()
            if (peek() == '}'.code) {
                pos++
                return Value.Obj(members)
            }
            while (true) {
                skipWhitespace()
                if (peek() != '"'.code) throw ParseException("syntax")
                val key = string()
                if (!seen.add(key)) throw ParseException("duplicateKey")
                skipWhitespace()
                expect(':'.code)
                skipWhitespace()
                members.add(Member(key, value(depth)))
                skipWhitespace()
                val c = peek() ?: throw ParseException("syntax")
                pos++
                if (c == '}'.code) return Value.Obj(members)
                if (c != ','.code) throw ParseException("syntax")
            }
        }

        fun arr(depth: Int): Value {
            if (depth > maxDepth) throw ParseException("tooDeep")
            expect('['.code)
            val items = ArrayList<Value>()
            skipWhitespace()
            if (peek() == ']'.code) {
                pos++
                return Value.Arr(items)
            }
            while (true) {
                skipWhitespace()
                items.add(value(depth))
                skipWhitespace()
                val c = peek() ?: throw ParseException("syntax")
                pos++
                if (c == ']'.code) return Value.Arr(items)
                if (c != ','.code) throw ParseException("syntax")
            }
        }

        fun string(): String {
            expect('"'.code)
            val start = pos
            while (pos < bytes.size) {
                val c = bytes[pos].toInt() and 0xFF
                if (c == '"'.code) {
                    val s = String(bytes, start, pos - start, Charsets.UTF_8)
                    pos++
                    return s
                }
                if (c == '\\'.code) throw ParseException("escape")
                if (c < 0x20) throw ParseException("controlCharacter")
                pos++
            }
            throw ParseException("syntax")
        }

        fun number(): String {
            val start = pos
            fun digits(): Int {
                val s = pos
                while (pos < bytes.size) {
                    val c = bytes[pos].toInt() and 0xFF
                    if (c < '0'.code || c > '9'.code) break
                    pos++
                }
                return pos - s
            }
            if (peek() == '-'.code) pos++
            if (peek() == '0'.code) {
                pos++
            } else if (digits() == 0) {
                throw ParseException("syntax")
            }
            if (peek() == '.'.code) {
                pos++
                if (digits() == 0) throw ParseException("syntax")
            }
            val e = peek()
            if (e == 'e'.code || e == 'E'.code) {
                pos++
                val s = peek()
                if (s == '+'.code || s == '-'.code) pos++
                if (digits() == 0) throw ParseException("syntax")
            }
            val c = peek()
            if (c != null && c in '0'.code..'9'.code) throw ParseException("syntax")
            return String(bytes, start, pos - start, Charsets.US_ASCII)
        }
    }
}
