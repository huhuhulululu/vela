package vela.core.base.model

import vela.core.base.site.CatalogQuery

enum class FeedSource {
    /** 猜你喜欢。旧版存的是 `recommended`，由 [restore] 映射。 */
    guess,
    popular,
    fresh,
    favorites,

    ;

    /** 单次请求的条数（站点接口上限 90）：热门沿用小页，推荐与新开播取满一页。 */
    val pageLimit: Int
        get() = when (this) {
            popular, favorites -> 40
            guess, fresh -> CatalogQuery.maxPageSize
        }

    /** 新开播改用站点自己的「新开播」筛选。 */
    val usesNewCams: Boolean
        get() = this == fresh

    /** 一次加载连续取几页。 */
    val pageCount: Int
        get() = when (this) {
            popular, favorites -> 1
            guess, fresh -> 3
        }

    companion object {
        /** 读存下的来源名：旧版 `recommended` 映射到猜你喜欢，认不出的返回 null。 */
        fun restore(raw: String?): FeedSource? {
            if (raw == null) return null
            if (raw == "recommended") return guess
            return entries.firstOrNull { it.name == raw }
        }
    }
}
