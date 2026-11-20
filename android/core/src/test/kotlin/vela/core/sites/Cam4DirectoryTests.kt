package vela.core.sites

import java.net.URI
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import vela.core.base.model.Gender
import vela.core.base.model.ShowState
import vela.core.base.net.EndpointBackoffBox
import vela.core.base.net.EndpointBackedOff
import vela.core.base.net.HTTPClient
import vela.core.base.net.HTTPRequest
import vela.core.base.net.HTTPResult
import vela.core.base.site.CatalogFailure
import vela.core.base.site.CatalogQuery
import vela.core.stream.StreamFailure

/** Cam4 访客目录解析。输入与期望对齐 Swift `Cam4DirectoryTests`，外加适配器出口丢掉地区屏蔽房间。 */
class Cam4DirectoryTests {
    private val now = Instant.ofEpochSecond(1_700_000_000)

    @Test
    fun testURLShapeStartsAtPageOneAndClampsSize() {
        val first = Cam4Directory.url(offset = 0, limit = 100)
        val items = query(first)
        assertEquals("true", items["directoryJson"])
        assertEquals("true", items["online"])
        assertEquals("true", items["url"])
        assertEquals("1", items["page"])
        assertEquals("90", items["resultsPerPage"])
        assertEquals("www.cam4.com", first.host)
        assertEquals("/directoryCams", first.path)
        assertFalse(first.toString().contains("affiliate"))
        assertEquals("2", query(Cam4Directory.url(offset = 90, limit = 90))["page"])
        assertEquals("1", query(Cam4Directory.url(page = 0, resultsPerPage = 0))["page"])
        assertEquals("10", query(Cam4Directory.url(page = 3, resultsPerPage = 10))["resultsPerPage"])
    }

    @Test
    fun testFixtureMapsEveryFieldAndIgnoresDefaultImage() {
        val page = Cam4Directory.decode(fixture(), requestLimit = 50, requestOffset = 0, now = now, viewerRegions = emptySet())
        assertEquals(40, page.total)
        assertEquals(3, page.nextOffset)
        assertEquals(3, page.rooms.size)

        val full = page.rooms[0]
        assertEquals("sample_one", full.username)
        assertEquals("sample_one", full.displayName)
        assertEquals(SiteID.cam4, full.site)
        assertEquals("c4:sample_one", full.id)
        assertEquals(Gender.female, full.gender)
        assertEquals(24, full.age)
        assertEquals("US", full.location)
        assertEquals(ShowState.publicShow, full.show)
        assertEquals("https://preview.example/sample_one/master.m3u8?token=abc", full.stream?.toString())
        assertEquals("https://snapshots.example/sample_one.jpg?sign=s1", full.thumbnailURL?.toString())
        assertFalse(full.thumbnailURL?.toString()?.contains("default") ?: true)
        assertEquals(Instant.ofEpochSecond(1_700_003_600), full.thumbnailExpires)
        assertEquals(setOf("RU", "UA", "AF"), full.blockedRegions)
        assertEquals(128, full.viewers)
        assertEquals(listOf("talk", "dance"), full.tags)

        val priv = page.rooms[1]
        assertEquals(ShowState.privateShow, priv.show)
        assertNull(priv.stream)
        assertNull(priv.age)
        assertEquals("", priv.location)
        assertEquals(Gender.trans, priv.gender)
        assertEquals(7, priv.viewers)
        assertEquals(setOf("US"), priv.blockedRegions)
        assertEquals(now.plusSeconds(Cam4Directory.snapshotTTL), priv.thumbnailExpires)

        val bare = page.rooms[2]
        assertEquals("bare_min", bare.username)
        assertEquals(ShowState.publicShow, bare.show)
        assertNull(bare.gender)
        assertNull(bare.stream)
        assertNull(bare.thumbnailURL)
        assertNull(bare.thumbnailExpires)
        assertEquals(0, bare.viewers)
        assertTrue(bare.tags.isEmpty())
        assertTrue(bare.blockedRegions.isEmpty())
    }

    @Test
    fun testGenderWordsAndMillisecondValidtoFallsBackToTTL() {
        val body = """
            {"users":[
              {"username":"aa_male","gender":"male"},
              {"username":"aa_couple","gender":"couple"},
              {"username":"aa_trans","gender":"transgender"},
              {"username":"aa_other","gender":"unknown"},
              {"username":"aa_ms","snapshotImageLink":"https://snapshots.example/aa_ms.jpg","profileImageLink":"https://images.example/a.jpg?validto=1700003600000"}
            ],"totalCount":9}
        """.trimIndent()
        val page = Cam4Directory.decode(
            body.encodeToByteArray(),
            requestLimit = 10,
            requestOffset = 20,
            now = now,
            viewerRegions = emptySet(),
        )
        assertEquals(listOf(Gender.male, Gender.couple, Gender.trans, null, null), page.rooms.map { it.gender })
        assertEquals(25, page.nextOffset)
        assertEquals(now.plusSeconds(Cam4Directory.snapshotTTL), page.rooms[4].thumbnailExpires)
    }

    @Test
    fun testBlockedViewerStripsStreamButEmptyViewerDoesNot() {
        val blocked = Cam4Directory.decode(fixture(), 90, 0, now, setOf("RU"))
        assertEquals(ShowState.publicShow, blocked.rooms[0].show)
        assertNull(blocked.rooms[0].stream)
        assertEquals(ShowState.privateShow, blocked.rooms[1].show)
        val clear = Cam4Directory.decode(fixture(), 90, 0, now, setOf("DE"))
        assertNotNull(clear.rooms[0].stream)
        val empty = Cam4Directory.decode(fixture(), 90, 0, now, emptySet())
        assertNotNull(empty.rooms[0].stream)
    }

    @Test
    fun testRegionsParseCommaArrayCaseWhitespaceAndUnknown() {
        assertTrue(ISO3166.alpha2ToNumeric.size >= 240)
        val comma = Cam4Regions.parse(listOf(" 643, 804 , 004 , nope , USA , us "))
        assertEquals(setOf("RU", "UA", "AF", "US"), comma)
        val list = Cam4Regions.parse(listOf("643", " 804 ", "4", "xx", "de"))
        assertEquals(setOf("RU", "UA", "AF", "DE"), list)
        assertTrue(Cam4Regions.parse(null as List<String>?).isEmpty())
        assertTrue(Cam4Regions.isBlocked(setOf("643", "US"), setOf("ru")))
        assertTrue(Cam4Regions.isBlocked(setOf("RU"), setOf("643")))
        assertFalse(Cam4Regions.isBlocked(setOf("643"), setOf("US")))
        assertFalse(Cam4Regions.isBlocked(setOf("643"), emptySet()))
        assertFalse(Cam4Regions.isBlocked(emptySet(), setOf("RU")))
        assertEquals("AF", ISO3166.alpha2("004"))
        assertNull(ISO3166.alpha2("nope"))
    }

    @Test
    fun testHTMLAndNonJSONAreMalformedNotChallenge() {
        val html = assertFailsWith<CatalogFailure.Malformed> {
            Cam4Directory.decode("<html>Just a moment</html>".encodeToByteArray(), 10, 0, now, emptySet())
        }
        assertFalse(html.isChallenge)
        val empty = Cam4Directory.decode("[]".encodeToByteArray(), 10, 0, now, emptySet())
        assertTrue(empty.rooms.isEmpty())
        val broken = assertFailsWith<CatalogFailure.Malformed> {
            Cam4Directory.decode("{".encodeToByteArray(), 10, 0, now, emptySet())
        }
        assertFalse(broken.isChallenge)
    }

    @Test
    fun testAdapterDropsGeoBlockedRoomsButKeepsPageCursor() = runBlocking {
        val client = object : HTTPClient {
            override suspend fun send(request: HTTPRequest): HTTPResult = HTTPResult(200, fixture())
        }
        val page = Cam4Adapter(client, backoff = null, viewerRegions = setOf("RU")).fetchDirectory(CatalogQuery(limit = 90))
        assertEquals(listOf("c4:sample_two", "c4:bare_min"), page.rooms.map { it.id })
        assertEquals(40, page.total)
        assertEquals(3, page.nextOffset)
    }

    @Test
    fun testHTMLRegistersBackoffAndIsNotAChallenge() = runBlocking {
        val calls = AtomicInteger()
        val client = object : HTTPClient {
            override suspend fun send(request: HTTPRequest): HTTPResult {
                calls.incrementAndGet()
                return HTTPResult(200, "<html>Just a moment</html>".encodeToByteArray())
            }
        }
        val backoff = EndpointBackoffBox { Instant.ofEpochSecond(1_000) }
        val adapter = Cam4Adapter(client, backoff, viewerRegions = emptySet())
        val malformed = assertFailsWith<CatalogFailure.Malformed> {
            adapter.fetchDirectory(CatalogQuery())
        }
        assertFalse(malformed.isChallenge)
        assertFailsWith<EndpointBackedOff> { adapter.fetchDirectory(CatalogQuery()) }
        assertEquals(1, calls.get())
    }

    @Test
    fun testMasterFailureOnlyOnCam4() {
        val cb = ChaturbateAdapter(IdleClient(), backoff = null)
        val c4 = Cam4Adapter(IdleClient(), backoff = null, viewerRegions = emptySet())
        for (status in listOf(200, 301, 302, 400, 401)) {
            assertNull(c4.masterFailure(status), "$status")
            assertNull(cb.masterFailure(status), "$status")
        }
        for (status in listOf(403, 404, 500, 504, 599)) {
            assertEquals(StreamFailure.unavailable, c4.masterFailure(status), "$status")
            assertNull(cb.masterFailure(status), "$status")
        }
        assertEquals("https://chaturbate.com", cb.mediaHeaders(RoomKey.parsing("alice")!!)["Origin"])
        assertEquals("https://www.cam4.com/", c4.mediaHeaders(RoomKey.parsing("c4:alice")!!)["Referer"])
        assertEquals("https://www.cam4.com", c4.mediaHeaders(RoomKey.parsing("c4:alice")!!)["Origin"])
        assertNull(c4.challengeHome)
        assertEquals("https://chaturbate.com/", cb.challengeHome?.toString())
        assertTrue(cb.capabilities.contains(SiteCapabilities.challengePage))
        assertEquals(SiteCapabilities.strictMaster, c4.capabilities)
    }

    private fun fixture(): ByteArray {
        val stream = checkNotNull(javaClass.getResourceAsStream("/sites/cam4-directory.json"))
        return stream.use { it.readBytes() }
    }

    private fun query(url: URI): Map<String, String> {
        val raw = url.rawQuery ?: return emptyMap()
        return raw.split('&').mapNotNull { part ->
            val eq = part.indexOf('=')
            if (eq <= 0) null else part.substring(0, eq) to part.substring(eq + 1)
        }.toMap()
    }

    private class IdleClient : HTTPClient {
        override suspend fun send(request: HTTPRequest): HTTPResult = HTTPResult(500, ByteArray(0))
    }
}
