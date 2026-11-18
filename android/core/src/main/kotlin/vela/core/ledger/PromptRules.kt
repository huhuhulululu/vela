package vela.core.ledger

import vela.core.base.model.FeedSource
import vela.core.base.model.Room
import vela.core.base.model.RoomQuery
import vela.core.base.model.ShowState
import vela.core.base.model.lowerRoot
import vela.core.base.model.trimSwiftWhitespace

/** 常看收藏提示何时算「问过」、何时可以显示。 */
enum class SuggestEnd {
    /** 点了「收藏」。 */
    accepted,

    /** 点了关闭。 */
    closed,

    /** 超时自己消失。 */
    timedOut,

    /** 换房或被别的横幅替换而撤下。 */
    withdrawn,
}

object SuggestRules {
    /**
     * 只有用户点了「收藏」或明确关闭才记为已提示（`WatchLedger.markingSuggested`）；
     * 超时消失、换房撤下都不算，这位主播以后还会被问。
     */
    fun countsAsAsked(end: SuggestEnd): Boolean = end == SuggestEnd.accepted || end == SuggestEnd.closed

    /**
     * 能不能显示并开始计时：点击穿透期间（点击会落到后面的窗口，按钮点不到）不显示，等指针回到窗口；
     * 本次停留里已经超时消失过的也不再重复弹。
     */
    fun canPresent(pointerCanClick: Boolean, snoozed: Boolean): Boolean = pointerCanClick && !snoozed
}

/** 列表为空时的说明：按原因区分，用户知道该去哪里处理。 */
object EmptyListCopy {
    fun message(
        catalogError: String?,
        query: RoomQuery,
        catalog: List<Room>,
        source: FeedSource,
        favoriteCount: Int,
    ): String {
        if (catalogError != null) return catalogError
        if (catalog.isEmpty()) {
            if (source == FeedSource.favorites) {
                return if (favoriteCount == 0) "还没有收藏，在房间里按 ⌘D 收藏" else "收藏的人现在都不在线"
            }
            return "目录里现在没有房间，可以稍后重试"
        }
        val text = query.text.trimSwiftWhitespace()
        if (text.isNotEmpty()) {
            val withoutText = query.copy(text = "")
            if (catalog.any { withoutText.matches(it) }) return "没有匹配「$text」的房间。回车搜索全站"
        }
        val unblocked = catalog.filter { it.username.lowerRoot() !in query.blocked }
        if (unblocked.isEmpty()) return "目录里的房间都在屏蔽名单里"
        val allowed = unblocked.filter { room -> query.only?.contains(room.username.lowerRoot()) ?: true }
        if (allowed.isEmpty()) return "收藏的人现在都不在线"
        if (query.hidden.isNotEmpty() && allowed.all { RoomQuery.isHidden(it, query.hidden) }) {
            return "目录里的房间都被「我的口味」里不想看的标签过滤掉了"
        }
        if (query.publicOnly && allowed.all { it.show != ShowState.publicShow }) {
            return "目录里没有公开的房间，可以在筛选里关掉「仅公开」"
        }
        return "目录里暂时没有符合条件的房间"
    }
}
