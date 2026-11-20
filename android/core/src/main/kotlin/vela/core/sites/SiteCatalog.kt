package vela.core.sites

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import vela.core.base.model.Room
import vela.core.base.site.CatalogPage
import vela.core.base.site.CatalogQuery

/**
 * 混合目录的各站游标。单站模式不靠它分页（直接用 [CatalogQuery.offset]）。
 */
data class MixedCursor(
    val offsets: Map<SiteID, Int> = emptyMap(),
    val totals: Map<SiteID, Int> = emptyMap(),
    val ended: Set<SiteID> = emptySet(),
) {
    constructor(sites: List<SiteID>) : this(
        offsets = sites.associateWith { 0 },
        totals = sites.associateWith { 0 },
        ended = emptySet(),
    )

    val isFinished: Boolean
        get() = offsets.isNotEmpty() && offsets.keys.all { it in ended }

    fun withPage(site: SiteID, page: CatalogPage): MixedCursor {
        val stillOpen = page.rooms.isNotEmpty() && page.nextOffset < page.total
        return copy(
            offsets = offsets + (site to page.nextOffset),
            totals = totals + (site to page.total),
            ended = if (stillOpen) ended - site else ended + site,
        )
    }
}

data class SiteFetch(
    val page: CatalogPage,
    val cursor: MixedCursor,
    val failures: Map<SiteID, Throwable>,
)

/**
 * 按模式向各站取一页（并发），合并成一个 [CatalogPage]。
 * 各站保持自己的顺序，轮流各取一间交错；按 ref 去重。
 * `total` 是各站 total 之和，`nextOffset` 是各站 offset 之和。
 * 混合模式一站失败另一站成功：返回成功那站，并在 [SiteFetch.failures] 里记下失败站。全失败抛第一站的错误。
 */
object SiteCatalog {
    suspend fun fetch(
        mode: SiteMode,
        query: CatalogQuery,
        cursor: MixedCursor,
        registry: SiteRegistry,
    ): SiteFetch = coroutineScope {
        val sites = mode.sites
        val results = sites.filter { it !in cursor.ended }.map { site ->
            async {
                val siteQuery = queryFor(site, mode, query, cursor)
                val adapter = registry.adapter(site)
                    ?: return@async site to Result.failure(SiteError.UnknownSite(site.wire))
                site to try {
                    Result.success(adapter.fetchDirectory(siteQuery))
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    Result.failure(error)
                }
            }
        }.awaitAll().toMap()

        val failures = LinkedHashMap<SiteID, Throwable>()
        var firstError: Throwable? = null
        for (site in sites) {
            val error = results[site]?.exceptionOrNull() ?: continue
            failures[site] = error
            if (firstError == null) firstError = error
        }
        val successes = sites.filter { results[it]?.isSuccess == true }
        if (successes.isEmpty() && firstError != null && !sites.all { it in cursor.ended }) {
            throw firstError
        }

        var next = cursor
        val lists = ArrayList<List<Room>>()
        for (site in sites) {
            val page = results[site]?.getOrNull() ?: continue
            next = next.withPage(site, page)
            lists.add(page.rooms)
        }
        val merged = interleave(lists)
        val total = sites.sumOf { next.totals[it] ?: 0 }
        val nextOffset = sites.sumOf { next.offsets[it] ?: 0 }
        SiteFetch(
            page = CatalogPage(rooms = merged, total = total, nextOffset = nextOffset),
            cursor = next,
            failures = failures,
        )
    }

    private fun queryFor(site: SiteID, mode: SiteMode, query: CatalogQuery, cursor: MixedCursor): CatalogQuery {
        if (mode != SiteMode.mixed) return query
        return query.copyWith(offset = cursor.offsets[site] ?: 0)
    }

    /** 各站顺序不变，轮流各取一间；同一 ref 只留先出现的。 */
    internal fun interleave(lists: List<List<Room>>): List<Room> {
        val merged = ArrayList<Room>()
        val seen = HashSet<String>()
        var index = 0
        var produced = true
        while (produced) {
            produced = false
            for (list in lists) {
                if (index >= list.size) continue
                produced = true
                val room = list[index]
                if (seen.add(room.identity)) merged.add(room)
            }
            index += 1
        }
        return merged
    }
}

internal fun CatalogQuery.copyWith(offset: Int): CatalogQuery = CatalogQuery(
    gender = gender,
    region = region,
    tag = tag,
    limit = limit,
    offset = offset,
    newCams = newCams,
    keywords = keywords,
)
