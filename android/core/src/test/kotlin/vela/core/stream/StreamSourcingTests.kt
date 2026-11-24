package vela.core.stream

import java.net.URI
import java.util.concurrent.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.runBlocking
import vela.core.base.model.ShowState
import vela.core.base.net.EndpointBackoffBox
import vela.core.base.net.HTTPClient
import vela.core.base.net.HTTPRequest
import vela.core.base.net.HTTPResult
import vela.core.base.net.SiteEndpoint
import vela.core.base.site.ChatContext
import vela.core.hls.TransportError
import vela.core.hls.UrlErrors

private class Canned(
    private val result: HTTPResult? = null,
    private val error: Throwable? = null,
) : HTTPClient {
    override suspend fun send(request: HTTPRequest): HTTPResult {
        error?.let { throw it }
        return result ?: HTTPResult(status = 500, data = ByteArray(0))
    }
}

private fun context(status: String, source: String? = null): ByteArray {
    val field = if (source != null) ""","hls_source":"$source"""" else ""
    return """{"room_status":"$status","broadcaster_username":"a"$field}""".toByteArray()
}

private const val SOURCE = "https://edge2.example/v1/edge/streams/origin.a.1/llhls.m3u8?token=t"
private val edgeURL: URI = URI.create("https://edge9.example/v1/edge/streams/origin.a.1/llhls.m3u8?token=u")

class StreamSourcingTests {
    @Test
    fun testProbeDecodesStatusAndSource() {
        assertEquals(
            ContextProbe.Room(ShowState.publicShow, URI.create(SOURCE)),
            ChatContext.probe(status = 200, data = context("public", SOURCE)),
        )
        assertEquals(
            ContextProbe.Room(ShowState.publicShow, null),
            ChatContext.probe(status = 200, data = context("public", "")),
        )
        assertEquals(
            ContextProbe.Room(ShowState.publicShow, null),
            ChatContext.probe(status = 200, data = context("public")),
        )
        assertEquals(
            ContextProbe.Room(ShowState.publicShow, null),
            ChatContext.probe(status = 200, data = context("public", "http://plain.example/a.m3u8")),
            "只接受 https",
        )
        assertEquals(
            ContextProbe.Room(ShowState.away, null),
            ChatContext.probe(status = 200, data = context("away")),
        )
    }

    @Test
    fun testProbeFailureKinds() {
        assertEquals(ContextProbe.Challenge, ChatContext.probe(403, "<title>Just a moment...</title>".toByteArray()))
        assertEquals(ContextProbe.Challenge, ChatContext.probe(200, "<title>Verify your age</title>".toByteArray()))
        assertEquals(ContextProbe.NotJSON, ChatContext.probe(200, "<html>busy</html>".toByteArray()))
        assertEquals(ContextProbe.NotJSON, ChatContext.probe(502, ByteArray(0)))
        assertEquals(ContextProbe.Failed, ChatContext.probe(404, """{"detail":"x"}""".toByteArray()))
        assertEquals(ContextProbe.Failed, ChatContext.probe(200, "[1]".toByteArray()))
    }

    @Test
    fun testPublicWithSourceUsesContextDirectly() {
        val step = StreamSourcing.first(ContextProbe.Room(ShowState.publicShow, URI.create(SOURCE)))
        val ticket = (step as? StreamSourcing.Step.Use)?.ticket ?: fail("expected use")
        assertTrue(ticket.isPlayable)
        assertEquals(SOURCE, ticket.url?.toString())
    }

    @Test
    fun testKnownNonPublicStatesDecideWithoutAjax() {
        val cases = listOf(
            ShowState.away to StreamFailure.away,
            ShowState.privateShow to StreamFailure.privateShow,
            ShowState.group to StreamFailure.group,
            ShowState.offline to StreamFailure.offline,
            ShowState.hidden to StreamFailure.hidden,
            ShowState.password to StreamFailure.password,
        )
        for ((show, failure) in cases) {
            val step = StreamSourcing.first(ContextProbe.Room(show, URI.create(SOURCE)))
            val ticket = (step as? StreamSourcing.Step.Use)?.ticket ?: fail("$show 应直接定论")
            assertNull(ticket.url, "非公开时即使带了地址也不播")
            assertEquals(failure, ticket.failure)
        }
    }

    @Test
    fun testFallsBackToAjax() {
        val probes = listOf(
            ContextProbe.Room(ShowState.publicShow, null),
            ContextProbe.Room(ShowState.unknown, URI.create(SOURCE)),
            ContextProbe.Challenge,
            ContextProbe.NotJSON,
            ContextProbe.BackedOff,
            ContextProbe.Failed,
        )
        for (probe in probes) {
            assertEquals(StreamSourcing.Step.Fallback, StreamSourcing.first(probe), "$probe")
        }
    }

    @Test
    fun testSettleKeepsPlayableAjax() {
        val ajax = StreamTicket(success = true, url = edgeURL, status = ShowState.publicShow)
        val settled = StreamSourcing.settle(probe = ContextProbe.Room(ShowState.publicShow, null), fallback = ajax)
        assertEquals(ajax, settled)
        assertNull(settled.failure)
    }

    @Test
    fun testSettleEmptyOnBothIsGeo() {
        val emptyPublic = StreamTicket(success = true, url = null, status = ShowState.publicShow)
        assertEquals(
            StreamFailure.geo,
            StreamSourcing.settle(ContextProbe.Room(ShowState.publicShow, null), emptyPublic).failure,
        )
        val vague = StreamTicket(success = false, url = null, status = ShowState.unknown)
        assertEquals(StreamFailure.offline, vague.failure)
        assertEquals(
            StreamFailure.geo,
            StreamSourcing.settle(ContextProbe.Room(ShowState.publicShow, null), vague).failure,
        )
    }

    @Test
    fun testSettleDefersToAjaxStateChange() {
        val nowAway = StreamTicket(success = true, url = null, status = ShowState.away)
        assertEquals(
            StreamFailure.away,
            StreamSourcing.settle(ContextProbe.Room(ShowState.publicShow, null), nowAway).failure,
        )
        val emptyPublic = StreamTicket(success = true, url = null, status = ShowState.publicShow)
        val vague = StreamTicket(success = false, url = null, status = ShowState.unknown)
        for (probe in listOf(ContextProbe.Failed, ContextProbe.Challenge, ContextProbe.NotJSON, ContextProbe.BackedOff)) {
            assertEquals(vague, StreamSourcing.settle(probe, vague))
            assertEquals(StreamFailure.geo, StreamSourcing.settle(probe, emptyPublic).failure)
        }
    }

    @Test
    fun testOriginLabels() {
        assertEquals("chatvideocontext", StreamOrigin.chatContext.rawValue)
        assertEquals("ajax", StreamOrigin.edge.rawValue)
        assertEquals("sidecar", StreamOrigin.sidecar.rawValue)
        val sourced = SourcedTicket(
            ticket = StreamTicket(success = true, url = null, status = ShowState.away),
            origin = StreamOrigin.chatContext,
        )
        assertEquals(StreamFailure.away, sourced.failure)
    }

    @Test
    fun testProbeRequestRecordsBackoffOnNonJSON() = runBlocking {
        val box = EndpointBackoffBox()
        val busy = Canned(result = HTTPResult(status = 200, data = "<html>busy</html>".toByteArray()))
        val first = ChatContext.probe(username = "a", client = busy, backoff = box)
        assertEquals(ContextProbe.NotJSON, first)
        assertFalse(box.allows(SiteEndpoint.chatContext))
        assertTrue(box.allows(SiteEndpoint.streamURL), "退回 ajax 不受 chatvideocontext 的退避影响")
        val second = ChatContext.probe(username = "a", client = busy, backoff = box)
        assertEquals(ContextProbe.BackedOff, second)
        assertEquals(StreamSourcing.Step.Fallback, StreamSourcing.first(second))
    }

    @Test
    fun testProbeRequestChallengeDoesNotBackOff() = runBlocking {
        val box = EndpointBackoffBox()
        val page = Canned(result = HTTPResult(status = 403, data = "<title>Just a moment...</title>".toByteArray()))
        val probe = ChatContext.probe(username = "a", client = page, backoff = box)
        assertEquals(ContextProbe.Challenge, probe)
        assertTrue(box.allows(SiteEndpoint.chatContext))
    }

    @Test
    fun testProbeRequestNetworkErrorFallsBackButCancellationThrows() = runBlocking {
        val box = EndpointBackoffBox()
        val down = Canned(error = TransportError(UrlErrors.TIMED_OUT))
        val probe = ChatContext.probe(username = "a", client = down, backoff = box)
        assertEquals(ContextProbe.Failed, probe)
        assertTrue(box.allows(SiteEndpoint.chatContext), "网络故障不退避")
        try {
            ChatContext.probe(username = "a", client = Canned(error = TransportError(UrlErrors.CANCELLED)), backoff = box)
            fail("取消不当网络故障")
        } catch (error: TransportError) {
            assertEquals(UrlErrors.CANCELLED, error.code)
        }
        try {
            ChatContext.probe(username = "a", client = Canned(error = CancellationException()), backoff = box)
            fail("取消不当网络故障")
        } catch (_: CancellationException) {
            // 取消必须原样抛出，不能当成网络故障。
        }
    }

    @Test
    fun testProbeRequestTargetsChatContext() = runBlocking {
        val spy = Spy()
        val probe = ChatContext.probe(username = "a", client = spy, backoff = null)
        assertEquals(ContextProbe.Room(ShowState.publicShow, URI.create(SOURCE)), probe)
        assertEquals(listOf(ChatContext.url("a")), spy.urls)
    }

    private class Spy : HTTPClient {
        val urls = ArrayList<String>()

        override suspend fun send(request: HTTPRequest): HTTPResult {
            urls.add(request.url)
            return HTTPResult(status = 200, data = context("public", SOURCE))
        }
    }
}
