package vela.core.base.model

import java.time.Instant
import java.util.Locale

/** Swift `CharacterSet.whitespacesAndNewlines` 的近似：Java 空白再加上不换行空格。 */
internal fun Char.isSwiftWhitespace(): Boolean = isWhitespace() || Character.isSpaceChar(this)

internal fun String.trimSwiftWhitespace(): String = trim { it.isSwiftWhitespace() }

/** 按谓词切开，丢掉空段。对齐 Swift `split(whereSeparator:)` 的默认行为。 */
internal fun String.splitWhere(predicate: (Char) -> Boolean): List<String> {
    val parts = ArrayList<String>()
    var start = -1
    for (index in indices) {
        if (predicate(this[index])) {
            if (start >= 0) {
                parts.add(substring(start, index))
                start = -1
            }
        } else if (start < 0) {
            start = index
        }
    }
    if (start >= 0) parts.add(substring(start))
    return parts
}

internal fun String.lowerRoot(): String = lowercase(Locale.ROOT)

/**
 * Swift `Date.timeIntervalSince1970`。
 * `Instant` 的 nano 恒为 0..999_999_999，负时刻用 `epochSecond + nano/1e9` 即可（例如 -1.5 秒是 epochSecond=-2、nano=5e8）。
 */
fun Instant.timeIntervalSince1970(): Double = epochSecond.toDouble() + nano.toDouble() / 1_000_000_000.0
