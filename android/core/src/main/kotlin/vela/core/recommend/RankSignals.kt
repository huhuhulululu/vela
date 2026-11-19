package vela.core.recommend

import vela.core.ledger.WatchLedger
import vela.core.taste.TagRarity

/** 排序的附加信号。占位类型，字段与 Swift `RankSignals` 一致。 */
data class RankSignals(
    val rarity: TagRarity? = null,
    val ledger: WatchLedger? = null,
    val blocked: Set<String> = emptySet(),
) {
    companion object {
        val none: RankSignals = RankSignals()
    }
}
