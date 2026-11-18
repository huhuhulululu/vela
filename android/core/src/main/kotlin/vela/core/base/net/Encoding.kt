package vela.core.base.net

private const val HEX = "0123456789ABCDEF"

/** 百分号编码不在 [allowed] 里的 UTF-8 字节。十六进制用大写。 */
internal fun percentEncode(text: String, allowed: (Int) -> Boolean): String {
    val bytes = text.toByteArray(Charsets.UTF_8)
    val out = StringBuilder(bytes.size)
    for (byte in bytes) {
        val value = byte.toInt() and 0xFF
        if (allowed(value)) {
            out.append(value.toChar())
        } else {
            out.append('%')
            out.append(HEX[value ushr 4])
            out.append(HEX[value and 0x0F])
        }
    }
    return out.toString()
}

internal fun isUnreserved(byte: Int): Boolean {
    val c = byte.toChar()
    return c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '-' || c == '.' || c == '_' || c == '~'
}

/** Swift `.urlPathAllowed` 的常用子集：非保留字符再加路径里允许的标点。 */
internal fun isPathAllowed(byte: Int): Boolean {
    if (isUnreserved(byte)) return true
    return byte.toChar() in "!$&'()*+,;=:@/"
}
