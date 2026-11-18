package vela.core.curation

import java.time.Instant
import vela.core.base.model.FeedSource
import vela.core.base.model.Room
import vela.core.base.model.lowerRoot
import vela.core.base.site.CatalogPage

/**
 * 目录翻页结果落地：副作用都在代次检查通过之后才写。
 * 被作废的旧响应只丢弃，不留痕迹。
 */
object LoadCommit {
    /**
     * [token] 与 [current] 不等（期间重载或换来源）返回 false 且什么都不改。
     * [raw] 是站点原始页（目录快照按它记），[arranged] 是排序后的页（理由按它算）。
     */
    fun apply(
        raw: CatalogPage,
        arranged: CatalogPage,
        offset: Int,
        source: FeedSource,
        token: Int,
        current: Int,
        replacing: Boolean,
        now: Instant,
        catalog: KnownCatalog,
        reasons: MutableMap<String, String>,
        reason: (Room) -> String,
    ): Boolean {
        if (token != current) return false
        if (source != FeedSource.favorites) {
            catalog.record(raw.rooms, firstPage = offset == 0, at = now)
        }
        if (replacing) reasons.clear()
        if (source == FeedSource.guess) {
            for (room in arranged.rooms) {
                reasons[room.username.lowerRoot()] = reason(room)
            }
        }
        return true
    }
}
