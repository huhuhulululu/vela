package vela.core.sites

import vela.core.base.site.CatalogPage
import vela.core.base.site.CatalogQuery
import vela.core.feed.CatalogAPI
import vela.core.feed.CatalogWindow
import vela.core.feed.PageMerge

/**
 * 舞台取目录的入口：`cb` 原样走 [CatalogAPI]；`c4` 只问 Cam4；
 * 混合模式记住「游标之和 → 各站游标」，下一页按它接着取。记不住的偏移从头取。
 */
class SiteDirectory(
    val mode: SiteMode,
    private val registry: SiteRegistry,
    private val chaturbate: CatalogAPI,
) {
    private val lock = Any()
    private val cursors = HashMap<Int, MixedCursor>()
    private var failed: List<SiteID> = emptyList()

    /** 混合模式最近一次取页时失败的站。 */
    val lastFailedSites: List<SiteID>
        get() = synchronized(lock) { failed.toList() }

    suspend fun fetch(query: CatalogQuery): CatalogPage = when (mode) {
        SiteMode.chaturbate -> chaturbate.fetch(query)
        SiteMode.cam4 -> {
            val adapter = registry.adapter(SiteID.cam4) ?: throw SiteError.UnknownSite(SiteID.cam4.wire)
            adapter.fetchDirectory(query)
        }
        SiteMode.mixed -> fetchMixed(query)
    }

    /** 与 [CatalogAPI.fetchPages] 同一循环：没取满、上一页是满页、游标没到底就接着取。 */
    suspend fun fetchPages(query: CatalogQuery, pages: Int): CatalogPage {
        if (mode == SiteMode.chaturbate) return chaturbate.fetchPages(query, pages)
        val size = query.limit.coerceAtLeast(1).coerceAtMost(CatalogQuery.maxPageSize)
        var last = fetch(query)
        var merged = last
        var fetched = 1
        while (
            CatalogWindow.shouldContinue(
                fetched = fetched,
                pages = pages,
                lastCount = last.rooms.size,
                pageSize = size,
                nextOffset = last.nextOffset,
                total = last.total,
            )
        ) {
            last = fetch(query.copyWith(last.nextOffset))
            if (last.rooms.isEmpty()) break
            merged = PageMerge.appended(merged, last)
            fetched += 1
        }
        return merged
    }

    private suspend fun fetchMixed(query: CatalogQuery): CatalogPage {
        val sites = mode.sites
        val cursor = synchronized(lock) {
            if (query.offset <= 0) cursors.clear()
            cursors[query.offset] ?: MixedCursor(sites)
        }
        val result = SiteCatalog.fetch(mode, query, cursor, registry)
        synchronized(lock) {
            failed = sites.filter { result.failures[it] != null }
            if (cursors.size >= 64) cursors.clear()
            cursors[result.page.nextOffset] = result.cursor
        }
        return result.page
    }
}
