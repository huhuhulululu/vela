package vela.core.base.site

import java.net.URI
import vela.core.base.model.Gender
import vela.core.base.model.Region
import vela.core.base.model.Room
import vela.core.base.model.isSwiftWhitespace
import vela.core.base.model.lowerRoot
import vela.core.base.model.splitWhere
import vela.core.base.model.trimSwiftWhitespace
import vela.core.base.net.isUnreserved
import vela.core.base.net.percentEncode

class CatalogQuery(
    val gender: Gender? = null,
    val region: Region? = null,
    val tag: String? = null,
    val limit: Int = 50,
    val offset: Int = 0,
    val newCams: Boolean = false,
    val keywords: String? = null,
) {
    fun url(): URI {
        val items = ArrayList<Pair<String, String>>(7)
        items.add("limit" to limit.coerceAtLeast(1).coerceAtMost(maxPageSize).toString())
        items.add("offset" to offset.coerceAtLeast(0).toString())
        val selectedGender = gender
        if (selectedGender != null) items.add("genders" to selectedGender.wire)
        val selectedRegion = region
        if (selectedRegion != null) items.add("regions" to selectedRegion.siteCode)
        val hashtag = normalizedHashtag(tag)
        if (hashtag != null) items.add("hashtags" to hashtag)
        if (newCams) items.add("new_cams" to "true")
        val words = SiteSearchText.normalized(keywords)
        if (words != null) items.add("keywords" to words)
        val query = items.joinToString("&") { (name, value) ->
            name + "=" + percentEncode(value, ::isUnreserved)
        }
        return URI("https://chaturbate.com/api/ts/roomlist/room-list/?$query")
    }

    companion object {
        /** 站点列表接口单页上限（limit=500 会 400，实测 90 可用）。 */
        const val maxPageSize: Int = 90

        /** 站点的 hashtags 参数不带 # 且只收单个标签：去掉前导 #，取第一个词。 */
        internal fun normalizedHashtag(raw: String?): String? {
            if (raw == null) return null
            val trimmed = raw.trimSwiftWhitespace().dropWhile { it == '#' }
            val word = trimmed.splitWhere { it.isSwiftWhitespace() || it == ',' }.firstOrNull().orEmpty()
            return word.lowerRoot().ifEmpty { null }
        }
    }
}

data class CatalogPage(
    val rooms: List<Room>,
    val total: Int,
    val nextOffset: Int,
)

sealed class CatalogFailure : Exception() {
    /** 正文命中站点质询页。 */
    data object Challenge : CatalogFailure()

    /** 非 2xx（含正文不是质询页的 403 / 503）。 */
    data class Status(val code: Int) : CatalogFailure()

    data object Malformed : CatalogFailure()

    val isChallenge: Boolean
        get() = this is Challenge
}
