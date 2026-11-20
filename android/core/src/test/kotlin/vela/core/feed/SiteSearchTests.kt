package vela.core.feed

import java.net.URI
import java.net.URLDecoder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import vela.core.base.model.FeedSource
import vela.core.base.model.Room
import vela.core.base.site.CatalogPage
import vela.core.base.site.CatalogQuery

/** 全站搜索：请求构造、回车与打字的触发规则、结果整理、停留归属。 */
class SiteSearchTests {
    private fun values(url: String): Map<String, String> {
        val raw = URI(url).rawQuery ?: return emptyMap()
        if (raw.isEmpty()) return emptyMap()
        return raw.split('&').associate { part ->
            val idx = part.indexOf('=')
            val key = URLDecoder.decode(if (idx >= 0) part.substring(0, idx) else part, Charsets.UTF_8)
            val value = URLDecoder.decode(if (idx >= 0) part.substring(idx + 1) else "", Charsets.UTF_8)
            key to value
        }
    }

    @Test
    fun testQueryCarriesKeywordsAndOnePageOnly() {
        val url = SiteSearch.query("big  smile").url()
        assertTrue(url.toString().startsWith("https://chaturbate.com/api/ts/roomlist/room-list/?"))
        assertEquals(mapOf("limit" to "90", "offset" to "0", "keywords" to "big smile"), values(url.toString()))
    }

    @Test
    fun testBlankKeywordsAreNotSent() {
        assertNull(values(CatalogQuery(keywords = "   ").url().toString())["keywords"])
        assertNull(values(CatalogQuery().url().toString())["keywords"])
    }

    @Test
    fun testNormalizationTrimsCollapsesAndClips() {
        assertEquals("a b", SiteSearch.normalized("  a \n b  "))
        assertNull(SiteSearch.normalized(" \t "))
        assertNull(SiteSearch.normalized(null))
        assertEquals(SiteSearch.maxLength, SiteSearch.normalized("x".repeat(100))?.length)
    }

    @Test
    fun testSubmitUsesLocalWhenSomethingMatches() {
        assertEquals(SiteSearch.Submit.Local, SiteSearch.onSubmit(text = "jazz", localMatches = 3, active = null))
    }

    @Test
    fun testSubmitQueriesSiteWhenNothingMatchesLocally() {
        assertEquals(SiteSearch.Submit.Remote("jazz"), SiteSearch.onSubmit(text = " jazz ", localMatches = 0, active = null))
    }

    @Test
    fun testSubmitIgnoresBlankAndSameActiveKeyword() {
        assertEquals(SiteSearch.Submit.Ignore, SiteSearch.onSubmit(text = "  ", localMatches = 0, active = null))
        assertEquals(SiteSearch.Submit.Ignore, SiteSearch.onSubmit(text = "Jazz", localMatches = 0, active = "jazz"))
    }

    @Test
    fun testSubmitWithNewKeywordDuringSearchQueriesAgain() {
        assertEquals(SiteSearch.Submit.Remote("piano"), SiteSearch.onSubmit(text = "piano", localMatches = 5, active = "jazz"))
    }

    @Test
    fun testTypingNeverQueries() {
        assertEquals(SiteSearch.Edit.Filter, SiteSearch.onEdit(text = "ja", active = null))
        assertEquals(SiteSearch.Edit.Filter, SiteSearch.onEdit(text = "", active = null))
        assertEquals(SiteSearch.Edit.Keep, SiteSearch.onEdit(text = "pia", active = "jazz"))
    }

    @Test
    fun testClearingDuringSearchExits() {
        assertEquals(SiteSearch.Edit.Exit, SiteSearch.onEdit(text = "  ", active = "jazz"))
    }

    @Test
    fun testPageDedupesDropsBlockedAndHasNoNextPage() {
        val rooms = listOf(Room(username = "a"), Room(username = "B"), Room(username = "A"), Room(username = "c"))
        val page = SiteSearch.page(rooms, blocked = setOf("b"))
        assertEquals(listOf("a", "c"), page.rooms.map { it.username })
        assertEquals(2, page.total)
        assertEquals(2, page.nextOffset)
        val store = FeedStore()
        store.replace(page)
        assertFalse(store.shouldLoadMore())
    }

    @Test
    fun testDwellDuringSearchIsNotCountedAsRecommendation() {
        assertEquals(FeedSource.popular, SiteSearch.dwellSource(loaded = FeedSource.guess, searching = true))
        assertEquals(FeedSource.guess, SiteSearch.dwellSource(loaded = FeedSource.guess, searching = false))
        assertEquals(FeedSource.fresh, SiteSearch.dwellSource(loaded = FeedSource.fresh, searching = true))
    }
}
