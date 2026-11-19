package vela.core.lifecycle

import java.time.Duration
import java.time.Instant

/**
 * 站点质询的连续次数闸门：每次撞上质询登记一次，判定这次还要不要弹确认页。
 * 连续次数超过上限，或距上一次质询太近（刚点过「已完成」又被拦），建议不再弹页。
 * 成功取到结果时调用 [reset]。时间由参数传入。
 *
 * Swift 是值类型。这里是类，方法就地修改。
 */
class ChallengeGate {
    var consecutive: Int = 0
        private set

    var lastAt: Instant? = null
        private set

    sealed class Decision {
        /** 弹确认页。 */
        data object Present : Decision()

        /** 不再弹页：提示并跳过（或停在失败态）。 */
        data object Suppress : Decision()
    }

    /**
     * 登记一次质询并给出建议。被建议不弹页的那次也计入连续次数。
     * 距上次超过 [resetAfter] 秒则连续计数从头算，用的是更新前的 [lastAt]。
     */
    fun register(now: Instant): Decision {
        val previous = lastAt
        if (previous != null && secondsSince(previous, now) >= resetAfter) consecutive = 0
        consecutive += 1
        lastAt = now
        if (consecutive > limit) return Decision.Suppress
        if (consecutive > 1 && previous != null && secondsSince(previous, now) < repeatWindow) {
            return Decision.Suppress
        }
        return Decision.Present
    }

    /** 解析或取目录成功（不再被质询）：清零。 */
    fun reset() {
        consecutive = 0
        lastAt = null
    }

    companion object {
        /** 连续质询最多弹几次确认页；第 limit + 1 次起不再弹。 */
        const val limit: Int = 2

        /** 距上一次质询不到这么久又撞上，视为通关没生效，不再弹页。 */
        const val repeatWindow: Double = 20.0

        /** 距上一次质询超过这么久，连续计数从头算。 */
        const val resetAfter: Double = 600.0
    }
}

/** 确认页完成后要接着做的事。 */
sealed class ChallengeResume {
    data object Reload : ChallengeResume()
    data object Favorites : ChallengeResume()

    /** 重跑被质询打断的全站搜索（搜索词）。 */
    data class SiteSearch(val query: String) : ChallengeResume()

    /** 取消全站搜索时清掉属于它的恢复意图（其余意图保留）。 */
    fun droppingSiteSearch(): ChallengeResume? = when (this) {
        is SiteSearch -> null
        else -> this
    }

    /**
     * 多个发起者先后请求恢复时取「需要做得最多」的：重载优先；
     * 其次是重跑搜索；只补查收藏最轻。同为搜索时取较新的词。
     */
    fun merging(new: ChallengeResume): ChallengeResume = when {
        this is Reload || new is Reload -> Reload
        new is SiteSearch -> new
        this is SiteSearch && new is Favorites -> this
        else -> Favorites
    }
}

/** Swift `Date.timeIntervalSince`：秒，亚秒来自纳秒。负间隔也成立。 */
private fun secondsSince(earlier: Instant, later: Instant): Double {
    val delta = Duration.between(earlier, later)
    return delta.seconds.toDouble() + delta.nano.toDouble() / 1_000_000_000.0
}
