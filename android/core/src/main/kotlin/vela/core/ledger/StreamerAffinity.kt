package vela.core.ledger

import kotlin.math.ln1p
import vela.core.base.model.CurationKeys

/**
 * 主播亲和度：按观看账本里该主播的累计观看时长与有效次数算一个有上限的加分（排序用）。
 * 时长部分对数饱和（约 60 分钟到顶），次数部分线性饱和（5 次到顶），按 7:3 混合后乘上限 [cap]。
 * 上限低于收藏加分（`Recommender.favoriteBoost`），收藏的主播仍排更前；屏蔽的、没有记录的为 0。
 */
object StreamerAffinity {
    const val cap: Double = 0.5
    internal const val saturationSeconds: Double = 3_600.0
    internal const val saturationVisits: Int = 5
    internal const val timeShare: Double = 0.7

    /** [blocked] 为 `Curation.blockedSet`（`Curation.key` 规范化的用户名）。 */
    fun score(ledger: WatchLedger, username: String, blocked: Set<String> = emptySet()): Double {
        val name = WatchLedger.key(username)
        if (name.isEmpty() || blocked.contains(CurationKeys.key(username))) return 0.0
        val entry = ledger.entries[name] ?: return 0.0
        val minutes = maxOf(0.0, entry.totalSeconds) / 60.0
        val time = minOf(1.0, ln1p(minutes) / ln1p(saturationSeconds / 60.0))
        val visits = minOf(1.0, maxOf(0, entry.visitCount).toDouble() / saturationVisits.toDouble())
        return cap * (timeShare * time + (1.0 - timeShare) * visits)
    }
}
