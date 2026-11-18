package vela.core.feed

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import vela.core.base.model.Gender
import vela.core.base.model.Room
import vela.core.base.model.RoomQuery
import vela.core.base.model.ShowState
import vela.core.base.site.CatalogPage

/** FeedStore 的选择、步进、翻页和前置可以指定锚点；不传锚点时与原来的主路行为一致。 */
class FeedAnchorTests {
    private fun room(name: String): Room =
        Room(username = name, viewers = 10, gender = Gender.female, tags = emptyList(), show = ShowState.publicShow)

    private fun store(names: List<String>, current: String? = null): FeedStore {
        val feed = FeedStore()
        feed.replace(CatalogPage(rooms = names.map { room(it) }, total = names.size, nextOffset = names.size))
        if (current != null) {
            assertTrue(feed.focus(current))
        }
        return feed
    }

    @Test
    fun testSelectWritesAVisibleRoomAndClearsWhenNil() {
        val feed = store(listOf("a", "b", "c"), current = "a")
        feed.select("C")
        assertEquals("c", feed.currentUsername)
        feed.select(null)
        assertNull(feed.currentUsername)
        feed.select("missing")
        assertNull(feed.currentUsername, "不在可见列表里的名字不改当前房间")
        feed.select("b")
        assertEquals("b", feed.currentUsername)
        assertFalse(feed.focus("nope"))
        assertEquals("b", feed.currentUsername)
    }

    @Test
    fun testStepSkippingEmptyMatchesPlainStep() {
        val names = listOf("a", "b", "c", "d", "e")
        for (delta in listOf(-4, -1, 0, 1, 3, 9)) {
            val plain = store(names, current = "c")
            val skipping = plain.copy()
            val left = plain.step(delta)
            val right = skipping.step(delta, emptySet())
            assertEquals(plain.currentUsername, skipping.currentUsername, "delta $delta")
            assertEquals(left?.username, right?.username, "delta $delta")
        }
    }

    @Test
    fun testStepSkippingJumpsOverNamedRoomsAndRefusesToLandOnThem() {
        val feed = store(listOf("a", "b", "c", "d"), current = "b")
        feed.step(1, setOf("c"))
        assertEquals("d", feed.currentUsername)

        val stuck = store(listOf("a", "b"), current = "a")
        val stayed = stuck.step(1, setOf("b"))
        assertEquals("a", stuck.currentUsername)
        assertEquals("a", stayed?.username)

        val tail = store(listOf("a", "b", "c"), current = "b")
        tail.step(1, setOf("C"))
        assertEquals("b", tail.currentUsername, "落点被跳过且前方没有房间时停在原地")
    }

    @Test
    fun testShouldLoadMoreAtAnchorMatchesCurrentAndBlankPages() {
        val feed = store((0 until 20).map { "r$it" }, current = "r0")
        feed.reachedEnd = false
        assertFalse(feed.shouldLoadMore())
        assertEquals(
            feed.shouldLoadMore(),
            feed.shouldLoadMore(feed.currentUsername, threshold = 8, blankPages = 0),
        )
        assertFalse(feed.shouldLoadMore("r0", threshold = 8, blankPages = 0))
        assertTrue(feed.shouldLoadMore("r12", threshold = 8, blankPages = 0))
        assertTrue(feed.shouldLoadMore("r19", threshold = 8, blankPages = 0))
        assertFalse(feed.shouldLoadMore("missing", threshold = 8, blankPages = 0), "找不到锚点时与 index 回落到 0 相同")
        assertTrue(feed.focus("r19"))
        assertEquals(
            feed.shouldLoadMore(threshold = 8, blankPages = 0),
            feed.shouldLoadMore(feed.currentUsername, threshold = 8, blankPages = 0),
        )

        val blank = FeedStore(query = RoomQuery(blocked = setOf("a", "b")))
        blank.replace(CatalogPage(rooms = listOf(room("a"), room("b")), total = 10, nextOffset = 2))
        assertTrue(blank.rooms.isEmpty())
        assertEquals(
            blank.shouldLoadMore(blankPages = FeedStore.maxBlankPages - 1),
            blank.shouldLoadMore("a", threshold = 8, blankPages = FeedStore.maxBlankPages - 1),
        )
        assertFalse(blank.shouldLoadMore(null, threshold = 1, blankPages = FeedStore.maxBlankPages))
        blank.reachedEnd = true
        assertFalse(blank.shouldLoadMore(null, threshold = 8, blankPages = 0))
    }

    @Test
    fun testPromoteAfterAnchorMatchesCurrentAndCanUseAnotherAnchor() {
        val names = (0 until 8).map { "r$it" }
        val current = store(names, current = "r2")
        val anchored = current.copy()
        current.promoteAfterCurrent(listOf("R6", "r4", "r1", "r2", "ghost", "r6"))
        anchored.promoteAfter("r2", listOf("R6", "r4", "r1", "r2", "ghost", "r6"))
        assertEquals(current.catalog.map { it.username }, anchored.catalog.map { it.username })
        assertEquals("r2", anchored.currentUsername)

        val other = store(names, current = "r2")
        other.promoteAfter("r0", listOf("r3"))
        assertEquals(listOf("r0", "r3", "r1", "r2", "r4", "r5", "r6", "r7"), other.catalog.map { it.username })
        assertEquals("r2", other.currentUsername, "前置别的锚点不改当前房间")

        val missing = store(names, current = "r2")
        missing.promoteAfter(null, listOf("r5"))
        assertEquals(names, missing.catalog.map { it.username })
        missing.promoteAfter("nobody", listOf("r5"))
        assertEquals(names, missing.catalog.map { it.username })
    }
}
