package vela.core.sync

/** 第 4.2 节的编码规则：base64url（无填充、拒绝非规范）、小写 hex、无前导零的十进制。 */
object SyncEncoding {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
    private val reverse = IntArray(256) { -1 }.also { table ->
        ALPHABET.forEachIndexed { i, c -> table[c.code] = i }
    }

    fun base64url(data: ByteArray): String {
        val out = StringBuilder((data.size * 4 + 2) / 3)
        var i = 0
        while (i + 3 <= data.size) {
            val n = (data[i].toInt() and 0xFF shl 16) or (data[i + 1].toInt() and 0xFF shl 8) or (data[i + 2].toInt() and 0xFF)
            out.append(ALPHABET[n shr 18 and 63])
            out.append(ALPHABET[n shr 12 and 63])
            out.append(ALPHABET[n shr 6 and 63])
            out.append(ALPHABET[n and 63])
            i += 3
        }
        val rest = data.size - i
        if (rest == 1) {
            val n = data[i].toInt() and 0xFF shl 16
            out.append(ALPHABET[n shr 18 and 63])
            out.append(ALPHABET[n shr 12 and 63])
        } else if (rest == 2) {
            val n = (data[i].toInt() and 0xFF shl 16) or (data[i + 1].toInt() and 0xFF shl 8)
            out.append(ALPHABET[n shr 18 and 63])
            out.append(ALPHABET[n shr 12 and 63])
            out.append(ALPHABET[n shr 6 and 63])
        }
        return out.toString()
    }

    /**
     * 严格解码：拒绝 `=`、空白、字母表外字符与末位未用比特不为 0 的非规范编码。
     * 实现口径：解码后重新编码必须与输入逐字节相等。
     */
    fun decodeBase64url(text: String): ByteArray? {
        val chars = text.toByteArray(Charsets.US_ASCII).let { ascii ->
            if (ascii.size != text.length) return null
            ascii
        }
        if (chars.size % 4 == 1) return null
        val out = ArrayList<Byte>(chars.size * 3 / 4)
        var acc = 0
        var bits = 0
        for (c in chars) {
            val v = reverse[c.toInt() and 0xFF]
            if (v < 0) return null
            acc = acc shl 6 or v
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out.add((acc shr bits and 0xFF).toByte())
                acc = if (bits == 0) 0 else acc and ((1 shl bits) - 1)
            }
        }
        if (bits > 0 && acc and ((1 shl bits) - 1) != 0) return null
        val bytes = out.toByteArray()
        if (base64url(bytes) != text) return null
        return bytes
    }

    fun hex(data: ByteArray): String {
        val digits = "0123456789abcdef"
        val out = StringBuilder(data.size * 2)
        for (b in data) {
            val v = b.toInt() and 0xFF
            out.append(digits[v shr 4])
            out.append(digits[v and 15])
        }
        return out.toString()
    }

    /** 只接受小写 hex，长度为偶数。 */
    fun decodeHex(text: String): ByteArray? {
        if (text.length % 2 != 0) return null
        val out = ByteArray(text.length / 2)
        var i = 0
        while (i < text.length) {
            val hi = nibble(text[i].code) ?: return null
            val lo = nibble(text[i + 1].code) ?: return null
            out[i / 2] = (hi shl 4 or lo).toByte()
            i += 2
        }
        return out
    }

    fun nibble(c: Int): Int? = when (c) {
        in '0'.code..'9'.code -> c - '0'.code
        in 'a'.code..'f'.code -> c - 'a'.code + 10
        else -> null
    }

    const val maxSafeInteger: Long = (1L shl 53) - 1

    /** 十进制：无前导零（`0` 除外）、无符号、无小数与指数，0 到 2^53−1。 */
    fun parseDecimal(text: String): Long? {
        if (text.isEmpty() || text.length > 16) return null
        if (text.length != 1 && text[0] == '0') return null
        var value = 0L
        for (c in text) {
            if (c !in '0'..'9') return null
            value = value * 10 + (c.code - '0'.code)
        }
        return if (value <= maxSafeInteger) value else null
    }

    fun formatDecimal(n: Long): String {
        require(n >= 0)
        return n.toString()
    }
}

/** UTF-8 字节序：小于为 true。协议里的键与寄存器比较都走这里，不用平台字符串序。 */
fun String.utf8Precedes(other: String): Boolean = utf8Compare(other) < 0

fun String.utf8Compare(other: String): Int {
    val a = toByteArray(Charsets.UTF_8)
    val b = other.toByteArray(Charsets.UTF_8)
    val n = minOf(a.size, b.size)
    for (i in 0 until n) {
        val da = a[i].toInt() and 0xFF
        val db = b[i].toInt() and 0xFF
        if (da != db) return da - db
    }
    return a.size - b.size
}

fun String.utf8Equals(other: String): Boolean = toByteArray(Charsets.UTF_8).contentEquals(other.toByteArray(Charsets.UTF_8))

fun ByteArray.toAscii(): String = String(this, Charsets.US_ASCII)
