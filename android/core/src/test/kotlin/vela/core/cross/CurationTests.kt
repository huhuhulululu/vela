package vela.core.cross

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import vela.core.base.model.FeedSource
import vela.core.base.model.Gender
import vela.core.base.model.Room
import vela.core.base.model.RoomQuery
import vela.core.base.model.ShowState
import vela.core.base.site.CatalogPage
import vela.core.feed.FeedStore
import vela.core.recommend.Recommender
import vela.core.taste.Taste

/**
 * Swift `CurationTests` 里依赖 FeedStore / Recommender 的四条。
 * 其余十一条已在 `vela.core.curation.CurationTests`，类名后缀相同，parity 按方法名对上。
 */
class CurationTests {
    private val now: Instant = Instant.ofEpochSecond(1_800_000_000)

    private fun room(name: String, viewers: Int = 100, show: ShowState = ShowState.publicShow): Room =
        Room(username = name, viewers = viewers, gender = Gender.female, show = show)

    private fun names(rooms: List<Room>): List<String> = rooms.map { it.username }

    @Test
    fun testBlockedRoomsAreHiddenFromEverySourceAndFutureAppends() {
        val store = FeedStore(query = RoomQuery(blocked = setOf("bad")))
        store.replace(CatalogPage(rooms = listOf(room("a"), room("Bad"), room("c")), total = 3, nextOffset = 3))
        assertEquals(listOf("a", "c"), names(store.rooms))
        store.append(CatalogPage(rooms = listOf(room("BAD"), room("d")), total = 5, nextOffset = 5))
        assertEquals(listOf("a", "c", "d"), names(store.rooms))
        store.setBlocked(emptySet())
        assertEquals(listOf("a", "Bad", "c", "d"), names(store.rooms))
    }

    @Test
    fun testBlockingCurrentMovesToNextThenPrevious() {
        val store = FeedStore(query = RoomQuery())
        store.replace(CatalogPage(rooms = listOf(room("a"), room("b"), room("c")), total = 3, nextOffset = 3))
        store.focus("b")
        store.setBlocked(setOf("b"))
        assertEquals("c", store.currentUsername)
        store.setBlocked(setOf("b", "c"))
        assertEquals("a", store.currentUsername)
        store.setBlocked(setOf("a", "b", "c"))
        assertNull(store.currentUsername)
        assertNull(store.current)
    }

    @Test
    fun testBlockingOtherRoomKeepsCurrent() {
        val store = FeedStore(query = RoomQuery())
        store.replace(CatalogPage(rooms = listOf(room("a"), room("b"), room("c")), total = 3, nextOffset = 3))
        store.focus("b")
        store.setBlocked(setOf("c"))
        assertEquals("b", store.currentUsername)
    }

    @Test
    fun testFavoriteOutranksHotterRoomsAndSurvivesSeenPenalty() {
        val hot = room("hot", viewers = 20_000)
        val fav = room("Fav", viewers = 10)
        val favorites = setOf("fav")
        val seen = Taste().marking(seen = "fav", now = now.minusSeconds(60)).marking(seen = "hot", now = now.minusSeconds(60))
        for (taste in listOf(Taste(), seen)) {
            val wins = (1..100).count { seed ->
                Recommender.arrange(
                    listOf(hot, fav),
                    source = FeedSource.guess,
                    taste = taste,
                    now = now,
                    seed = seed.toULong(),
                    favorites = favorites,
                ).firstOrNull()?.username == "Fav"
            }
            assertTrue(wins > 95)
        }
        val popular = Recommender.arrange(
            listOf(hot, fav),
            source = FeedSource.popular,
            taste = Taste(),
            now = now,
            seed = 1u,
            favorites = favorites,
        )
        assertEquals(listOf("hot", "Fav"), names(popular))
    }
}
