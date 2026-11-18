package vela.core.cross

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import vela.core.base.model.FeedSource
import vela.core.base.model.Room
import vela.core.base.model.RoomQuery
import vela.core.base.model.ShowState
import vela.core.base.site.CatalogPage
import vela.core.base.site.FavoriteStatus
import vela.core.curation.FavoriteCache
import vela.core.curation.FavoritePlan
import vela.core.curation.FavoriteResolver
import vela.core.curation.KnownCatalog
import vela.core.feed.FeedStore
import vela.core.ledger.DwellTracker
import vela.core.recommend.Recommender
import vela.core.taste.Taste

class ReviewFixTests {
    private val t0: Instant = Instant.ofEpochSecond(1_800_000_000)

    private fun room(name: String, show: ShowState = ShowState.publicShow, tags: List<String> = emptyList()): Room =
        Room(username = name, viewers = 5, tags = tags, show = show)

    @Test
    fun testAppendedPageDoesNotRefreshEarlierRooms() {
        val catalog = KnownCatalog()
        catalog.record(listOf(room("early")), firstPage = true, at = t0)
        catalog.record(listOf(room("late")), firstPage = false, at = t0.plusSeconds(150))
        val now = t0.plusMillis((FavoriteCache.ttl * 1000).toLong() + 10_000)
        assertEquals(listOf("late"), catalog.usable(now).map { it.room.username }, "第一页已过期，不因追加页被续期")
        val plan = FavoriteResolver.plan(favorites = listOf("early", "late"), catalog = catalog, cache = FavoriteCache(), now = now)
        assertEquals(listOf("early"), plan.pending)
        assertEquals(t0.plusSeconds(150), plan.fromCatalog["late"]?.at)
    }

    @Test
    fun testCatalogStatusesKeepTheirOwnTimeInCache() {
        val catalog = KnownCatalog()
        catalog.record(listOf(room("a")), firstPage = true, at = t0)
        val plan = FavoriteResolver.plan(
            favorites = listOf("a"),
            catalog = catalog,
            cache = FavoriteCache(),
            now = t0.plusSeconds(60),
        )
        val cache = FavoriteCache().storing(stamped = plan.fromCatalog)
        val ttl = FavoriteCache.ttl.toLong()
        assertTrue(cache.fresh("a", t0.plusSeconds(ttl)) != null)
        assertEquals(null, cache.fresh("a", t0.plusSeconds(ttl + 1)), "按房间自己被取回的时间过期")
    }

    @Test
    fun testOlderCatalogStampDoesNotOverwriteNewerCacheEntry() {
        val cache = FavoriteCache().storing(mapOf("a" to FavoriteStatus.Offline), at = t0.plusSeconds(100))
        val stamped = mapOf("a" to FavoritePlan.Stamped(status = FavoriteStatus.NotFound, at = t0))
        assertEquals(FavoriteStatus.Offline, cache.storing(stamped = stamped).status("a"))
    }

    @Test
    fun testFavoritesFeedOnlyShowsCurrentFavoritesAfterBlockAndUnblock() {
        val store = FeedStore(query = RoomQuery(only = setOf("a", "b")))
        store.replace(CatalogPage(rooms = listOf(room("A"), room("B")), total = 2, nextOffset = 2))
        store.setFilters(blocked = setOf("a"), only = setOf("b"))
        assertEquals(listOf("B"), store.rooms.map { it.username })
        store.setFilters(blocked = emptySet(), only = setOf("b"))
        assertEquals(listOf("B"), store.rooms.map { it.username })
        store.setFilters(blocked = emptySet(), only = setOf("a", "b"))
        assertEquals(listOf("A", "B"), store.rooms.map { it.username })
    }

    @Test
    fun testSetBlockedKeepsAllowList() {
        val store = FeedStore(query = RoomQuery(only = setOf("a")))
        store.replace(CatalogPage(rooms = listOf(room("a"), room("z")), total = 2, nextOffset = 2))
        store.setBlocked(emptySet())
        assertEquals(listOf("a"), store.rooms.map { it.username })
    }

    @Test
    fun testRevealClearsSearchThatHidesTarget() {
        val store = FeedStore(query = RoomQuery(text = "blonde"))
        store.replace(
            CatalogPage(
                rooms = listOf(room("x", tags = listOf("blonde")), room("fav", tags = listOf("tall"))),
                total = 2,
                nextOffset = 2,
            ),
        )
        assertEquals(listOf("x"), store.rooms.map { it.username })
        assertTrue(store.reveal(room("fav", tags = listOf("tall"))))
        assertEquals("fav", store.current?.username)
        assertEquals("", store.query.text)
    }

    @Test
    fun testRevealInsertsMissingRoomAndKeepsSearchWhenItMatches() {
        val store = FeedStore(query = RoomQuery(text = "blonde"))
        store.replace(CatalogPage(rooms = listOf(room("x", tags = listOf("blonde"))), total = 1, nextOffset = 1))
        assertTrue(store.reveal(room("new", tags = listOf("blonde"))))
        assertEquals("new", store.current?.username)
        assertEquals("blonde", store.query.text, "目标本就匹配时不动搜索条件")
    }

    @Test
    fun testRestoreUndoesVetoAndLearnedDislike() {
        val now = t0
        var taste = Taste().boosting("t:a", now)
        taste = taste.vetoing("t:a", now)
        assertTrue(taste.isVetoed("t:a"))
        assertTrue(taste.summary(now).disliked.any { it.key == "t:a" })
        val restored = taste.restoring("t:a", now)
        assertFalse(restored.isVetoed("t:a"))
        assertFalse(restored.summary(now).disliked.any { it.key == "t:a" })
        assertTrue(restored.mean("t:a", now) > 0.5, "之前的命中证据保留")
        var learned = Taste()
        repeat(15) {
            learned = learned.recording(room = room("r", tags = listOf("z")), watched = 1.0, source = FeedSource.guess, now = now)
        }
        assertTrue(learned.summary(now).disliked.any { it.key == "t:z" })
        assertFalse(learned.restoring("t:z", now).summary(now).disliked.any { it.key == "t:z" })
        assertEquals(Taste(), Taste().restoring("t:none", now))
    }

    @Test
    fun testTasteChangeReordersOnlyAfterCurrent() {
        val rooms = (0 until 8).map { index ->
            room("r$index", tags = listOf(if (index % 2 == 0) "even" else "odd"))
        }
        val store = FeedStore()
        store.replace(CatalogPage(rooms = rooms, total = 8, nextOffset = 8))
        store.focus("r2")
        val before = store.catalog.map { it.username }
        val taste = Taste().vetoing("t:odd", t0)
        store.reorderAfterCurrent { tail ->
            Recommender.arrange(tail, source = FeedSource.guess, taste = taste, now = t0, seed = 1u)
        }
        val after = store.catalog.map { it.username }
        assertEquals(before.take(3), after.take(3), "当前及之前不动")
        assertEquals("r2", store.currentUsername)
        assertEquals(before.toSet(), after.toSet(), "排序不删房间")
        store.setFilters(blocked = emptySet(), only = null, hidden = taste.vetoedKeys)
        assertFalse(
            store.rooms.drop(3).any { it.username.drop(1).toInt() % 2 == 1 },
            "不想看的标签在可见列表里被过滤",
        )
    }

    @Test
    fun testSourceChangeSettlesDwellEvenWhenFirstRoomHasSameName() {
        assertFalse(DwellTracker.shouldSettle(before = "a", after = "a", sourceChanged = false))
        assertTrue(DwellTracker.shouldSettle(before = "a", after = "a", sourceChanged = true))
        assertTrue(DwellTracker.shouldSettle(before = "a", after = "b", sourceChanged = false))
        assertFalse(DwellTracker.shouldSettle(before = null, after = null, sourceChanged = true))
        val tracker = DwellTracker()
        tracker.switchTo(room("a"), t0)
        tracker.setActive(true, t0)
        val settled = tracker.switchTo(room("a"), t0.plusSeconds(40))
        assertEquals(40.0, settled?.dwell ?: 0.0, 0.001)
        assertEquals(0.0, tracker.dwell(t0.plusSeconds(40)), 0.001)
    }
}
