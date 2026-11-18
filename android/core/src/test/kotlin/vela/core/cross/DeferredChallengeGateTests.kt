package vela.core.cross

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import vela.core.base.net.EndpointBackoffBox
import vela.core.base.net.HTTPResult
import vela.core.base.site.CatalogFailure
import vela.core.base.site.CatalogQuery
import vela.core.feed.CatalogAPI
import vela.core.stream.StreamDecodeError
import vela.core.stream.StreamDecoder
import vela.core.stream.StreamFailure

/** C3 标成 deferred:C9 的质询判定。流地址走 StreamDecoder，目录走 CatalogAPI.fetch。 */
class DeferredChallengeGateTests {
    private val page = utf8("<html><title>Just a moment...</title></html>")
    private val plain = utf8("""{"detail":"rate limited"}""")

    @Test
    fun testStreamChallengeOnlyWhenBodyLooksLikeChallenge() {
        for (status in listOf(200, 403, 503)) {
            val error = assertFailsWith<StreamDecodeError.Challenge> {
                StreamDecoder.decode(status = status, data = page)
            }
            assertEquals(StreamDecodeError.Challenge, error)
            assertTrue(error.isChallenge)
        }
    }

    @Test
    fun testStreamPlain403And503AreUnavailable() {
        for (status in listOf(403, 503)) {
            val error = assertFailsWith<StreamDecodeError.Unavailable> {
                StreamDecoder.decode(status = status, data = plain)
            }
            assertEquals(status, error.status)
            assertFalse(error.isChallenge)
            assertEquals(StreamFailure.unavailable, error.failure)
        }
        val empty = assertFailsWith<StreamDecodeError.Unavailable> {
            StreamDecoder.decode(status = 403, data = ByteArray(0))
        }
        assertEquals(403, empty.status, "空正文也不是质询")
    }

    @Test
    fun testStreamOtherStatusesUnchanged() {
        val malformed = assertFailsWith<StreamDecodeError.Malformed> {
            StreamDecoder.decode(status = 500, data = plain)
        }
        assertEquals(StreamDecodeError.Malformed, malformed)
        assertNull(malformed.failure)
        val ok = utf8("""{"success":true,"url":"https://edge.example/a.m3u8","room_status":"public"}""")
        assertTrue(StreamDecoder.decode(status = 200, data = ok).isPlayable)
    }

    @Test
    fun testCatalogPlain403And503AreStatusFailures() = runBlocking {
        for (status in listOf(403, 503)) {
            val api = CatalogAPI(
                client = ReplayClient(listOf(HTTPResult(status = status, data = plain))),
                backoff = EndpointBackoffBox(),
            )
            val error = assertFailsWith<CatalogFailure.Status> { api.fetch(CatalogQuery()) }
            assertEquals(status, error.code)
            assertFalse(error.isChallenge)
        }
    }

    @Test
    fun testCatalogChallengeBodyIsChallengeAtAnyStatus() = runBlocking {
        for (status in listOf(200, 403, 503)) {
            val api = CatalogAPI(
                client = ReplayClient(listOf(HTTPResult(status = status, data = page))),
                backoff = EndpointBackoffBox(),
            )
            val error = assertFailsWith<CatalogFailure.Challenge> { api.fetch(CatalogQuery()) }
            assertEquals(CatalogFailure.Challenge, error)
            assertTrue(error.isChallenge)
        }
    }
}
