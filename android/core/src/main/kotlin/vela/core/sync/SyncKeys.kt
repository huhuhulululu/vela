package vela.core.sync

import vela.core.base.model.CurationKeys
import vela.core.base.model.trimSwiftWhitespace

/** 同步文档里的键规则（第 6.3 节）。 */
object SyncKeys {
    const val projectedSite = "cb"
    val settingKeys = setOf("publicOnly", "infoAlwaysOn")

    fun isRoomKey(key: String): Boolean {
        val b = key.toByteArray(Charsets.UTF_8)
        val colon = b.indexOf(':'.code.toByte())
        if (colon < 0) return false
        val siteLen = colon
        val userLen = b.size - colon - 1
        if (siteLen !in 2..8) return false
        if (!isLower(b[0])) return false
        for (i in 1 until siteLen) if (!isLower(b[i]) && !isDigit(b[i])) return false
        return isUsername(b, colon + 1, userLen)
    }

    private fun isUsername(b: ByteArray, start: Int, len: Int): Boolean {
        if (len !in 2..30) return false
        for (i in start until start + len) {
            val c = b[i]
            if (!isLower(c) && !isDigit(c) && c != '_'.code.toByte()) return false
        }
        return true
    }

    private fun isLower(c: Byte) = c in 'a'.code.toByte()..'z'.code.toByte()
    private fun isDigit(c: Byte) = c in '0'.code.toByte()..'9'.code.toByte()
    private fun isAlpha(c: Int) = c in 'a'.code..'z'.code || c in 'A'.code..'Z'.code

    /** 本机无前缀的名字映射为 `cb:` + `Curation.key`；不合规返回 null。 */
    fun roomKey(localName: String): String? {
        val key = projectedSite + ":" + CurationKeys.key(localName)
        return if (isRoomKey(key)) key else null
    }

    fun siteOf(key: String): String {
        val i = key.indexOf(':')
        return if (i < 0) key else key.substring(0, i)
    }

    fun usernameOf(key: String): String {
        val i = key.indexOf(':')
        return if (i < 0) key else key.substring(i + 1)
    }

    fun isProjected(roomKey: String): Boolean = siteOf(roomKey) == projectedSite

    /** `n` 的载荷：`^[A-Za-z0-9_]{2,30}$`，按 ASCII 转小写后等于键的用户名部分。 */
    fun isDisplayName(name: String, roomKey: String): Boolean {
        val b = name.toByteArray(Charsets.UTF_8)
        if (b.size !in 2..30) return false
        for (c in b) {
            val v = c.toInt() and 0xFF
            if (!isAlpha(v) && !isDigit(c) && c != '_'.code.toByte()) return false
        }
        return asciiLower(name).utf8Equals(usernameOf(roomKey))
    }

    fun asciiLower(s: String): String {
        val b = s.toByteArray(Charsets.UTF_8)
        for (i in b.indices) {
            val c = b[i].toInt() and 0xFF
            if (c in 65..90) b[i] = (c + 32).toByte()
        }
        return String(b, Charsets.UTF_8)
    }

    fun displayName(original: String, roomKey: String): String {
        val trimmed = original.trimSwiftWhitespace()
        return if (isDisplayName(trimmed, roomKey)) trimmed else usernameOf(roomKey)
    }

    fun isGenderKey(key: String): Boolean = key == "g:f" || key == "g:m" || key == "g:t" || key == "g:c"

    /** 同步标签域的码点条件（第 6.3 节第 1 步）。 */
    fun isSyncTagScalar(cp: Int, uni: SyncUnicode = SyncChars.unicode): Boolean {
        if (cp > 0xFFFF || cp in 0xE000..0xF8FF) return false
        if (cp in 0xD800..0xDFFF) return false
        if (isNonCharacter(cp)) return false
        if (!uni.ageAtMost13(cp)) return false
        if (uni.isWhiteSpace(cp)) return false
        if (isControl(cp)) return false
        if (cp == '"'.code || cp == '\\'.code) return false
        return true
    }

    fun isNonCharacter(cp: Int): Boolean {
        if (cp in 0xFDD0..0xFDEF) return true
        return cp and 0xFFFE == 0xFFFE
    }

    fun isControl(cp: Int): Boolean = cp <= 0x1F || cp in 0x7F..0x9F

    fun lowercasedPerScalar(cps: IntArray, uni: SyncUnicode = SyncChars.unicode): String {
        val sb = StringBuilder()
        for (cp in cps) for (m in uni.lowercase(cp)) sb.appendCodePoint(m)
        return sb.toString()
    }

    /** 原始标签 → 同步键；域外返回 null。 */
    fun syncTagKey(raw: String, uni: SyncUnicode = SyncChars.unicode): String? {
        val scalars = raw.codePoints().toArray()
        if (scalars.size !in 1..64) return null
        if (scalars.any { !isSyncTagScalar(it, uni) }) return null
        val lowerCps = ArrayList<Int>()
        for (cp in scalars) {
            for (m in uni.lowercase(cp)) {
                if (!isSyncTagScalar(m, uni)) return null
                lowerCps.add(m)
            }
        }
        val lower = StringBuilder().apply { lowerCps.forEach { appendCodePoint(it) } }.toString()
        val key = uni.nfc("t:$lower")
        val bytes = key.toByteArray(Charsets.UTF_8)
        if (bytes.size < 2 || bytes.size - 2 > 128) return null
        return key
    }

    /** 接收方检查：码点条件、NFC、不超过 128 字节，且再做一遍第 3、4 步结果不变。 */
    fun isSyncTagKey(key: String, uni: SyncUnicode = SyncChars.unicode): Boolean {
        val b = key.toByteArray(Charsets.UTF_8)
        if (b.size <= 2 || b[0] != 't'.code.toByte() || b[1] != ':'.code.toByte() || b.size - 2 > 128) return false
        val part = String(b, 2, b.size - 2, Charsets.UTF_8)
        val scalars = part.codePoints().toArray()
        if (scalars.any { !isSyncTagScalar(it, uni) }) return false
        if (!uni.nfc(key).utf8Equals(key)) return false
        val again = uni.nfc("t:" + lowercasedPerScalar(scalars, uni))
        return again.utf8Equals(key)
    }

    fun isVetoKey(key: String, uni: SyncUnicode = SyncChars.unicode): Boolean = isGenderKey(key) || isSyncTagKey(key, uni)

    /** 本机口味键 → 同步键；域外返回 null。 */
    fun syncVetoKey(localKey: String, uni: SyncUnicode = SyncChars.unicode): String? {
        if (localKey.startsWith("g:")) return if (isGenderKey(localKey)) localKey else null
        val b = localKey.toByteArray(Charsets.UTF_8)
        if (b.size <= 2 || b[0] != 't'.code.toByte() || b[1] != ':'.code.toByte()) return null
        return syncTagKey(String(b, 2, b.size - 2, Charsets.UTF_8), uni)
    }
}
