package vela.core.cross

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

class VetoVisibilityTests {
    private val now: Instant = Instant.ofEpochSecond(3_000_000)

    private fun room(name: String, tags: List<String>, gender: Gender? = Gender.female): Room =
        Room(username = name, viewers = 5, gender = gender, tags = tags, show = ShowState.publicShow)

    @Test
    fun testVetoHidesWithoutDeletingAndRestoreBringsRoomsBack() {
        var taste = Taste().vetoing(Taste.tagKey("nope"), now)
        val feed = FeedStore()
        feed.replace(
            CatalogPage(
                rooms = listOf(room("cur", listOf("ok")), room("a", listOf("ok")), room("b", listOf("Nope")), room("c", listOf("ok"))),
                total = 4,
                nextOffset = 4,
            ),
        )
        feed.setFilters(blocked = emptySet(), only = null, hidden = taste.vetoedKeys)
        assertEquals(listOf("cur", "a", "c"), feed.rooms.map { it.username })
        assertEquals(4, feed.catalog.size, "不从目录删除")

        taste = taste.restoring(Taste.tagKey("nope"), now)
        feed.setFilters(blocked = emptySet(), only = null, hidden = taste.vetoedKeys)
        feed.reorderAfterCurrent { rooms ->
            Recommender.arrange(rooms, source = FeedSource.guess, taste = taste, now = now, seed = 1u)
        }
        assertEquals("cur", feed.currentUsername)
        assertTrue(feed.rooms.map { it.username }.contains("b"), "恢复后房间回来")
        assertEquals("cur", feed.rooms.firstOrNull()?.username)
    }

    @Test
    fun testVetoedGenderIsHiddenAndAllVetoedLeavesEmptyVisibleList() {
        val taste = Taste().vetoing(Taste.genderKey(Gender.male), now)
        val feed = FeedStore()
        feed.replace(CatalogPage(rooms = listOf(room("m", listOf("x"), gender = Gender.male)), total = 1, nextOffset = 1))
        feed.setFilters(blocked = emptySet(), only = null, hidden = taste.vetoedKeys)
        assertTrue(feed.rooms.isEmpty())
        assertEquals(1, feed.catalog.size)
    }

    @Test
    fun testSetFiltersWithoutHiddenKeepsExistingHiddenKeys() {
        val feed = FeedStore()
        feed.replace(
            CatalogPage(
                rooms = listOf(room("a", listOf("nope")), room("b", listOf("ok"))),
                total = 2,
                nextOffset = 2,
            ),
        )
        feed.setFilters(blocked = emptySet(), only = null, hidden = setOf(Taste.tagKey("nope")))
        feed.setFilters(blocked = setOf("zzz"), only = null)
        assertEquals(listOf("b"), feed.rooms.map { it.username })
    }
}
