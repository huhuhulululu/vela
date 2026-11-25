package vela.core.ledger

import vela.core.base.model.FeedSource
import vela.core.base.model.Gender
import vela.core.base.model.Room
import vela.core.base.model.RoomQuery
import vela.core.base.model.ShowState
import vela.core.base.model.TasteKeys
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PromptRulesTests {
    @Test
    fun testOnlyAcceptOrCloseCountsAsAsked() {
        assertTrue(SuggestRules.countsAsAsked(SuggestEnd.accepted))
        assertTrue(SuggestRules.countsAsAsked(SuggestEnd.closed))
        assertFalse(SuggestRules.countsAsAsked(SuggestEnd.timedOut), "超时消失不算问过")
        assertFalse(SuggestRules.countsAsAsked(SuggestEnd.withdrawn), "换房或被替换也不算")
    }

    @Test
    fun testPresentGate() {
        assertTrue(SuggestRules.canPresent(pointerCanClick = true, snoozed = false))
        assertFalse(SuggestRules.canPresent(pointerCanClick = false, snoozed = false))
        assertFalse(SuggestRules.canPresent(pointerCanClick = true, snoozed = true))
    }

    @Test
    fun testTimeoutLeavesLedgerUnmarked() {
        val t0 = 1_000_000.0
        var ledger = WatchLedger()
        ledger = ledger.recording(username = "Amy", seconds = 700.0, start = t0, now = t0 + 700)
        assertTrue(
            ledger.shouldSuggest(
                username = "Amy",
                favorited = false,
                blocked = false,
                liveSeconds = 0.0,
                liveStart = null,
            ),
        )
        if (SuggestRules.countsAsAsked(SuggestEnd.timedOut)) {
            ledger = ledger.markingSuggested("Amy", now = t0 + 710)
        }
        assertTrue(
            ledger.shouldSuggest(
                username = "Amy",
                favorited = false,
                blocked = false,
                liveSeconds = 0.0,
                liveStart = null,
            ),
        )
        if (SuggestRules.countsAsAsked(SuggestEnd.closed)) {
            ledger = ledger.markingSuggested("Amy", now = t0 + 720)
        }
        assertFalse(
            ledger.shouldSuggest(
                username = "Amy",
                favorited = false,
                blocked = false,
                liveSeconds = 0.0,
                liveStart = null,
            ),
        )
    }

    private fun room(name: String, tags: List<String> = listOf("x"), show: ShowState = ShowState.publicShow): Room =
        Room(username = name, viewers = 5, gender = Gender.female, tags = tags, show = show)

    private fun message(
        query: RoomQuery,
        catalog: List<Room>,
        source: FeedSource = FeedSource.guess,
        favorites: Int = 0,
        error: String? = null,
    ): String = EmptyListCopy.message(
        catalogError = error,
        query = query,
        catalog = catalog,
        source = source,
        favoriteCount = favorites,
    )

    @Test
    fun testEmptyMessagesByReason() {
        val rooms = listOf(room("a"), room("b"))
        assertEquals("网络不通，稍后再试", message(RoomQuery(), rooms, error = "网络不通，稍后再试"))
        assertEquals("目录里的房间都在屏蔽名单里", message(RoomQuery(blocked = setOf("a", "b")), rooms))
        assertEquals(
            "目录里的房间都被「我的口味」里不想看的标签过滤掉了",
            message(RoomQuery(hidden = setOf(TasteKeys.tagKey("x"))), rooms),
        )
        assertEquals("没有匹配「zzz」的房间。回车搜索全站", message(RoomQuery(text = "zzz"), rooms))
        assertEquals(
            "目录里没有公开的房间，可以在筛选里关掉「仅公开」",
            message(RoomQuery(), listOf(room("a", show = ShowState.privateShow))),
        )
        assertEquals("目录里现在没有房间，可以稍后重试", message(RoomQuery(), emptyList()))
    }

    @Test
    fun testEmptyFavoritesMessages() {
        assertEquals(
            "还没有收藏，在房间里按 ⌘D 收藏",
            message(RoomQuery(only = emptySet()), emptyList(), source = FeedSource.favorites, favorites = 0),
        )
        assertEquals(
            "收藏的人现在都不在线",
            message(RoomQuery(only = setOf("q")), emptyList(), source = FeedSource.favorites, favorites = 1),
        )
        assertEquals(
            "收藏的人现在都不在线",
            message(RoomQuery(only = setOf("q")), listOf(room("a")), source = FeedSource.favorites, favorites = 1),
        )
    }

    @Test
    fun testMixedCauseFallsBackToGenericMessage() {
        val rooms = listOf(room("a"), room("b", tags = listOf("y")))
        val query = RoomQuery(blocked = setOf("a"), hidden = setOf(TasteKeys.tagKey("y")))
        assertEquals("目录里的房间都被「我的口味」里不想看的标签过滤掉了", message(query, rooms))
        assertEquals(
            "目录里没有公开的房间，可以在筛选里关掉「仅公开」",
            message(
                RoomQuery(publicOnly = true, blocked = setOf("a")),
                listOf(room("a"), room("b", show = ShowState.privateShow)),
            ),
        )
    }

    @Test
    fun testEmptyMessageCam4UnsupportedFilterRespectsFavoritesAndSearch() {
        assertEquals(
            "Cam4 不支持按地区、标签或新开播筛选",
            EmptyListCopy.message(
                catalogError = null,
                query = RoomQuery(),
                catalog = emptyList(),
                source = FeedSource.guess,
                favoriteCount = 0,
                siteMode = vela.core.sites.SiteMode.cam4,
                hasUnsupportedSiteFilter = true,
                searching = false,
            ),
        )
        assertEquals(
            "Cam4 不支持按地区、标签或新开播筛选",
            EmptyListCopy.message(
                catalogError = null,
                query = RoomQuery(),
                catalog = emptyList(),
                source = FeedSource.fresh,
                favoriteCount = 0,
                siteMode = vela.core.sites.SiteMode.cam4,
                hasUnsupportedSiteFilter = false,
                searching = false,
            ),
        )
        assertEquals(
            EmptyListCopy.noFavoritesYet,
            EmptyListCopy.message(
                catalogError = null,
                query = RoomQuery(),
                catalog = emptyList(),
                source = FeedSource.favorites,
                favoriteCount = 0,
                siteMode = vela.core.sites.SiteMode.cam4,
                hasUnsupportedSiteFilter = true,
                searching = false,
            ),
        )
        assertEquals(
            "目录里现在没有房间，可以稍后重试",
            EmptyListCopy.message(
                catalogError = null,
                query = RoomQuery(),
                catalog = emptyList(),
                source = FeedSource.fresh,
                favoriteCount = 0,
                siteMode = vela.core.sites.SiteMode.cam4,
                hasUnsupportedSiteFilter = true,
                searching = true,
            ),
        )
    }
}
