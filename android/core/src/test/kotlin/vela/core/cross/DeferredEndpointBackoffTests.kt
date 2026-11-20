package vela.core.cross

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import vela.core.base.net.EndpointBackoff
import vela.core.base.net.EndpointBackoffBox
import vela.core.base.net.EndpointBackedOff
import vela.core.base.net.HTTPResult
import vela.core.base.net.SiteEndpoint
import vela.core.base.site.CatalogFailure
import vela.core.base.site.CatalogQuery
import vela.core.base.site.ChatContext
import vela.core.base.site.FavoriteStatus
import vela.core.curation.FavoriteFetcher
import vela.core.curation.LastBroadcastFetcher
import vela.core.feed.CatalogAPI
import vela.core.lifecycle.AwayRetry
import vela.core.stream.StreamDecodeError
import vela.core.stream.StreamDecoder
import vela.core.stream.StreamFailure

/** L0a 标成 deferred:C9 的 EndpointBackoffTests。依赖 C3、C4、C5、C7 的类型。 */
class DeferredEndpointBackoffTests {
    private val t0: Instant = Instant.ofEpochSecond(1_000)

    private class Clock {
        var now: Instant = Instant.ofEpochSecond(50_000)

        fun advance(seconds: Long) {
            now = now.plusSeconds(seconds)
        }
    }

    @Test
    fun testAgePageIsChallengeForStreamAndCatalog() {
        val page = utf8("<html><title>Verify your age</title></html>")
        val stream = assertFailsWith<StreamDecodeError.Challenge> {
            StreamDecoder.decode(status = 200, data = page)
        }
        assertEquals(StreamDecodeError.Challenge, stream)
        val catalog = assertFailsWith<CatalogFailure.Challenge> {
            ChatContext.decode(status = 200, data = page, now = t0)
        }
        assertEquals(CatalogFailure.Challenge, catalog)
    }

    @Test
    fun testStreamFetchBacksOffAfterNonJSON() = runBlocking {
        val clock = Clock()
        val box = EndpointBackoffBox(now = { clock.now })
        val client = ReplayClient(
            listOf(
                HTTPResult(status = 429, data = utf8("<html><body>Too many requests</body></html>")),
                HTTPResult(
                    status = 200,
                    data = utf8("""{"success":true,"url":"https://e.example/a.m3u8","room_status":"public"}"""),
                ),
            ),
        )
        val unavailable = assertFailsWith<StreamDecodeError.Unavailable> {
            StreamDecoder.fetch(username = "a", client = client, backoff = box)
        }
        assertEquals(429, unavailable.status)
        assertEquals(StreamFailure.unavailable, unavailable.failure)
        clock.advance(10)
        val backed = assertFailsWith<EndpointBackedOff> {
            StreamDecoder.fetch(username = "b", client = client, backoff = box)
        }
        assertEquals(SiteEndpoint.streamURL, backed.endpoint)
        assertEquals(1, client.count)
        clock.advance(20)
        val ticket = StreamDecoder.fetch(username = "c", client = client, backoff = box)
        assertTrue(ticket.isPlayable)
        assertEquals(2, client.count)
    }

    @Test
    fun testStreamFetchChallengeDoesNotBackOff() = runBlocking {
        val box = EndpointBackoffBox()
        val client = ReplayClient(listOf(HTTPResult(status = 403, data = utf8("<title>Just a moment...</title>"))))
        val error = assertFailsWith<StreamDecodeError.Challenge> {
            StreamDecoder.fetch(username = "a", client = client, backoff = box)
        }
        assertEquals(StreamDecodeError.Challenge, error)
        assertTrue(box.allows(SiteEndpoint.streamURL))
    }

    @Test
    fun testCatalogBacksOffAndKeepsFailureKind() = runBlocking {
        val box = EndpointBackoffBox()
        val api = CatalogAPI(client = ReplayClient(listOf(HTTPResult(status = 200, data = utf8("<html>busy</html>")))), backoff = box)
        val malformed = assertFailsWith<CatalogFailure.Malformed> { api.fetch(CatalogQuery()) }
        assertEquals(CatalogFailure.Malformed, malformed)
        val backed = assertFailsWith<EndpointBackedOff> { api.fetch(CatalogQuery()) }
        assertEquals(SiteEndpoint.catalog, backed.endpoint)
        assertFalse(box.allows(SiteEndpoint.catalog))
        assertTrue(box.allows(SiteEndpoint.chatContext))
    }

    @Test
    fun testFavoriteFetcherBacksOffWithoutCachingOrChallenge() = runBlocking {
        val box = EndpointBackoffBox()
        val client = ReplayClient(
            listOf(HTTPResult(status = 403, data = utf8("denied"))) +
                List(5) { HTTPResult(status = 200, data = utf8("<html>x</html>")) },
        )
        val result = FavoriteFetcher(client = client, concurrency = 1, backoff = box).fetch(listOf("a", "b", "c"))
        assertTrue(result.statuses.isEmpty(), "状态未知，不写缓存")
        assertFalse(result.challenged, "普通 403 文本按暂时不可用，不弹确认页")
        assertEquals(1, client.count, "退避后同一接口不再发请求")
        assertFalse(box.allows(SiteEndpoint.chatContext))
    }

    @Test
    fun testFavoriteFetcherDefinitiveStatusesIgnoreBody() = runBlocking {
        val box = EndpointBackoffBox()
        val client = ReplayClient(listOf(HTTPResult(status = 404, data = utf8("<html>Not Found</html>"))))
        val result = FavoriteFetcher(client = client, backoff = box).fetch(listOf("gone"))
        assertEquals(FavoriteStatus.NotFound, result.statuses["gone"])
        assertTrue(box.allows(SiteEndpoint.chatContext))
    }

    @Test
    fun testLastBroadcastFetcherBacksOff() = runBlocking {
        val box = EndpointBackoffBox()
        val client = ReplayClient(
            listOf(
                HTTPResult(status = 429, data = utf8("<html><body>Too many requests</body></html>")),
                HTTPResult(status = 200, data = utf8("""{"last_broadcast":"2026-01-01T00:00:00Z"}""")),
            ),
        )
        val result = LastBroadcastFetcher(client = client, concurrency = 1, backoff = box).fetch(listOf("a", "b"))
        assertTrue(result.found.isEmpty())
        assertFalse(result.challenged)
        assertEquals(1, client.count)
        assertFalse(box.allows(SiteEndpoint.bioContext))
        assertTrue(box.allows(SiteEndpoint.chatContext))
    }

    @Test
    fun testNoteAfterCancelSwitchesWaitingNotes() {
        assertEquals(AwayRetry.heldNote, AwayRetry.noteAfterCancel(AwayRetry.waitingNote))
        assertEquals(EndpointBackoff.heldNote, AwayRetry.noteAfterCancel(EndpointBackoff.waitingNote))
        assertEquals("别的提示", AwayRetry.noteAfterCancel("别的提示"))
        assertNull(AwayRetry.noteAfterCancel(null))
    }
}
