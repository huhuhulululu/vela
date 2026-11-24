package vela.core.sites

import java.net.URI
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import vela.core.base.model.Gender
import vela.core.base.model.Region
import vela.core.base.model.Room
import vela.core.base.model.ShowState
import vela.core.base.net.EndpointBackoffBox
import vela.core.base.net.EndpointBackedOff
import vela.core.base.net.HTTPClient
import vela.core.base.net.HTTPRequest
import vela.core.base.net.HTTPResult
import vela.core.base.net.SiteEndpoint
import vela.core.base.site.CatalogFailure
import vela.core.base.site.CatalogPage
import vela.core.base.site.CatalogQuery
import vela.core.base.site.FavoriteStatus
import vela.core.stream.StreamFailure
import vela.core.stream.StreamOrigin

/** Cam4 取流、索引缓存、地区屏蔽、收藏状态与退避。对齐 Swift `Cam4StreamTests`。 */
class Cam4StreamTests {
    private val hls = "https://preview.example/sample_one/master.m3u8?token=abc"

    @Test
    fun testFreshHintMakesNoRequest() = runBlocking {
        val script = ScriptClient(mutableListOf(page(listOf(user("sample_one", hls = hls)))))
        var now = Instant.ofEpochSecond(1_000)
        val adapter = Cam4Adapter(script, EndpointBackoffBox(), viewerRegions = emptySet(), clock = { now })
        val listed = adapter.fetchDirectory(CatalogQuery())
        val room = listed.rooms.first()
        val before = script.requests.size
        val ticket = adapter.resolveStream(room)
        assertEquals(before, script.requests.size)
        assertEquals(StreamOrigin.cam4Directory, ticket.origin)
        assertEquals(room.stream, ticket.ticket.url)
        assertNull(ticket.ticket.failure)
        val status = adapter.status(listOf(room.key), now)
        val online = status[room.key] as? FavoriteStatus.Online
        assertEquals("c4:sample_one", online?.room?.ref)
    }

    @Test
    fun testExpiredPageMissIsOfflineAndPrivateIsPrivate() = runBlocking {
        val script = ScriptClient(
            mutableListOf(
                page(listOf(user("sample_one", hls = hls), user("hidden_one", hls = hls, privateRoom = true))),
                page(listOf(user("other_name", hls = hls))),
            ),
        )
        var now = Instant.ofEpochSecond(1_000)
        val adapter = Cam4Adapter(script, EndpointBackoffBox(), viewerRegions = emptySet(), clock = { now })
        adapter.fetchDirectory(CatalogQuery(limit = 90, offset = 0))
        val room = Room(username = "sample_one", show = ShowState.publicShow, site = SiteID.cam4, stream = URI.create(hls))
        now = now.plusMillis(Cam4Adapter.streamTTLMillis + 1_000)
        val offline = adapter.resolveStream(room)
        assertEquals(StreamFailure.offline, offline.ticket.failure)
        assertEquals(2, script.requests.size)
        assertEquals("1", query(script.requests[1].url, "page"))

        val again = ScriptClient(mutableListOf(page(listOf(user("hidden_one", hls = hls, privateRoom = true)))))
        val fresh = Cam4Adapter(again, EndpointBackoffBox(), viewerRegions = emptySet(), clock = { Instant.ofEpochSecond(2_000) })
        fresh.fetchDirectory(CatalogQuery())
        val priv = fresh.resolveStream(Room(username = "hidden_one", site = SiteID.cam4))
        assertEquals(1, again.requests.size)
        assertEquals(StreamFailure.privateShow, priv.ticket.failure)
    }

    @Test
    fun testRefetchUsesThePageItWasSeenOn() = runBlocking {
        val script = ScriptClient(
            mutableListOf(
                page(listOf(user("sample_one", hls = hls))),
                page(listOf(user("sample_one", hls = hls))),
            ),
        )
        var now = Instant.ofEpochSecond(5_000)
        val adapter = Cam4Adapter(script, EndpointBackoffBox(), viewerRegions = emptySet(), clock = { now })
        adapter.fetchDirectory(CatalogQuery(limit = 90, offset = 90))
        now = now.plusMillis(Cam4Adapter.streamTTLMillis + 5_000)
        val ticket = adapter.resolveStream(Room(username = "sample_one", site = SiteID.cam4))
        assertNull(ticket.ticket.failure)
        assertEquals("2", query(script.requests[1].url, "page"))
    }

    @Test
    fun testGeoAndUnknownKeyAndCancellation() = runBlocking {
        val script = ScriptClient(
            mutableListOf(
                page(listOf(user("sample_one", hls = hls, blocked = listOf("643")))),
                page(listOf(user("other_name", hls = hls))),
            ),
        )
        val adapter = Cam4Adapter(script, EndpointBackoffBox(), viewerRegions = setOf("RU"), clock = { Instant.ofEpochSecond(3_000) })
        val listed = adapter.fetchDirectory(CatalogQuery())
        assertTrue(listed.rooms.isEmpty())
        val geo = adapter.resolveStream(Room.direct(RoomKey(SiteID.cam4, "sample_one")!!))
        assertEquals(StreamFailure.geo, geo.ticket.failure)
        assertEquals(1, script.requests.size)
        val missing = adapter.resolveStream(Room(username = "never_seen", site = SiteID.cam4))
        assertEquals(StreamFailure.offline, missing.ticket.failure)
        assertEquals("1", query(script.requests.last().url, "page"))

        val cancelling = Cam4Adapter(ScriptClient(error = CancellationException("cancelled")), backoff = null, viewerRegions = emptySet())
        assertFailsWith<CancellationException> {
            cancelling.resolveStream(Room(username = "sample_one", site = SiteID.cam4))
        }
        Unit
    }

    @Test
    fun testGeoBlockedRoomsStayOutOfTheDirectory() = runBlocking {
        val script = ScriptClient(
            mutableListOf(
                page(listOf(user("sample_one", hls = hls, blocked = listOf("643")), user("sample_two", hls = hls))),
            ),
        )
        val now = Instant.ofEpochSecond(4_000)
        val adapter = Cam4Adapter(script, backoff = null, viewerRegions = setOf("RU"), clock = { now })
        val listed = adapter.fetchDirectory(CatalogQuery())
        assertEquals(listOf("sample_two"), listed.rooms.map { it.username })
        assertEquals(100, listed.total)
        val key = RoomKey(SiteID.cam4, "sample_one")!!
        val status = adapter.status(listOf(key), now)
        assertEquals(FavoriteStatus.Restricted, status[key])
        val direct = adapter.resolveStream(Room.direct(key))
        assertEquals(StreamFailure.geo, direct.ticket.failure)
        assertEquals(1, script.requests.size)

        val open = ScriptClient(mutableListOf(page(listOf(user("sample_one", hls = hls, blocked = listOf("643"))))))
        val anywhere = Cam4Adapter(open, backoff = null, viewerRegions = emptySet())
        val all = anywhere.fetchDirectory(CatalogQuery())
        assertEquals(listOf("sample_one"), all.rooms.map { it.username })
    }

    @Test
    fun testStaleStatusIsOmittedAndBlockedIsRestricted() = runBlocking {
        val script = ScriptClient(mutableListOf(page(listOf(user("sample_one", hls = hls, blocked = listOf("840"))))))
        val now = Instant.ofEpochSecond(8_000)
        val adapter = Cam4Adapter(script, backoff = null, viewerRegions = setOf("US"), clock = { now })
        val listed = adapter.fetchDirectory(CatalogQuery())
        assertTrue(listed.rooms.isEmpty())
        val key = RoomKey(SiteID.cam4, "sample_one")!!
        val fresh = adapter.status(listOf(key), now)
        assertEquals(FavoriteStatus.Restricted, fresh[key])
        val stale = adapter.status(listOf(key), now.plusMillis(Cam4Adapter.statusTTLMillis + 1_000))
        assertNull(stale[key])
        val unknown = adapter.status(listOf(RoomKey.parsing("c4:other")!!), now)
        assertTrue(unknown.isEmpty())
    }

    @Test
    fun testThumbnailExpiresInsideOneMinute() {
        val adapter = Cam4Adapter(ScriptClient(), backoff = null, viewerRegions = emptySet())
        val url = URI.create("https://snapshots.example/a.jpg?sign=1")
        val now = Instant.ofEpochSecond(1_000)
        assertEquals(
            url,
            adapter.thumbnail(Room(username = "sample_one", thumbnailURL = url, site = SiteID.cam4, thumbnailExpires = now.plusSeconds(120)), now),
        )
        assertNull(
            adapter.thumbnail(Room(username = "sample_one", thumbnailURL = url, site = SiteID.cam4, thumbnailExpires = now.plusSeconds(30)), now),
        )
        assertNull(
            adapter.thumbnail(Room(username = "sample_one", thumbnailURL = url, site = SiteID.cam4, thumbnailExpires = now.minusSeconds(1)), now),
        )
        assertEquals(
            url,
            adapter.thumbnail(Room(username = "sample_one", thumbnailURL = url, site = SiteID.cam4, thumbnailExpires = null), now),
        )
    }

    @Test
    fun testHTMLBacksOffAndIsNotAChallenge() = runBlocking {
        val box = EndpointBackoffBox()
        val html = ScriptClient(mutableListOf(HTTPResult(200, "<html>Just a moment</html>".encodeToByteArray())))
        val adapter = Cam4Adapter(html, backoff = box, viewerRegions = emptySet())
        val failure = assertFailsWith<CatalogFailure.Malformed> {
            adapter.fetchDirectory(CatalogQuery())
        }
        assertFalse(failure.isChallenge)
        assertFalse(box.allows(SiteEndpoint.cam4Directory))
        val backedOff = assertFailsWith<EndpointBackedOff> {
            adapter.fetchDirectory(CatalogQuery())
        }
        assertEquals(SiteEndpoint.cam4Directory, backedOff.endpoint)
        assertEquals(1, html.requests.size)
    }

    @Test
    fun testNon2xxAndUnsupportedFiltersMakeNoExtraRequest() = runBlocking {
        val box = EndpointBackoffBox()
        val script = ScriptClient(mutableListOf(HTTPResult(502, "<html>bad gateway</html>".encodeToByteArray())))
        val adapter = Cam4Adapter(script, backoff = box, viewerRegions = emptySet())
        val status = assertFailsWith<CatalogFailure.Status> {
            adapter.fetchDirectory(CatalogQuery())
        }
        assertEquals(502, status.code)
        assertFalse(box.allows(SiteEndpoint.cam4Directory))

        val quiet = ScriptClient(
            mutableListOf(
                page(listOf(user("sample_one", hls = hls))),
                page(listOf(user("sample_one", hls = hls))),
            ),
        )
        val cam = Cam4Adapter(quiet, backoff = EndpointBackoffBox(), viewerRegions = emptySet())
        for (q in listOf(CatalogQuery(region = Region.asia), CatalogQuery(tag = "talk"), CatalogQuery(newCams = true))) {
            assertEquals(CatalogPage(rooms = emptyList(), total = 0, nextOffset = 0), cam.fetchDirectory(q))
        }
        assertTrue(quiet.requests.isEmpty())
        val male = cam.fetchDirectory(CatalogQuery(gender = Gender.male, keywords = "nope"))
        assertTrue(male.rooms.isEmpty())
        assertEquals(1, quiet.requests.size)
        val matched = cam.fetchDirectory(CatalogQuery(gender = Gender.female, keywords = "sample"))
        assertEquals(listOf("sample_one"), matched.rooms.map { it.username })
    }

    private fun user(
        name: String,
        hls: String? = null,
        privateRoom: Boolean = false,
        blocked: List<String> = emptyList(),
    ): String {
        val fields = mutableListOf(
            """"username":"$name"""",
            """"privateRoom":$privateRoom""",
            """"gender":"female"""",
            """"viewers":3""",
            """"snapshotImageLink":"https://snapshots.example/$name.jpg?sign=1"""",
        )
        if (hls != null) fields.add(""""hlsPreviewUrl":"$hls"""")
        if (blocked.isNotEmpty()) {
            fields.add(""""blockedRegions":[${blocked.joinToString(",") { "\"$it\"" }}]""")
        }
        return "{${fields.joinToString(",")}}"
    }

    private fun page(users: List<String>): HTTPResult =
        HTTPResult(200, """{"users":[${users.joinToString(",")}],"totalCount":100}""".encodeToByteArray())

    private fun query(url: String, name: String): String? {
        val raw = URI(url).rawQuery ?: return null
        return raw.split('&').firstNotNullOfOrNull { part ->
            val eq = part.indexOf('=')
            if (eq <= 0) null else if (part.substring(0, eq) == name) part.substring(eq + 1) else null
        }
    }

    private class ScriptClient(
        private val results: MutableList<HTTPResult> = mutableListOf(),
        private val error: Throwable? = null,
    ) : HTTPClient {
        val requests = mutableListOf<HTTPRequest>()

        override suspend fun send(request: HTTPRequest): HTTPResult {
            requests.add(request)
            error?.let { throw it }
            return if (results.isEmpty()) HTTPResult(500, ByteArray(0)) else results.removeAt(0)
        }
    }
}
