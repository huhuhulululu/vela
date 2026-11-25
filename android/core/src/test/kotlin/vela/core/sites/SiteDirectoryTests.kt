package vela.core.sites

import java.net.URI
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import vela.core.base.model.Gender
import vela.core.base.model.Room
import vela.core.base.net.HTTPClient
import vela.core.base.net.HTTPRequest
import vela.core.base.net.HTTPResult
import vela.core.base.site.CatalogQuery
import vela.core.feed.CatalogAPI

/** 混合目录按站点续页。输入与期望对齐 Swift `SiteDirectoryTests`。 */
class SiteDirectoryTests {
    @Test
    fun testChaturbateModeIsTheExistingCatalogPath() = runBlocking {
        val body = cbBody(listOf("sample_one", "sample_two"), total = 9)
        val direct = HostClient(cb = mutableListOf(body))
        val staged = HostClient(cb = mutableListOf(body))
        val query = CatalogQuery(gender = Gender.female, limit = 2, offset = 4)
        val expected = CatalogAPI(direct, backoff = null).fetch(query)
        val page = directory(SiteMode.chaturbate, staged).fetch(query)
        assertEquals(expected, page)
        assertEquals(direct.urls, staged.urls)
        assertTrue(staged.urls.all { URI(it).host == "chaturbate.com" })
    }

    @Test
    fun testCam4ModeAsksOnlyCam4() = runBlocking {
        val client = HostClient(c4 = mutableListOf(c4Body(listOf("sample_one"), total = 1)))
        val page = directory(SiteMode.cam4, client).fetch(CatalogQuery(limit = 30))
        assertEquals(listOf("c4:sample_one"), page.rooms.map { it.id })
        assertEquals(SiteID.cam4, page.rooms.first().site)
        assertEquals(listOf("www.cam4.com"), client.urls.map { URI(it).host })
    }

    @Test
    fun testMixedNextPageResumesEachSiteFromItsOwnCursor() = runBlocking {
        val client = HostClient(
            cb = mutableListOf(
                cbBody(listOf("cb_a", "cb_b"), total = 10),
                cbBody(listOf("cb_c", "cb_d"), total = 10),
            ),
            c4 = mutableListOf(
                c4Body(listOf("c4_a", "c4_b"), total = 6),
                c4Body(listOf("c4_c", "c4_d"), total = 6),
            ),
        )
        val dir = directory(SiteMode.mixed, client)
        val first = dir.fetch(CatalogQuery(limit = 2, offset = 0))
        assertEquals(listOf("cb_a", "c4:c4_a", "cb_b", "c4:c4_b"), first.rooms.map { it.id })
        assertEquals(4, first.nextOffset)
        assertEquals(16, first.total)

        val second = dir.fetch(CatalogQuery(limit = 2, offset = first.nextOffset))
        assertEquals(listOf("cb_c", "c4:c4_c", "cb_d", "c4:c4_d"), second.rooms.map { it.id })
        assertEquals(8, second.nextOffset)
        val cbOffsets = client.urls.filter { URI(it).host == "chaturbate.com" }.map { query(it, "offset") }
        val c4Pages = client.urls.filter { URI(it).host == "www.cam4.com" }.map { query(it, "page") }
        assertEquals(listOf("0", "2"), cbOffsets)
        assertEquals(listOf("1", "2"), c4Pages)
    }

    @Test
    fun testMixedFetchPagesWalksTheRememberedCursors() = runBlocking {
        val client = HostClient(
            cb = mutableListOf(
                cbBody(listOf("cb_a", "cb_b"), total = 10),
                cbBody(listOf("cb_c", "cb_d"), total = 10),
            ),
            c4 = mutableListOf(
                c4Body(listOf("c4_a", "c4_b"), total = 6),
                c4Body(listOf("c4_c", "c4_d"), total = 6),
            ),
        )
        val merged = directory(SiteMode.mixed, client).fetchPages(CatalogQuery(limit = 2), pages = 2)
        assertEquals(8, merged.rooms.size)
        assertEquals(8, merged.nextOffset)
        assertEquals(setOf(SiteID.chaturbate, SiteID.cam4), merged.rooms.map { it.site }.toSet())
    }

    @Test
    fun testMixedUnknownOffsetRestartsInsteadOfThrowing() = runBlocking {
        val client = HostClient(
            cb = mutableListOf(cbBody(listOf("cb_a"), total = 1)),
            c4 = mutableListOf(c4Body(listOf("c4_a"), total = 1)),
        )
        val page = directory(SiteMode.mixed, client).fetch(CatalogQuery(limit = 2, offset = 37))
        assertEquals(setOf("cb_a", "c4:c4_a"), page.rooms.map { it.id }.toSet())
    }

    @Test
    fun testMixedOneSiteDownStillServesTheOther() = runBlocking {
        val client = HostClient(
            cb = mutableListOf(cbBody(listOf("cb_a"), total = 1)),
            c4 = mutableListOf(),
            c4Status = 504,
        )
        val dir = directory(SiteMode.mixed, client)
        val page = dir.fetch(CatalogQuery(limit = 2))
        assertEquals(listOf("cb_a"), page.rooms.map { it.id })
        assertEquals(listOf(SiteID.cam4), dir.lastFailedSites)
    }

    @Test
    fun testUsableThumbnailDropsSoonToExpireLinks() {
        val url = URI.create("https://images.example/a.jpg")
        val now = Instant.ofEpochSecond(1_000)
        assertEquals(url, Room(username = "cb_a", thumbnailURL = url).usableThumbnail(now))
        val fresh = Room(
            username = "c4_a",
            thumbnailURL = url,
            site = SiteID.cam4,
            thumbnailExpires = now.plusSeconds(600),
        )
        assertEquals(url, fresh.usableThumbnail(now))
        val stale = Room(
            username = "c4_a",
            thumbnailURL = url,
            site = SiteID.cam4,
            thumbnailExpires = now.plusSeconds(30),
        )
        assertNull(stale.usableThumbnail(now))
    }

    @Test
    fun testSiteOfRef() {
        assertEquals(SiteID.chaturbate, RoomKey.siteOfRef("alice"))
        assertEquals(SiteID.cam4, RoomKey.siteOfRef("c4:sample_one"))
        assertEquals(SiteID.cam4, RoomKey.siteOfRef("C4:Sample_One"))
        assertEquals(SiteID.chaturbate, RoomKey.siteOfRef("cb:alice"))
        assertEquals(SiteID.chaturbate, RoomKey.siteOfRef("vela-perf-room"))
        assertEquals(SiteID.cam4, RoomKey.siteOfRef(" c4:alice "), "前导空白应先裁剪再识别站点")
        assertEquals(SiteID.cam4, RoomKey.siteOfRef("\tC4:bob\n"))
        assertEquals(listOf(" c4:alice "), SiteMode.cam4.filterFavorites(listOf(" c4:alice ", "alice")))
    }

    @Test
    fun testMixedReloadFailureKeepsExistingPaginationCursors() = runBlocking {
        val client = HostClient.withResults(
            cbResults = mutableListOf(
                HTTPResult(200, cbBody(listOf("cb_1", "cb_2"), total = 20)),
                HTTPResult(503, "{}".encodeToByteArray()),
                HTTPResult(200, cbBody(listOf("cb_3", "cb_4"), total = 20)),
            ),
            c4Results = mutableListOf(
                HTTPResult(200, c4Body(listOf("c4_1", "c4_2"), total = 20)),
                HTTPResult(503, "{}".encodeToByteArray()),
                HTTPResult(200, c4Body(listOf("c4_3", "c4_4"), total = 20)),
            ),
        )
        val dir = directory(SiteMode.mixed, client)
        val page1 = dir.fetch(CatalogQuery(limit = 2, offset = 0))
        assertEquals(4, page1.nextOffset)

        try {
            dir.fetch(CatalogQuery(limit = 2, offset = 0))
            kotlin.test.fail("模拟的重载应失败")
        } catch (_: Throwable) {
            // 预期
        }

        val feed = vela.core.feed.FeedStore()
        feed.replace(page1)
        val page2 = dir.fetch(CatalogQuery(limit = 2, offset = page1.nextOffset))
        assertEquals(8, page2.nextOffset, "重载失败后继续翻页应沿原游标前进")
        assertEquals(listOf("cb_3", "c4:c4_3", "cb_4", "c4:c4_4"), page2.rooms.map { it.id })
        feed.append(page2, offset = page1.nextOffset)
        kotlin.test.assertFalse(feed.reachedEnd)

        val cbOffsets = client.urls.filter { URI(it).host == "chaturbate.com" }.map { query(it, "offset") }
        val c4Pages = client.urls.filter { URI(it).host == "www.cam4.com" }.map { query(it, "page") }
        assertEquals(listOf("0", "0", "2"), cbOffsets)
        assertEquals(listOf("1", "1", "2"), c4Pages)
    }

    @Test
    fun testTargetedTagQueryDoesNotClearMixedRegularCursors() = runBlocking {
        val client = HostClient(
            cb = mutableListOf(
                cbBody(listOf("cb_1", "cb_2"), total = 20),
                cbBody(listOf("cb_3", "cb_4"), total = 20),
                cbBody(listOf("cb_5", "cb_6"), total = 20),
                cbBody(listOf("cb_t1"), total = 5),
                cbBody(listOf("cb_t2"), total = 5),
                cbBody(listOf("cb_7", "cb_8"), total = 20),
            ),
            c4 = mutableListOf(
                c4Body(listOf("c4_1", "c4_2"), total = 20),
                c4Body(listOf("c4_3", "c4_4"), total = 20),
                c4Body(listOf("c4_5", "c4_6"), total = 20),
                c4Body(listOf("c4_7", "c4_8"), total = 20),
            ),
        )
        val dir = directory(SiteMode.mixed, client)
        val regular = dir.fetchPages(CatalogQuery(limit = 2, offset = 0), pages = 3)
        assertEquals(12, regular.nextOffset)

        dir.fetchPages(CatalogQuery(tag = "asian", limit = 90, offset = 0), pages = 1)
        dir.fetchPages(CatalogQuery(tag = "latina", limit = 90, offset = 0), pages = 1)

        val feed = vela.core.feed.FeedStore()
        feed.replace(regular)
        val page4 = dir.fetch(CatalogQuery(limit = 2, offset = regular.nextOffset))
        assertEquals(16, page4.nextOffset)
        assertEquals(listOf("cb_7", "c4:c4_7", "cb_8", "c4:c4_8"), page4.rooms.map { it.id })
        feed.append(page4, offset = regular.nextOffset)
        kotlin.test.assertFalse(feed.reachedEnd)

        val cbRegularOffsets = client.urls
            .filter { URI(it).host == "chaturbate.com" && query(it, "hashtags") == null }
            .map { query(it, "offset") }
        val c4Pages = client.urls
            .filter { URI(it).host == "www.cam4.com" }
            .map { query(it, "page") }
        assertEquals(listOf("0", "2", "4", "6"), cbRegularOffsets)
        assertEquals(listOf("1", "2", "3", "4"), c4Pages)
    }

    private fun directory(mode: SiteMode, client: HostClient): SiteDirectory {
        val registry = SiteRegistry(
            listOf(
                ChaturbateAdapter(client, backoff = null),
                Cam4Adapter(client, backoff = null, viewerRegions = emptySet()),
            ),
        )
        return SiteDirectory(mode, registry, CatalogAPI(client, backoff = null))
    }

    private fun cbBody(names: List<String>, total: Int): ByteArray {
        val rooms = names.joinToString(",") { """{"username":"$it","current_show":"public","num_users":1}""" }
        return """{"rooms":[$rooms],"total_count":$total}""".encodeToByteArray()
    }

    private fun c4Body(names: List<String>, total: Int): ByteArray {
        val users = names.joinToString(",") {
            """{"username":"$it","hlsPreviewUrl":"https://media.example/$it/master.m3u8"}"""
        }
        return """{"users":[$users],"totalCount":$total}""".encodeToByteArray()
    }

    private fun query(url: String, name: String): String? {
        val raw = URI(url).rawQuery ?: return null
        return raw.split('&').firstNotNullOfOrNull { part ->
            val eq = part.indexOf('=')
            if (eq <= 0) return@firstNotNullOfOrNull null
            if (part.substring(0, eq) != name) return@firstNotNullOfOrNull null
            part.substring(eq + 1)
        }
    }
}

private class HostClient private constructor(
    private val cb: MutableList<HTTPResult>,
    private val c4: MutableList<HTTPResult>,
    private val c4Status: Int,
    @Suppress("UNUSED_PARAMETER") raw: Unit,
) : HTTPClient {
    constructor(
        cb: MutableList<ByteArray> = mutableListOf(),
        c4: MutableList<ByteArray> = mutableListOf(),
        c4Status: Int = 200,
    ) : this(
        cb = cb.mapTo(ArrayList()) { HTTPResult(200, it) },
        c4 = c4.mapTo(ArrayList()) { HTTPResult(200, it) },
        c4Status = c4Status,
        raw = Unit,
    )

    companion object {
        fun withResults(cbResults: MutableList<HTTPResult>, c4Results: MutableList<HTTPResult>): HostClient =
            HostClient(cb = cbResults, c4 = c4Results, c4Status = 200, raw = Unit)
    }

    private val lock = Any()
    val urls: List<String>
        get() = synchronized(lock) { recorded.toList() }
    private val recorded = ArrayList<String>()

    override suspend fun send(request: HTTPRequest): HTTPResult = synchronized(lock) {
        val url = request.url
        recorded.add(url)
        val host = URI(url).host
        if (host == "www.cam4.com") {
            if (c4Status != 200 || c4.isEmpty()) {
                val status = if (c4Status == 200) 404 else c4Status
                return HTTPResult(status, "{}".encodeToByteArray())
            }
            return c4.removeAt(0)
        }
        if (cb.isEmpty()) return HTTPResult(404, "{}".encodeToByteArray())
        cb.removeAt(0)
    }
}
