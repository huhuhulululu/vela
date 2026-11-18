package vela.core.feed

import vela.core.base.model.FeedSource
import vela.core.base.model.Room
import vela.core.base.model.dedupeRooms
import vela.core.base.model.lowerRoot
import vela.core.base.site.CatalogPage
import vela.core.base.site.CatalogQuery
import vela.core.base.site.SiteSearchText

/**
 * 全站搜索。打字过程中不发请求，只在回车时查。
 * [normalized] 转调 L0a [SiteSearchText]，截断长度共用 [SiteSearchText.maxLength]。
 */
object SiteSearch {
    /** 一次只取一页（站点单页上限）。 */
    const val pageSize: Int = CatalogQuery.maxPageSize

    /** 搜索词最长字符数，超出截断。与 [SiteSearchText.maxLength] 相同。 */
    const val maxLength: Int = SiteSearchText.maxLength

    sealed class Submit {
        /** 沿用本地处理（直达用户名或本地过滤）。 */
        data object Local : Submit()

        /** 用这个词查全站。 */
        data class Remote(val keyword: String) : Submit()

        /** 空白，或与正在显示的全站搜索词相同。 */
        data object Ignore : Submit()
    }

    sealed class Edit {
        /** 不在全站搜索里：照常本地过滤。 */
        data object Filter : Edit()

        /** 在全站搜索里继续打字：不发请求，也不过滤临时列表。 */
        data object Keep : Edit()

        /** 在全站搜索里清空了搜索框：回到原来源。 */
        data object Exit : Edit()
    }

    sealed class MissRecovery {
        /** 已有上一次全站搜索：先结束它、换回原来源列表，再决定直达或提示。 */
        data object RestoreSource : MissRecovery()

        data object None : MissRecovery()
    }

    fun missRecovery(active: String?): MissRecovery =
        if (active == null) MissRecovery.None else MissRecovery.RestoreSource

    sealed class MissOutcome {
        /** 恢复原来源期间已被新的操作取代。 */
        data object Abandon : MissOutcome()

        data class Direct(val room: Room) : MissOutcome()

        data class Notify(val message: String) : MissOutcome()
    }

    /** [stillCurrent] 是恢复原来源之后重新校验操作代次的结果。 */
    fun missOutcome(
        keyword: String,
        fallback: Room?,
        fallbackBlocked: Boolean,
        failed: Boolean,
        stillCurrent: Boolean,
    ): MissOutcome {
        if (!stillCurrent) return MissOutcome.Abandon
        if (fallback != null && !fallbackBlocked) return MissOutcome.Direct(fallback)
        val message = if (failed) "全站搜索失败，稍后再试" else "全站没有找到「$keyword」"
        return MissOutcome.Notify(message)
    }

    /** 去掉首尾空白、合并中间空白、截断到上限；空白返回 null。 */
    fun normalized(raw: String?): String? = SiteSearchText.normalized(raw)

    /**
     * 正在显示全站搜索时，换了词就再查一次；否则本地有匹配沿用本地，没有才查全站。
     * [localMatches] 是按当前搜索词过滤后的可见房间数（不含仅因 retained 留下的当前房间）。
     */
    fun onSubmit(text: String, localMatches: Int, active: String?): Submit {
        val keyword = normalized(text) ?: return Submit.Ignore
        if (active != null) {
            return if (keyword.lowerRoot() == active.lowerRoot()) Submit.Ignore else Submit.Remote(keyword)
        }
        return if (localMatches > 0) Submit.Local else Submit.Remote(keyword)
    }

    /** 只有回车才发请求。 */
    fun onEdit(text: String, active: String?): Edit {
        if (active == null) return Edit.Filter
        return if (normalized(text) == null) Edit.Exit else Edit.Keep
    }

    /**
     * 搜索框被清空时，在途的全站搜索要取消。首次搜索在途时 active 仍是 null，
     * [onEdit] 走 [Edit.Filter]，这里也不能放过。
     */
    fun cancelsInflight(text: String): Boolean = normalized(text) == null

    /** 只带搜索词与分页，不叠加性别、地区、标签。 */
    fun query(keyword: String): CatalogQuery = CatalogQuery(limit = pageSize, offset = 0, keywords = keyword)

    /**
     * 全站搜索的临时列表不是画像推荐出来的。「猜你喜欢」下按热门记
     * （证据减半、不进每日命中率），其他来源照旧。
     */
    fun dwellSource(loaded: FeedSource, searching: Boolean): FeedSource =
        if (searching && loaded == FeedSource.guess) FeedSource.popular else loaded

    /** 去重、去掉屏蔽的；没有后续页。屏蔽名单按已经小写的用户名比对。 */
    fun page(rooms: List<Room>, blocked: Set<String>): CatalogPage {
        val kept = dedupeRooms(rooms).filter { it.username.lowerRoot() !in blocked }
        return CatalogPage(rooms = kept, total = kept.size, nextOffset = kept.size)
    }
}

/**
 * 全站搜索的操作代次。每次发起新搜索领一个新号；慢请求返回后先核对号，不是当前的就丢弃。
 * Swift 是值类型；这里是类，方法就地改同一实例。
 */
class SearchEpoch {
    var value: Int = 0
        private set

    /** 认领「加载中」的那次搜索；只有它（且仍是当前）能把加载中复位。 */
    var loadingOwner: Int? = null
        private set

    /** 发起新搜索：作废之前所有搜索，返回新号。 */
    fun begin(): Int {
        value += 1
        return value
    }

    /** 作废所有在途搜索。 */
    fun invalidate() {
        value += 1
    }

    fun isCurrent(token: Int): Boolean = token == value

    /** 仍是当前搜索、且任务没被取消，才可以改加载状态并发请求。 */
    fun canStart(token: Int, cancelled: Boolean): Boolean = !cancelled && isCurrent(token)

    fun claimLoading(token: Int) {
        loadingOwner = token
    }

    /** 只在有搜索认领时返回 true。 */
    fun releaseLoading(): Boolean {
        val had = loadingOwner != null
        loadingOwner = null
        return had
    }

    /** 只让仍是当前的认领者复位。过期的旧搜索返回 false，不动认领。 */
    fun releaseLoading(token: Int): Boolean {
        if (loadingOwner != token || !isCurrent(token)) return false
        loadingOwner = null
        return true
    }

    override fun equals(other: Any?): Boolean =
        other is SearchEpoch && value == other.value && loadingOwner == other.loadingOwner

    override fun hashCode(): Int = 31 * value + (loadingOwner ?: 0)
}
