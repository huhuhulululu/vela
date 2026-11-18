package vela.core.stream

import java.time.Instant
import java.util.ArrayDeque
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import vela.core.base.net.EndpointBackoffBox
import vela.core.base.net.EndpointBackedOff
import vela.core.base.net.HTTPClient
import vela.core.base.net.HTTPRequest
import vela.core.base.net.HTTPResult
import vela.core.base.net.SiteEndpoint

/**
 * `StreamDecoder.fetch` 的退避与质询。Swift 落在 EndpointBackoffTests，组 l0a 已登记 deferred:C9。
 * 这里先锁住 C4 的行为，C9 再按原文件名消化。
 */
class StreamFetchTests {
    @Test
    fun testStreamFetchBacksOffAfterNonJSON() = runBlocking {
        val clock = Clock()
        val box = EndpointBackoffBox(now = { clock.now })
        val client = Replay(
            listOf(
                HTTPResult(status = 429, data = "<html><body>Too many requests</body></html>".toByteArray()),
                HTTPResult(
                    status = 200,
                    data = """{"success":true,"url":"https://e.example/a.m3u8","room_status":"public"}""".toByteArray(),
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
        val client = Replay(
            listOf(HTTPResult(status = 403, data = "<title>Just a moment...</title>".toByteArray())),
        )
        val error = assertFailsWith<StreamDecodeError.Challenge> {
            StreamDecoder.fetch(username = "a", client = client, backoff = box)
        }
        assertEquals(StreamDecodeError.Challenge, error)
        assertTrue(box.allows(SiteEndpoint.streamURL))
    }

    private class Clock {
        var now: Instant = Instant.ofEpochSecond(50_000)

        fun advance(seconds: Long) {
            now = now.plusSeconds(seconds)
        }
    }

    private class Replay(queue: List<HTTPResult>) : HTTPClient {
        private val queue = ArrayDeque(queue)
        var count: Int = 0

        override suspend fun send(request: HTTPRequest): HTTPResult {
            count += 1
            return if (queue.isEmpty()) HTTPResult(status = 200, data = "{}".toByteArray()) else queue.removeFirst()
        }
    }
}
