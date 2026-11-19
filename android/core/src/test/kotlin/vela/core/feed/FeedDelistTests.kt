package vela.core.feed

import java.net.URI
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
import vela.core.stream.StreamFailure
import vela.core.stream.StreamTicket

/** parity: Tests/VelaCoreTests/FeedDelistTests.swift */
class FeedDelistTests {
    private fun room(name: String, show: ShowState = ShowState.publicShow): Room =
        Room(username = name, viewers = 10, gender = Gender.female, tags = emptyList(), show = show)

    private fun feed(publicOnly: Boolean = true): FeedStore {
        val store = FeedStore(query = RoomQuery(publicOnly = publicOnly))
        store.replace(CatalogPage(rooms = listOf("a", "b", "c").map { room(it) }, total = 3, nextOffset = 3))
        return store
    }

    private fun ticket(status: ShowState, success: Boolean = false, url: URI? = null): StreamTicket =
        StreamTicket(success = success, url = url, status = status)

    @Test
    fun testDelistRemovesRoomFromVisibleListAndPrefetch() {
        val store = feed()
        assertEquals(listOf("b"), store.prefetchUsernames())
        assertTrue(store.delist("b", ShowState.privateShow))
        assertEquals(listOf("a", "c"), store.rooms.map { it.username })
        assertEquals(listOf("c"), store.prefetchUsernames())
        assertEquals("a", store.currentUsername)
    }

    @Test
    fun testDelistIsCaseInsensitive() {
        val store = feed()
        assertTrue(store.delist("B", ShowState.offline))
        assertEquals(listOf("a", "c"), store.rooms.map { it.username })
    }

    @Test
    fun testDelistSkippedWhenPublicOnlyIsOff() {
        val store = feed(publicOnly = false)
        assertFalse(store.delist("b", ShowState.privateShow))
        assertEquals(listOf("a", "b", "c"), store.rooms.map { it.username })
    }

    @Test
    fun testDelistNeverTouchesCurrentOrProtectedRooms() {
        val store = feed()
        assertFalse(store.delist("a", ShowState.group))
        assertFalse(store.delist("c", ShowState.group, protecting = setOf("C")))
        assertEquals(listOf("a", "b", "c"), store.rooms.map { it.username })
    }

    @Test
    fun testDelistIgnoresAwayAndPublicStates() {
        val store = feed()
        assertFalse(store.delist("b", ShowState.away))
        assertFalse(store.delist("b", ShowState.publicShow))
        assertEquals(3, store.rooms.size)
    }

    @Test
    fun testDelistOnlyAppliesToRoomsListedAsPublic() {
        val store = FeedStore(query = RoomQuery(publicOnly = true))
        store.replace(
            CatalogPage(
                rooms = listOf(room("a"), room("x", ShowState.unknown)),
                total = 2,
                nextOffset = 2,
            ),
        )
        assertFalse(store.canDelist("x"))
        assertFalse(store.delist("x", ShowState.privateShow))
        assertFalse(store.canDelist("nobody"))
    }

    @Test
    fun testRefreshBringsDelistedRoomBackWhenPublic() {
        val store = feed()
        store.delist("b", ShowState.privateShow)
        store.replace(CatalogPage(rooms = listOf("a", "b", "c").map { room(it) }, total = 3, nextOffset = 3))
        assertEquals(listOf("a", "b", "c"), store.rooms.map { it.username })
    }

    @Test
    fun testTicketDelistsOnlyConfirmedStatuses() {
        assertEquals(ShowState.privateShow, ticket(ShowState.privateShow).delistedShow)
        assertEquals(ShowState.group, ticket(ShowState.group).delistedShow)
        assertEquals(ShowState.hidden, ticket(ShowState.hidden).delistedShow)
        assertEquals(ShowState.password, ticket(ShowState.password).delistedShow)
        assertEquals(ShowState.offline, ticket(ShowState.offline).delistedShow)
    }

    @Test
    fun testUnknownTicketIsNotDelistedButKeepsOfflineNotice() {
        val unknown = ticket(ShowState.unknown)
        assertNull(unknown.delistedShow)
        assertEquals(StreamFailure.offline, unknown.failure)
    }

    @Test
    fun testAwayGeoAndPlayableTicketsAreNotDelisted() {
        assertNull(ticket(ShowState.away).delistedShow)
        assertNull(ticket(ShowState.publicShow).delistedShow)
        assertNull(
            ticket(ShowState.publicShow, success = true, url = URI("https://x.test/a.m3u8")).delistedShow,
        )
    }
}
