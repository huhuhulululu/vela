package vela.core.hls

import vela.core.base.model.trimSwiftWhitespace

/**
 * 自动跳过：沿用户最后一次手动划动的方向，连续跳到上限就停下。
 * 没跳成（列表尽头）不占次数。
 */
class SkipTracker {
    var direction: Int = 1
        private set
    var count: Int = 0
        private set

    fun noteManual(delta: Int) {
        if (delta != 0) direction = if (delta > 0) 1 else -1
        count = 0
    }

    /** 登记一次自动跳过。已达上限返回 false。 */
    fun registerAuto(): Boolean {
        if (count >= limit) return false
        count += 1
        return true
    }

    /** 播放成功或用户重试：计数清零，方向保留。 */
    fun reset() {
        count = 0
    }

    /**
     * 已达上限时不调用 [move]。`move` 返回 false 表示无处可跳，不占计数。
     */
    fun attemptAuto(move: () -> Boolean): AutoOutcome {
        if (count >= limit) return AutoOutcome.LimitReached
        if (!move()) return AutoOutcome.EndOfList
        count += 1
        return AutoOutcome.Moved
    }

    sealed class AutoOutcome {
        data object Moved : AutoOutcome()
        data object EndOfList : AutoOutcome()
        data object LimitReached : AutoOutcome()

        /** 停下时的文案。跳走了返回 null。尽头保留原因；只有真到上限才说连续几个房间。 */
        fun notice(keeping: String?): String? = when (this) {
            Moved -> null
            EndOfList -> {
                val kept = (keeping ?: "").trimSwiftWhitespace()
                if (kept.isEmpty()) "已到列表尽头" else "$kept，已到列表尽头"
            }
            LimitReached -> "连续几个房间都无法播放"
        }
    }

    companion object {
        const val limit: Int = 3
        const val noticeSeconds: Double = 1.6
    }
}
