package vela.core.sites

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import vela.core.base.model.Gender
import vela.core.base.model.Room
import vela.core.base.model.ShowState
import vela.core.base.net.EndpointBackoffBox
import vela.core.base.net.EndpointBackedOff
import vela.core.base.net.HTTPClient
import vela.core.base.net.HTTPRequest
import vela.core.base.net.HTTPResult
import vela.core.base.net.SiteEndpoint
import vela.core.base.site.CatalogFailure
import vela.core.base.site.CatalogQuery
import vela.core.base.site.ChatContext
import vela.core.feed.CatalogAPI
import vela.core.stream.SourcedTicket
import vela.core.stream.StreamDecodeError
import vela.core.stream.StreamDecoder
import vela.core.stream.StreamFailure
import vela.core.stream.StreamOrigin
import vela.core.stream.StreamSourcing
import vela.core.stream.probe

/** ChaturbateAdapter 必须与 CatalogAPI / StreamSourcing 现路径一致。对齐 Swift `ChaturbateAdapterTests`。 */
class ChaturbateAdapterTests {
    private val source = "https://edge.example/v1/origin.m3u8?token=t"

    @Test
    fun testDirectoryMatchesCatalogAPI() = runBlocking {
        val body = catalog(listOf(Triple("sample_one", "public", 4)))
        val query = CatalogQuery(gender = Gender.female, limit = 50, offset = 0)
        val api = CatalogAPI(QueueClient(mutableListOf(HTTPResult(200, body))), EndpointBackoffBox()).fetch(query)
        val adapter = ChaturbateAdapter(QueueClient(mutableListOf(HTTPResult(200, body))), EndpointBackoffBox())
            .fetchDirectory(query)
        assertEquals(api, adapter)
        assertEquals(listOf("sample_one"), adapter.rooms.map { it.username })
        assertEquals(listOf(SiteID.chaturbate), adapter.rooms.map { it.site })
    }

    @Test
    fun testDirectoryChallengeStatusAndBackoffMatchCatalogAPI() = runBlocking {
        val query = CatalogQuery()
        val challenge = "<html>Just a moment</html>".encodeToByteArray()
        assertSameFailure(query, HTTPResult(403, challenge), CatalogFailure.Challenge)
        assertSameFailure(query, HTTPResult(400, "{}".encodeToByteArray()), CatalogFailure.Status(400))

        val html = HTTPResult(200, "<html>busy</html>".encodeToByteArray())
        val apiBox = EndpointBackoffBox()
        val adapterBox = EndpointBackoffBox()
        assertFailsWith<CatalogFailure.Malformed> {
            CatalogAPI(QueueClient(mutableListOf(html)), apiBox).fetch(query)
        }
        assertFailsWith<CatalogFailure.Malformed> {
            ChaturbateAdapter(QueueClient(mutableListOf(html)), adapterBox).fetchDirectory(query)
        }
        assertFalse(apiBox.allows(SiteEndpoint.catalog))
        assertFalse(adapterBox.allows(SiteEndpoint.catalog))

        val held = EndpointBackoffBox()
        held.record(SiteEndpoint.catalog)
        val client = QueueClient(mutableListOf(html))
        val backedOff = assertFailsWith<EndpointBackedOff> {
            ChaturbateAdapter(client, held).fetchDirectory(query)
        }
        assertEquals(SiteEndpoint.catalog, backedOff.endpoint)
        assertTrue(client.requests.isEmpty())
    }

    @Test
    fun testPublicSourceMatchesStreamSourcingAndSkipsAjax() = runBlocking {
        val payload = context("public", source = source)
        val client = QueueClient(mutableListOf(HTTPResult(200, payload)))
        val room = Room(username = "sample_one", show = ShowState.publicShow)
        val ticket = ChaturbateAdapter(client, EndpointBackoffBox()).resolveStream(room)
        val probe = ChatContext.probe(200, payload)
        val step = StreamSourcing.first(probe) as StreamSourcing.Step.Use
        assertEquals(SourcedTicket(step.ticket, StreamOrigin.chatContext), ticket)
        assertEquals(1, client.requests.size)
        assertEquals(ChatContext.url("sample_one"), client.requests[0].url)
    }

    @Test
    fun testChallenge403503BackoffAndGeoMatchExistingPath() = runBlocking {
        val challenge = "<title>Just a moment...</title>".encodeToByteArray()
        val client = QueueClient(mutableListOf(HTTPResult(403, challenge), HTTPResult(403, challenge)))
        assertFailsWith<StreamDecodeError.Challenge> {
            ChaturbateAdapter(client, EndpointBackoffBox()).resolveStream(Room(username = "sample_one"))
        }
        assertEquals(2, client.requests.size)

        val denied = QueueClient(
            mutableListOf(
                HTTPResult(200, context("public")),
                HTTPResult(403, """{"detail":"no"}""".encodeToByteArray()),
            ),
        )
        val err403 = assertFailsWith<StreamDecodeError.Unavailable> {
            ChaturbateAdapter(denied, EndpointBackoffBox()).resolveStream(Room(username = "sample_one"))
        }
        assertEquals(403, err403.status)

        val busy = QueueClient(
            mutableListOf(
                HTTPResult(200, context("public")),
                HTTPResult(503, """{"ok":false}""".encodeToByteArray()),
            ),
        )
        val err503 = assertFailsWith<StreamDecodeError.Unavailable> {
            ChaturbateAdapter(busy, EndpointBackoffBox()).resolveStream(Room(username = "sample_one"))
        }
        assertEquals(503, err503.status)

        val box = EndpointBackoffBox()
        val html = HTTPResult(200, "<html>busy</html>".encodeToByteArray())
        val nonJSON = QueueClient(mutableListOf(HTTPResult(200, context("public")), html))
        val err200 = assertFailsWith<StreamDecodeError.Unavailable> {
            ChaturbateAdapter(nonJSON, box).resolveStream(Room(username = "sample_one"))
        }
        assertEquals(200, err200.status)
        assertFalse(box.allows(SiteEndpoint.streamURL))

        val held = QueueClient(mutableListOf(HTTPResult(200, context("public")), html))
        val before = held.requests.size
        val backedOff = assertFailsWith<EndpointBackedOff> {
            ChaturbateAdapter(held, box).resolveStream(Room(username = "sample_one"))
        }
        assertEquals(SiteEndpoint.streamURL, backedOff.endpoint)
        assertEquals(before + 1, held.requests.size)

        val ajaxPayload = """{"success":1,"url":"","room_status":"public"}""".encodeToByteArray()
        val geoClient = QueueClient(mutableListOf(HTTPResult(200, context("public")), HTTPResult(200, ajaxPayload)))
        val geo = ChaturbateAdapter(geoClient, EndpointBackoffBox()).resolveStream(Room(username = "sample_one"))
        val probe = ChatContext.probe(200, context("public"))
        val ajax = StreamDecoder.decode(200, ajaxPayload)
        assertEquals(StreamSourcing.settle(probe, ajax), geo.ticket)
        assertEquals(StreamFailure.geo, geo.ticket.failure)
        assertEquals(StreamOrigin.edge, geo.origin)
    }

    private suspend fun assertSameFailure(query: CatalogQuery, result: HTTPResult, expected: CatalogFailure) {
        val apiError = assertFailsWith<CatalogFailure> {
            CatalogAPI(QueueClient(mutableListOf(result)), EndpointBackoffBox()).fetch(query)
        }
        val adapterError = assertFailsWith<CatalogFailure> {
            ChaturbateAdapter(QueueClient(mutableListOf(result)), EndpointBackoffBox()).fetchDirectory(query)
        }
        assertEquals(expected, apiError)
        assertEquals(expected, adapterError)
    }

    private fun catalog(rooms: List<Triple<String, String, Int>>): ByteArray {
        val rows = rooms.joinToString(",") { (name, show, viewers) ->
            """{"username":"$name","current_show":"$show","num_users":$viewers}"""
        }
        return """{"rooms":[$rows],"total_count":${rooms.size}}""".encodeToByteArray()
    }

    private fun context(status: String, source: String? = null): ByteArray {
        val field = if (source != null) ""","hls_source":"$source"""" else ""
        return """{"room_status":"$status","broadcaster_username":"sample_one"$field}""".encodeToByteArray()
    }

    private class QueueClient(private val results: MutableList<HTTPResult>) : HTTPClient {
        val requests = mutableListOf<HTTPRequest>()

        override suspend fun send(request: HTTPRequest): HTTPResult {
            requests.add(request)
            return if (results.isEmpty()) HTTPResult(500, ByteArray(0)) else results.removeAt(0)
        }
    }
}
