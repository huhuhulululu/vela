package vela.core.feed

import kotlinx.coroutines.runBlocking
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import vela.core.base.model.Room
import vela.core.base.model.RoomQuery
import vela.core.base.model.ShowState
import vela.core.base.net.EndpointBackoffBox
import vela.core.base.net.HTTPClient
import vela.core.base.net.HTTPRequest
import vela.core.base.net.HTTPResult
import vela.core.base.site.CatalogFailure
import vela.core.base.site.CatalogPage
import vela.core.base.site.CatalogQuery

class VelaCoreTests {
    @BeforeTest
    fun setUp() {
        EndpointBackoffBox.shared.reset()
    }

    @Test
    fun testCatalogFetchRejectsChallenge() = runBlocking {
        val client = FixtureClient(status = 403, data = "<html>Just a moment</html>".toByteArray())
        val api = CatalogAPI(client)
        val error = assertFailsWith<CatalogFailure> { api.fetch(CatalogQuery()) }
        assertEquals(CatalogFailure.Challenge, error)
    }

    @Test
    fun testFeedKeepsPlaceFiltersAndPrefetches() {
        val feed = FeedStore()
        feed.replace(
            CatalogPage(
                rooms = listOf(room("a"), room("b"), room("c", show = ShowState.privateShow)),
                total = 5,
                nextOffset = 3,
            ),
        )
        assertEquals("a", feed.current?.username)
        assertEquals(listOf("a", "b"), feed.rooms.map { it.username })
        assertFalse(feed.reachedEnd)
        feed.step(1)
        assertEquals("b", feed.current?.username)
        feed.setQuery(RoomQuery(text = "zzz", publicOnly = true))
        assertNull(feed.current?.username)
        feed.setQuery(RoomQuery(publicOnly = true))
        assertEquals("a", feed.current?.username)
        feed.focus("b")
        feed.insertDirect(Room.direct("new_room")!!)
        assertEquals("new_room", feed.current?.username)
        assertEquals(emptyList(), feed.prefetchUsernames())
        feed.append(CatalogPage(rooms = listOf(room("b"), room("d", show = ShowState.publicShow)), total = 5, nextOffset = 5))
        assertTrue(feed.catalog.any { it.username == "d" })
        assertEquals(1, feed.catalog.count { it.username == "b" })
        assertTrue(feed.reachedEnd)
    }

    private fun room(name: String, show: ShowState = ShowState.publicShow, subject: String = ""): Room =
        Room(username = name, subject = subject, show = show)

    private class FixtureClient(val status: Int, val data: ByteArray) : HTTPClient {
        override suspend fun send(request: HTTPRequest): HTTPResult = HTTPResult(status, data)
    }
}
