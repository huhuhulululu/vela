package vela.core.stream

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import vela.core.base.net.EndpointBackoffBox
import vela.core.base.net.HTTPClient
import vela.core.base.net.HTTPRequest
import vela.core.base.net.HTTPResult
import vela.core.base.net.SiteEndpoint
import vela.core.base.site.ChatContext

private class Fixed(private val result: HTTPResult) : HTTPClient {
    override suspend fun send(request: HTTPRequest): HTTPResult = result
}

/** 起播探测：401 / 404 是确定答案，空正文或 HTML 正文不登记全局退避。 */
class ChatContextProbeTests {
    @Test
    fun testDefinitiveStatusesWithEmptyOrHTMLBodyAreFailedNotNotJSON() {
        for (status in listOf(401, 404)) {
            assertEquals(ContextProbe.Failed, ChatContext.probe(status, ByteArray(0)), "$status 空正文")
            assertEquals(
                ContextProbe.Failed,
                ChatContext.probe(status, "<html>Not Found</html>".toByteArray()),
                "$status HTML",
            )
        }
    }

    @Test
    fun testNonDefinitiveEmptyBodyStillNotJSON() {
        assertEquals(ContextProbe.NotJSON, ChatContext.probe(502, ByteArray(0)))
        assertEquals(ContextProbe.NotJSON, ChatContext.probe(200, "<html>busy</html>".toByteArray()))
    }

    @Test
    fun testDefinitiveStatusDoesNotRegisterBackoff() = runBlocking {
        for ((status, text) in listOf(401 to "", 404 to "<html>Not Found</html>")) {
            val box = EndpointBackoffBox()
            val client = Fixed(HTTPResult(status = status, data = text.toByteArray()))
            val outcome = ChatContext.probe(username = "gone", client = client, backoff = box)
            assertEquals(ContextProbe.Failed, outcome)
            assertTrue(box.allows(SiteEndpoint.chatContext), "$status 不应退避")
        }
    }

    @Test
    fun testRateLimitPageStillBacksOff() = runBlocking {
        val box = EndpointBackoffBox()
        val client = Fixed(HTTPResult(status = 429, data = "<html>Too many requests</html>".toByteArray()))
        val outcome = ChatContext.probe(username = "x", client = client, backoff = box)
        assertEquals(ContextProbe.NotJSON, outcome)
        assertFalseBox(box)
    }

    private fun assertFalseBox(box: EndpointBackoffBox) {
        kotlin.test.assertFalse(box.allows(SiteEndpoint.chatContext))
    }
}
