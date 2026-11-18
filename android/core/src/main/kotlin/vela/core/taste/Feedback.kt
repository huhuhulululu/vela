package vela.core.taste

import kotlin.math.ln
import kotlin.math.min
import vela.core.base.model.Prefs

/**
 * 一次反馈折算成的命中 / 落空证据量（Beta 后验的 α、β 增量，未乘来源比例与标签稀有度）。
 * 与 Swift `Feedback` 同形。`grade` 用自然对数，和 Swift `log` 一样。
 */
data class Feedback(
    val hit: Double,
    val miss: Double,
) {
    companion object {
        val none: Feedback = Feedback(hit = 0.0, miss = 0.0)

        /** 3 秒内划走：大多是快速浏览，只算弱落空。 */
        const val skipMiss: Double = 0.3

        /** 停留满 20 秒的命中权重；之后按停留时长的对数增长，每 3 倍时长 +0.5。 */
        const val baseHit: Double = 1.0

        internal const val hitPerTripling: Double = 0.5

        /** 长停留的命中上限（180 秒及以上）。 */
        const val maxHit: Double = 2.0

        /** 收藏相当于多少次命中，高于任何停留。 */
        const val followHit: Double = 3.0

        /**
         * 由有效停留秒数得到证据：
         * <3 秒弱落空 0.3；3 到 20 秒不计；20 秒 1、60 秒 1.5、180 秒及以上 2（对数连续，封顶）。
         */
        fun grade(seconds: Double): Feedback {
            if (!seconds.isFinite()) return none
            if (seconds < Prefs.skipBelow) return Feedback(hit = 0.0, miss = skipMiss)
            if (seconds < Prefs.likeAfter) return none
            val triplings = ln(seconds / Prefs.likeAfter) / ln(3.0)
            return Feedback(hit = min(maxHit, baseHit + hitPerTripling * triplings), miss = 0.0)
        }

        /**
         * 每日命中率的口径：≥20 秒记命中，<3 秒记落空，之间不进统计（null）。
         * 权重分级只改变学习强度，不改变「这次算不算对味」的计数。
         */
        fun dailyOutcome(seconds: Double): Boolean? {
            if (seconds >= Prefs.likeAfter) return true
            if (seconds < Prefs.skipBelow) return false
            return null
        }
    }
}
