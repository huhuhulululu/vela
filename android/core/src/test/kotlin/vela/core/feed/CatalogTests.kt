package vela.core.feed

import java.net.URI
import java.net.URLDecoder
import java.time.Instant
import kotlinx.coroutines.runBlocking
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import vela.core.base.model.Gender
import vela.core.base.model.Region
import vela.core.base.net.EndpointBackoffBox
import vela.core.base.net.HTTPClient
import vela.core.base.net.HTTPRequest
import vela.core.base.net.HTTPResult
import vela.core.base.site.CatalogFailure
import vela.core.base.site.CatalogQuery

/** 站点房间列表接口：URL 构造、解码、开播时长与多页候选池。 */
class CatalogTests {
    @BeforeTest
    fun setUp() {
        EndpointBackoffBox.shared.reset()
    }

    private val now: Instant = Instant.ofEpochSecond(1_800_000_000)

    private fun values(url: String): Map<String, String> {
        val raw = URI(url).rawQuery ?: return emptyMap()
        if (raw.isEmpty()) return emptyMap()
        return raw.split('&').associate { part ->
            val idx = part.indexOf('=')
            val key = URLDecoder.decode(if (idx >= 0) part.substring(0, idx) else part, Charsets.UTF_8)
            val value = URLDecoder.decode(if (idx >= 0) part.substring(idx + 1) else "", Charsets.UTF_8)
            key to value
        }
    }

    private fun values(uri: URI): Map<String, String> = values(uri.toString())

    @Test
    fun testURLWithoutFilters() {
        val url = CatalogQuery(limit = 40, offset = 80).url()
        assertEquals("chaturbate.com", url.host)
        assertTrue(url.toString().startsWith("https://chaturbate.com/api/ts/roomlist/room-list/?"))
        assertEquals(mapOf("limit" to "40", "offset" to "80"), values(url))
    }

    @Test
    fun testURLWithAllFilters() {
        val query = CatalogQuery(gender = Gender.female, region = Region.asia, tag = "jazz", limit = 90, offset = 90)
        assertEquals(
            mapOf("limit" to "90", "offset" to "90", "genders" to "f", "regions" to "AS", "hashtags" to "jazz"),
            values(query.url()),
        )
    }

    @Test
    fun testURLClampsPagingAndNeverCarriesAffiliateParameters() {
        val url = CatalogQuery(gender = Gender.trans, limit = 900, offset = -4).url()
        val v = values(url)
        assertEquals("90", v["limit"])
        assertEquals("0", v["offset"])
        assertEquals("t", v["genders"])
        for (key in listOf("wm", "client_ip", "format", "gender", "region", "hd", "tag")) {
            assertNull(v[key])
        }
        assertFalse(url.toString().contains("affiliates"))
    }

    @Test
    fun testRegionSiteCodes() {
        val codes = Region.entries.map { values(CatalogQuery(region = it).url())["regions"] }
        assertEquals(listOf("AS", "ER", "NA", "SA", "O"), codes)
    }

    @Test
    fun testHashtagNormalization() {
        assertEquals(true, CatalogQuery(tag = " #Asian ").url().rawQuery?.contains("hashtags=asian"))
        assertEquals("big", values(CatalogQuery(tag = "big ass").url())["hashtags"])
        assertNull(values(CatalogQuery(tag = "  # ").url())["hashtags"])
        assertNull(values(CatalogQuery(tag = null).url())["hashtags"])
    }

    @Test
    fun testDecodeMapsRoomFields() {
        val start = now.epochSecond - 3660
        val page = RoomListDecoder.decode(
            payload(
                listOf(
                    room("alpha", gender = "f", users = 12_000, start = start, age = 25),
                    room("beta", gender = "t", show = "private", users = 3, start = null, age = 99, image = "http://images.example/b.jpg"),
                ),
                321,
            ),
            requestLimit = 90,
            requestOffset = 90,
            now = now,
        )
        assertEquals(321, page.total)
        assertEquals(92, page.nextOffset)
        val first = page.rooms.first()
        assertEquals("alpha", first.username)
        assertEquals("alpha", first.displayName)
        assertEquals(Gender.female, first.gender)
        assertEquals(vela.core.base.model.ShowState.publicShow, first.show)
        assertFalse(first.isHD)
        assertEquals(12_000, first.viewers)
        assertEquals(50, first.followers)
        assertEquals(listOf("a", "b"), first.tags)
        assertEquals(25, first.age)
        assertEquals(3660, first.secondsOnline)
        assertEquals("https://images.example/alpha.jpg", first.thumbnailURL?.toString())
        val second = page.rooms[1]
        assertEquals(Gender.trans, second.gender)
        assertEquals(vela.core.base.model.ShowState.privateShow, second.show)
        assertNull(second.age)
        assertNull(second.secondsOnline)
        assertEquals("https", second.thumbnailURL?.scheme)
    }

    @Test
    fun testPasswordRoomIsNotPlayable() {
        val page = RoomListDecoder.decode(
            payload(listOf(room("locked", password = true)), 1),
            requestLimit = 90,
            requestOffset = 0,
            now = now,
        )
        assertEquals(vela.core.base.model.ShowState.password, page.rooms.first().show)
    }

    @Test
    fun testSecondsOnlineConversion() {
        val n = now.epochSecond
        assertEquals(90, RoomListRoom.secondsOnline(n - 90, now))
        assertEquals(0, RoomListRoom.secondsOnline(n, now))
        assertNull(RoomListRoom.secondsOnline(n + 5, now))
        assertNull(RoomListRoom.secondsOnline(0, now))
        assertNull(RoomListRoom.secondsOnline(-3, now))
        assertNull(RoomListRoom.secondsOnline(null, now))
    }

    /** Swift `Int` 是 64 位。超过 32 位的 `start_timestamp` 仍要能换算已开播秒数。 */
    @Test
    fun testStartTimestampAboveInt32DecodesLikeSwiftInt() {
        val start = 3_000_000_000L
        val at = Instant.ofEpochSecond(start + 90)
        val page = RoomListDecoder.decode(
            payload(listOf(room("wide", start = start)), 1),
            requestLimit = 90,
            requestOffset = 0,
            now = at,
        )
        assertEquals(90, page.rooms.single().secondsOnline)
        assertEquals(90, RoomListRoom.secondsOnline(start, at))
    }

    @Test
    fun testNextOffsetIgnoresExtraRoom() {
        val rooms = (0 until 91).map { room("r$it") }
        val page = RoomListDecoder.decode(payload(rooms, 500), requestLimit = 90, requestOffset = 0, now = now)
        assertEquals(91, page.rooms.size)
        assertEquals(90, page.nextOffset)
    }

    @Test
    fun testDecodeRejectsGarbageAndChallenge() {
        val malformed = assertFailsWith<CatalogFailure> {
            RoomListDecoder.decode("""{"errors":{}}""".toByteArray(), requestLimit = 90, requestOffset = 0)
        }
        assertEquals(CatalogFailure.Malformed, malformed)
        val challenge = assertFailsWith<CatalogFailure> {
            RoomListDecoder.decode("<html>Just a moment</html>".toByteArray(), requestLimit = 90, requestOffset = 0)
        }
        assertEquals(CatalogFailure.Challenge, challenge)
    }

    @Test
    fun testFetchSendsBrowserHeadersAndMapsStatus() = runBlocking {
        val client = ScriptedClient(listOf(HTTPResult(200, payload(listOf(room("a")), 1))))
        CatalogAPI(client).fetch(CatalogQuery())
        assertEquals("XMLHttpRequest", client.requests.first().header("X-Requested-With"))
        val bad = CatalogAPI(ScriptedClient(listOf(HTTPResult(400, "{}".toByteArray()))))
        val error = assertFailsWith<CatalogFailure> { bad.fetch(CatalogQuery()) }
        assertEquals(CatalogFailure.Status(400), error)
    }

    @Test
    fun testFetchPagesMergesDedupesAndAdvances() = runBlocking {
        val p1 = (0 until 90).map { room("r$it") }
        val p2 = (89 until 179).map { room("r$it") }
        val p3 = (180 until 270).map { room("r$it") }
        val client = ScriptedClient(listOf(p1, p2, p3).map { HTTPResult(200, payload(it, 1000)) })
        val page = CatalogAPI(client).fetchPages(CatalogQuery(limit = 90, offset = 0), pages = 3)
        assertEquals(listOf("0", "90", "180"), client.requests.map { values(it.url)["offset"] })
        assertEquals(269, page.rooms.size)
        assertEquals(269, page.rooms.map { it.username }.toSet().size)
        assertEquals(270, page.nextOffset)
        assertEquals(1000, page.total)
    }

    @Test
    fun testFetchPagesStopsOnShortPageAndAtEnd() = runBlocking {
        val short = ScriptedClient(listOf(HTTPResult(200, payload((0 until 30).map { room("s$it") }, 30))))
        val a = CatalogAPI(short).fetchPages(CatalogQuery(limit = 90), pages = 3)
        assertEquals(1, short.requests.size)
        assertEquals(30, a.rooms.size)
        assertTrue(a.nextOffset >= a.total)

        val full = (0 until 90).map { room("f$it") }
        val end = ScriptedClient(listOf(HTTPResult(200, payload(full, 90))))
        val b = CatalogAPI(end).fetchPages(CatalogQuery(limit = 90), pages = 3)
        assertEquals(1, end.requests.size, "到底后不再请求")
        assertEquals(90, b.nextOffset)
    }

    @Test
    fun testFetchPagesFailsWhenAnyPageFails() = runBlocking {
        val full = (0 until 90).map { room("f$it") }
        val client = ScriptedClient(
            listOf(
                HTTPResult(200, payload(full, 1000)),
                HTTPResult(503, "{}".toByteArray()),
            ),
        )
        val error = assertFailsWith<CatalogFailure> {
            CatalogAPI(client).fetchPages(CatalogQuery(limit = 90), pages = 3)
        }
        assertEquals(CatalogFailure.Status(503), error)
    }

    private fun room(
        name: String,
        gender: String = "f",
        show: String = "public",
        users: Int = 10,
        start: Long? = null,
        age: Int? = null,
        image: String? = null,
        password: Boolean = false,
    ): String {
        val img = image ?: "http://images.example/$name.jpg"
        val startField = if (start != null) "\"start_timestamp\":$start," else ""
        val ageField = if (age != null) "\"display_age\":$age," else ""
        return """{"username":"$name","gender":"$gender","current_show":"$show","num_users":$users,"num_followers":50,"tags":["a","b"],"room_subject":"hello #a #b","location":"Desk","country":"","is_new":false,$startField${ageField}"has_password":$password,"label":"$show","img":"$img"}"""
    }

    private fun payload(rooms: List<String>, total: Int): ByteArray =
        """{"rooms":[${rooms.joinToString(",")}],"total_count":$total}""".toByteArray()

    private class ScriptedClient(script: List<HTTPResult>) : HTTPClient {
        private val pending = script.toMutableList()
        private val seen = mutableListOf<HTTPRequest>()
        val requests: List<HTTPRequest>
            get() = seen.toList()

        override suspend fun send(request: HTTPRequest): HTTPResult {
            seen.add(request)
            return if (pending.isEmpty()) HTTPResult(500, ByteArray(0)) else pending.removeAt(0)
        }
    }
}
