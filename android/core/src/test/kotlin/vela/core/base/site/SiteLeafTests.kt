package vela.core.base.site

import java.net.URI
import java.net.URLDecoder
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import vela.core.base.model.Gender
import vela.core.base.model.Region
import vela.core.base.model.ShowState

class SiteLeafTests {
    private val now: Instant = Instant.ofEpochSecond(10_000)

    private fun values(uri: URI): Map<String, String> {
        val raw = uri.rawQuery ?: return emptyMap()
        if (raw.isEmpty()) return emptyMap()
        return raw.split('&').associate { part ->
            val idx = part.indexOf('=')
            val key = URLDecoder.decode(if (idx >= 0) part.substring(0, idx) else part, Charsets.UTF_8)
            val value = URLDecoder.decode(if (idx >= 0) part.substring(idx + 1) else "", Charsets.UTF_8)
            key to value
        }
    }

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
    fun keywordsAndNewCams() {
        assertEquals("a b", SiteSearchText.normalized("  a \n b  "))
        assertNull(SiteSearchText.normalized(" \t "))
        assertNull(SiteSearchText.normalized(null))
        assertEquals(SiteSearchText.maxLength, SiteSearchText.normalized("x".repeat(100))?.length)
        assertEquals(64, SiteSearchText.maxLength)
        val search = CatalogQuery(limit = 90, offset = 0, keywords = "big  smile").url()
        assertTrue(search.toString().startsWith("https://chaturbate.com/api/ts/roomlist/room-list/?"))
        assertEquals(mapOf("limit" to "90", "offset" to "0", "keywords" to "big smile"), values(search))
        assertNull(values(CatalogQuery(keywords = "   ").url())["keywords"])
        assertNull(values(CatalogQuery().url())["keywords"])
        val fresh = CatalogQuery(
            gender = Gender.female,
            region = Region.asia,
            tag = "#Jazz",
            limit = 90,
            offset = 180,
            newCams = true,
        )
        assertEquals(
            mapOf(
                "limit" to "90",
                "offset" to "180",
                "genders" to "f",
                "regions" to "AS",
                "hashtags" to "jazz",
                "new_cams" to "true",
            ),
            values(fresh.url()),
        )
        assertNull(values(CatalogQuery(limit = 40).url())["new_cams"])
        assertEquals("1", values(CatalogQuery(limit = 0, offset = 0).url())["limit"])
    }

    @Test
    fun testDecodeOnlinePublicRoom() {
        val data = """
            {"room_status":"public","num_viewers":321,"room_title":"hi","broadcaster_username":"Alpha",
             "broadcaster_gender":"female","age":24,"start_timestamp":1000}
        """.trimIndent().encodeToByteArray()
        val status = ChatContext.decode(200, data, now)
        val room = status.room
        assertNotNull(room)
        assertEquals("Alpha", room.username)
        assertEquals(321, room.viewers)
        assertEquals("hi", room.subject)
        assertEquals(Gender.female, room.gender)
        assertEquals(24, room.age)
        assertEquals(ShowState.publicShow, room.show)
        assertEquals(9_000, room.secondsOnline)
        assertEquals("thumb.live.mmcdn.com", room.thumbnailURL?.host)
    }

    @Test
    fun testDecodeNotPlayableAndMissing() {
        for (state in listOf("offline", "private", "group", "away", "hidden")) {
            val data = """{"room_status":"$state","num_viewers":0,"broadcaster_username":"x"}""".encodeToByteArray()
            assertEquals(FavoriteStatus.Offline, ChatContext.decode(200, data, now), state)
        }
        assertEquals(FavoriteStatus.NotFound, ChatContext.decode(404, ByteArray(0), now))
        val deleted = """{"status":403,"detail":"Room is deleted","code":"access-denied"}""".encodeToByteArray()
        assertEquals(FavoriteStatus.NotFound, ChatContext.decode(403, deleted, now))
        val password = """{"status":401,"detail":"This room requires a password."}""".encodeToByteArray()
        assertEquals(FavoriteStatus.Offline, ChatContext.decode(401, password, now))
    }

    @Test
    fun testDecodeChallengeAndFailures() {
        val page = "<html><title>Just a moment...</title></html>".encodeToByteArray()
        for (status in listOf(200, 403, 503)) {
            val error = assertFailsWith<CatalogFailure> { ChatContext.decode(status, page, now) }
            assertEquals(CatalogFailure.Challenge, error)
        }
        val denied = assertFailsWith<CatalogFailure> {
            ChatContext.decode(403, "denied".encodeToByteArray(), now)
        }
        assertEquals(CatalogFailure.Challenge, denied)
        val status = assertFailsWith<CatalogFailure> { ChatContext.decode(500, ByteArray(0), now) }
        assertEquals(CatalogFailure.Status(500), status)
        val malformed = assertFailsWith<CatalogFailure> { ChatContext.decode(200, "[1]".encodeToByteArray(), now) }
        assertEquals(CatalogFailure.Malformed, malformed)
        assertTrue(CatalogFailure.Challenge.isChallenge)
        assertFalse(CatalogFailure.Malformed.isChallenge)
        assertFalse(CatalogFailure.Status(403).isChallenge)
    }

    @Test
    fun hlsSourceDistinguishesRestricted() {
        fun context(fields: String) =
            """{"room_status":"public","num_viewers":3,"broadcaster_username":"geo_room"$fields}""".encodeToByteArray()
        assertEquals(FavoriteStatus.Restricted, ChatContext.decode(200, context(",\"hls_source\":\"\""), now))
        assertEquals(FavoriteStatus.Restricted, ChatContext.decode(200, context(",\"hls_source\":\"  \""), now))
        assertNotNull(ChatContext.decode(200, context(",\"hls_source\":\"https://edge.example/a.m3u8\""), now).room)
        assertNotNull(ChatContext.decode(200, context(""), now).room)
        assertNotNull(ChatContext.decode(200, context(",\"hls_source\":null"), now).room)
        val away = """{"room_status":"away","broadcaster_username":"x","hls_source":""}""".encodeToByteArray()
        assertEquals(FavoriteStatus.Offline, ChatContext.decode(200, away, now))
        assertNull(FavoriteStatus.Restricted.room)
        val numericDetail = """{"detail":1}""".encodeToByteArray()
        val error = assertFailsWith<CatalogFailure> { ChatContext.decode(403, numericDetail, now) }
        assertEquals(CatalogFailure.Challenge, error)
    }

    @Test
    fun testRequestShape() {
        val request = ChatContext.request("alpha_1")
        assertEquals("https://chaturbate.com/api/chatvideocontext/alpha_1/", request.url)
        assertEquals("GET", request.method)
        assertEquals("XMLHttpRequest", request.header("X-Requested-With"))
        assertEquals("https://chaturbate.com/alpha_1/", request.header("Referer"))
        assertEquals("https://chaturbate.com", request.header("Origin"))
        assertEquals(
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.2 Safari/605.1.15",
            request.header("User-Agent"),
        )
        assertTrue(request.timeoutSeconds <= 10.0)
        assertEquals(8.0, request.timeoutSeconds)
        assertNull(request.header("Cookie"))
        assertEquals(setOf(401, 404), ChatContext.definitiveStatuses)
    }

    @Test
    fun futureStartClampsToZeroAndStringAgeDecodes() {
        val data = """
            {"room_status":"public","broadcaster_username":"z","age":"24","start_timestamp":20000}
        """.trimIndent().encodeToByteArray()
        val room = ChatContext.decode(200, data, now).room
        assertNotNull(room)
        assertEquals(24, room.age)
        assertEquals(0, room.secondsOnline)
        assertEquals(ShowState.publicShow, room.show)
    }
}
