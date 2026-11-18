package vela.core.base.site

import vela.core.base.model.isSwiftWhitespace
import vela.core.base.model.splitWhere

/** `SiteSearch.normalized`。C5 的 `SiteSearch` 转调这里，截断长度用 [maxLength]。 */
object SiteSearchText {
    const val maxLength: Int = 64

    /** 去掉首尾空白、合并中间空白、截断到上限；空白返回 null。 */
    fun normalized(raw: String?): String? {
        if (raw == null) return null
        val joined = raw.splitWhere { it.isSwiftWhitespace() }.joinToString(" ")
        val clipped = joined.take(maxLength)
        return clipped.ifEmpty { null }
    }
}
