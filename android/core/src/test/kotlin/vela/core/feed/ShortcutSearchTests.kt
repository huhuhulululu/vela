package vela.core.feed

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import vela.core.base.model.Gender
import vela.core.base.model.Room
import vela.core.base.model.RoomQuery
import vela.core.base.model.ShowState
import vela.core.base.site.CatalogPage

class ShortcutSearchTests {
    private fun room(name: String, tags: List<String> = listOf("x")): Room =
        Room(username = name, viewers = 10, gender = Gender.female, tags = tags, show = ShowState.publicShow)

    private fun feed(): FeedStore {
        val feed = FeedStore()
        feed.replace(
            CatalogPage(
                rooms = listOf(room("amy"), room("bob"), room("mike"), room("sofia")),
                total = 4,
                nextOffset = 4,
            ),
        )
        feed.focus("bob")
        return feed
    }

    @Test
    fun testPreviewKeepsCurrentRoomWhileFilteringOthers() {
        val feed = feed()
        feed.previewQuery(RoomQuery(text = "mi"))
        assertEquals("bob", feed.currentUsername)
        assertEquals("bob", feed.current?.username)
        assertEquals(listOf("bob", "mike"), feed.rooms.map { it.username }, "保留的当前房间 + 匹配项")
        feed.previewQuery(RoomQuery(text = "mik"))
        assertEquals("bob", feed.current?.username, "继续打字仍不换房")
    }

    @Test
    fun testSubmitSwitchesToFirstMatch() {
        val feed = feed()
        feed.previewQuery(RoomQuery(text = "mi"))
        feed.setQuery(RoomQuery(text = "mi"))
        assertNull(feed.retained)
        assertEquals(listOf("mike"), feed.rooms.map { it.username })
        assertEquals("mike", feed.currentUsername)
    }

    @Test
    fun testSteppingAwayDropsRetainedRoom() {
        val feed = feed()
        feed.previewQuery(RoomQuery(text = "mi"))
        feed.step(1)
        assertEquals("mike", feed.currentUsername)
        assertNull(feed.retained)
        assertEquals(listOf("mike"), feed.rooms.map { it.username })
    }

    @Test
    fun testPreviewMatchingCurrentChangesNothingVisible() {
        val feed = feed()
        feed.previewQuery(RoomQuery(text = "bo"))
        assertEquals(listOf("bob"), feed.rooms.map { it.username })
        assertEquals("bob", feed.currentUsername)
    }

    @Test
    fun testPreviewThenClearTextRestoresAll() {
        val feed = feed()
        feed.previewQuery(RoomQuery(text = "zz"))
        assertEquals(listOf("bob"), feed.rooms.map { it.username })
        feed.previewQuery(RoomQuery(text = ""))
        assertEquals(listOf("amy", "bob", "mike", "sofia"), feed.rooms.map { it.username })
        assertEquals("bob", feed.currentUsername)
    }
}
