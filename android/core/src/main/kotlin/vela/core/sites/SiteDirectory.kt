package vela.core.sites

import vela.core.base.model.Gender
import vela.core.base.model.Region
import vela.core.base.site.CatalogPage
import vela.core.base.site.CatalogQuery
import vela.core.base.site.SiteSearchText
import vela.core.feed.CatalogAPI
import vela.core.feed.CatalogWindow
import vela.core.feed.PageMerge

/**
 * 舞台取目录的入口：`cb` 原样走 [CatalogAPI]；`c4` 只问 Cam4；
 * 混合模式按查询维度记住「游标之和 → 各站游标」，下一页按它接着取。记不住的偏移从头取。
 */
class SiteDirectory(
    val mode: SiteMode,
    private val registry: SiteRegistry,
    private val chaturbate: CatalogAPI,
) {
    private data class QueryScope(
        val gender: Gender?,
        val region: Region?,
        val tag: String?,
        val limit: Int,
        val newCams: Boolean,
        val keywords: String?,
    ) {
        constructor(query: CatalogQuery) : this(
            gender = query.gender,
            region = query.region,
            tag = CatalogQuery.normalizedHashtag(query.tag),
            limit = query.limit.coerceIn(1, CatalogQuery.maxPageSize),
            newCams = query.newCams,
            keywords = SiteSearchText.normalized(query.keywords),
        )
    }

    private val lock = Any()
    private val cursors = HashMap<QueryScope, HashMap<Int, MixedCursor>>()
    private val scopeOrder = ArrayList<QueryScope>()
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
            val nextOffset = last.nextOffset
            last = fetch(query.copyWith(nextOffset))
            if (last.rooms.isEmpty()) {
                if (last.nextOffset > nextOffset) merged = PageMerge.appended(merged, last)
                break
            }
            merged = PageMerge.appended(merged, last)
            fetched += 1
        }
        return merged
    }

    private suspend fun fetchMixed(query: CatalogQuery): CatalogPage {
        val sites = mode.sites
        val scope = QueryScope(query)
        val cursor = synchronized(lock) {
            if (query.offset <= 0) cursors[scope] = HashMap()
            touchScope(scope)
            cursors[scope]?.get(query.offset) ?: MixedCursor(sites)
        }
        val result = SiteCatalog.fetch(mode, query, cursor, registry)
        synchronized(lock) {
            failed = sites.filter { result.failures[it] != null }
            val bucket = cursors.getOrPut(scope) { HashMap() }
            if (bucket.size >= 64) bucket.clear()
            bucket[result.page.nextOffset] = result.cursor
            touchScope(scope)
            while (scopeOrder.size > 16) {
                val oldest = scopeOrder.removeAt(0)
                cursors.remove(oldest)
            }
        }
        return result.page
    }

    private fun touchScope(scope: QueryScope) {
        scopeOrder.remove(scope)
        scopeOrder.add(scope)
    }
}
