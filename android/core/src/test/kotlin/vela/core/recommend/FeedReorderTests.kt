package vela.core.recommend

import vela.core.base.model.Room
import vela.core.base.model.RoomQuery
import vela.core.base.model.Gender
import vela.core.base.model.ShowState
import vela.core.base.site.CatalogPage
import vela.core.feed.FeedStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FeedReorderTests {
    private fun room(name: String, secs: Int? = null): Room = Room(
        username = name,
        viewers = 10,
        gender = Gender.female,
        tags = listOf("x"),
        show = ShowState.publicShow,
        secondsOnline = secs,
    )

    @Test
    fun testReorderAfterCurrentLeavesCurrentAndBeforeUntouched() {
        val feed = FeedStore()
        feed.replace(
            CatalogPage(
                rooms = listOf(
                    room("a", secs = 900),
                    room("b", secs = 800),
                    room("c", secs = 700),
                    room("d", secs = 600),
                ),
                total = 8,
                nextOffset = 4,
            ),
        )
        feed.step(1)
        feed.append(
            CatalogPage(
                rooms = listOf(room("e", secs = 10), room("f", secs = 5), room("g", secs = null)),
                total = 8,
                nextOffset = 7,
            ),
        )
        feed.reorderAfterCurrent(Recommender::newest)
        assertEquals(listOf("a", "b", "f", "e", "d", "c", "g"), feed.catalog.map { it.username })
        assertEquals("b", feed.currentUsername)
    }
}

class FeedBlockTests {
    private fun room(name: String): Room = Room(
        username = name,
        viewers = 10,
        gender = Gender.female,
        tags = emptyList(),
        show = ShowState.publicShow,
    )

    @Test
    fun testBlockingLastRoomThenUnblockingChangesCurrentBothWays() {
        val feed = FeedStore()
        feed.replace(CatalogPage(rooms = listOf(room("solo")), total = 1, nextOffset = 1))
        assertEquals("solo", feed.current?.username)
        feed.setBlocked(setOf("solo"))
        assertNull(feed.current)
        feed.setBlocked(emptySet())
        assertEquals("solo", feed.current?.username)
    }

    @Test
    fun testBlankVisibleListKeepsPagingUntilCapOrEnd() {
        val feed = FeedStore(query = RoomQuery(blocked = setOf("a", "b")))
        feed.replace(CatalogPage(rooms = listOf(room("a"), room("b")), total = 10, nextOffset = 2))
        assertTrue(feed.rooms.isEmpty())
        assertTrue(feed.shouldLoadMore())
        assertTrue(feed.shouldLoadMore(blankPages = FeedStore.maxBlankPages - 1))
        assertFalse(feed.shouldLoadMore(blankPages = FeedStore.maxBlankPages))
        feed.append(CatalogPage(rooms = listOf(room("c")), total = 10, nextOffset = 3))
        assertEquals("c", feed.current?.username)
    }

    @Test
    fun testBlankVisibleListStopsAtRealEnd() {
        val feed = FeedStore(query = RoomQuery(blocked = setOf("a")))
        feed.replace(CatalogPage(rooms = listOf(room("a")), total = 1, nextOffset = 1))
        assertTrue(feed.rooms.isEmpty())
        assertFalse(feed.shouldLoadMore())
    }
}
