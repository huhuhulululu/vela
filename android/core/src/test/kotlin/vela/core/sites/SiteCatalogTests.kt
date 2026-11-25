package vela.core.sites

import java.net.URI
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.runBlocking
import vela.core.base.model.Room
import vela.core.base.model.ShowState
import vela.core.base.net.HTTPClient
import vela.core.base.net.HTTPRequest
import vela.core.base.net.HTTPResult
import vela.core.base.site.CatalogPage
import vela.core.base.site.CatalogQuery
import vela.core.base.site.FavoriteStatus
import vela.core.stream.SourcedTicket
import vela.core.stream.StreamFailure
import vela.core.stream.StreamOrigin
import vela.core.stream.StreamTicket

/** 混合目录合并。输入与期望对齐 Swift `SiteCatalogTests`。 */
class SiteCatalogTests {
    @Test
    fun testSingleSiteMatchesDirectFetch() = runBlocking {
        val body = """{"rooms":[{"username":"sample_one","current_show":"public","num_users":2}],"total_count":8}"""
            .encodeToByteArray()
        val result = HTTPResult(status = 200, data = body)
        val directClient = OneShot(result)
        val mixedClient = OneShot(result)
        val query = CatalogQuery(limit = 30, offset = 0)
        val direct = ChaturbateAdapter(directClient, backoff = null).fetchDirectory(query)
        val registry = SiteRegistry(listOf(ChaturbateAdapter(mixedClient, backoff = null)))
        val fetched = SiteCatalog.fetch(
            mode = SiteMode.chaturbate,
            query = query,
            cursor = MixedCursor(listOf(SiteID.chaturbate)),
            registry = registry,
        )
        assertEquals(direct, fetched.page)
        assertTrue(fetched.failures.isEmpty())
        assertEquals(direct.total, fetched.cursor.totals[SiteID.chaturbate])
        assertEquals(direct.nextOffset, fetched.cursor.offsets[SiteID.chaturbate])
    }

    @Test
    fun testMixedInterleavesDedupesSumsAndRecordsOneFailure() = runBlocking {
        val cb = FakeAdapter(
            site = SiteID.chaturbate,
            rooms = listOf(
                Room(username = "alice", show = ShowState.publicShow),
                Room(username = "bella", show = ShowState.publicShow),
            ),
            total = 10,
            step = 2,
        )
        val c4 = FakeAdapter(
            site = SiteID.cam4,
            rooms = listOf(
                Room(username = "alice", show = ShowState.publicShow),
                Room(username = "alice", show = ShowState.publicShow, site = SiteID.cam4),
            ),
            total = 4,
            step = 2,
        )
        val fetched = SiteCatalog.fetch(
            mode = SiteMode.mixed,
            query = CatalogQuery(limit = 2),
            cursor = MixedCursor(listOf(SiteID.chaturbate, SiteID.cam4)),
            registry = SiteRegistry(listOf(cb, c4)),
        )
        assertEquals(listOf("alice", "bella", "c4:alice"), fetched.page.rooms.map { it.id })
        assertEquals(14, fetched.page.total)
        assertEquals(4, fetched.page.nextOffset)
        assertFalse(fetched.cursor.isFinished)

        val failing = FakeAdapter(
            site = SiteID.cam4,
            rooms = emptyList(),
            total = 0,
            step = 0,
            error = SiteError.Unsupported(SiteID.cam4),
        )
        val partial = SiteCatalog.fetch(
            mode = SiteMode.mixed,
            query = CatalogQuery(),
            cursor = MixedCursor(listOf(SiteID.chaturbate, SiteID.cam4)),
            registry = SiteRegistry(listOf(cb, failing)),
        )
        assertEquals(listOf("alice", "bella"), partial.page.rooms.map { it.id })
        assertEquals(listOf(SiteID.cam4), partial.failures.keys.sortedBy { it.wire })
        assertEquals(SiteError.Unsupported(SiteID.cam4), partial.failures[SiteID.cam4])

        try {
            SiteCatalog.fetch(
                mode = SiteMode.mixed,
                query = CatalogQuery(),
                cursor = MixedCursor(listOf(SiteID.chaturbate, SiteID.cam4)),
                registry = SiteRegistry(
                    listOf(
                        FakeAdapter(
                            site = SiteID.chaturbate,
                            rooms = emptyList(),
                            total = 0,
                            step = 0,
                            error = SiteError.Unsupported(SiteID.chaturbate),
                        ),
                        failing,
                    ),
                ),
            )
            fail("全失败应抛第一站")
        } catch (error: SiteError) {
            assertEquals(SiteError.Unsupported(SiteID.chaturbate), error)
        }
    }

    @Test
    fun testUnsupportedFilterDoesNotRequestCam4() = runBlocking {
        val cb = FakeAdapter(
            site = SiteID.chaturbate,
            rooms = listOf(Room(username = "alice", show = ShowState.publicShow)),
            total = 1,
            step = 1,
        )
        val c4 = FakeAdapter(
            site = SiteID.cam4,
            rooms = listOf(Room(username = "zz_cam", show = ShowState.publicShow, site = SiteID.cam4)),
            total = 1,
            step = 1,
            rejectFilters = true,
        )
        val fetched = SiteCatalog.fetch(
            mode = SiteMode.mixed,
            query = CatalogQuery(tag = "talk"),
            cursor = MixedCursor(listOf(SiteID.chaturbate, SiteID.cam4)),
            registry = SiteRegistry(listOf(cb, c4)),
        )
        assertEquals(1, cb.calls)
        assertEquals(0, c4.calls, "混合模式下不支持该查询的站点直接跳过，不计入成功")
        assertEquals(0, c4.networkCalls)
        assertEquals(listOf("alice"), fetched.page.rooms.map { it.id })
        assertTrue(fetched.cursor.ended.contains(SiteID.cam4))
    }

    @Test
    fun testMixedUnsupportedFilterPropagatesSupportedSiteError() = runBlocking {
        val queries = listOf(
            CatalogQuery(region = vela.core.base.model.Region.asia),
            CatalogQuery(tag = "talk"),
            CatalogQuery(newCams = true),
        )
        for (query in queries) {
            val cb = FakeAdapter(
                site = SiteID.chaturbate,
                rooms = emptyList(),
                total = 0,
                step = 0,
                error = vela.core.base.site.CatalogFailure.Challenge,
            )
            val c4 = FakeAdapter(
                site = SiteID.cam4,
                rooms = emptyList(),
                total = 0,
                step = 0,
                rejectFilters = true,
            )
            try {
                SiteCatalog.fetch(
                    mode = SiteMode.mixed,
                    query = query,
                    cursor = MixedCursor(listOf(SiteID.chaturbate, SiteID.cam4)),
                    registry = SiteRegistry(listOf(cb, c4)),
                )
                fail("应抛出 Chaturbate 的质询错误: $query")
            } catch (error: vela.core.base.site.CatalogFailure) {
                assertEquals(vela.core.base.site.CatalogFailure.Challenge, error)
            }
            assertEquals(0, c4.calls)
        }

        val c4Only = FakeAdapter(
            site = SiteID.cam4,
            rooms = emptyList(),
            total = 0,
            step = 0,
            rejectFilters = true,
        )
        val empty = SiteCatalog.fetch(
            mode = SiteMode.cam4,
            query = CatalogQuery(region = vela.core.base.model.Region.asia),
            cursor = MixedCursor(listOf(SiteID.cam4)),
            registry = SiteRegistry(listOf(c4Only)),
        )
        assertTrue(empty.page.rooms.isEmpty())
        assertTrue(empty.failures.isEmpty())
    }

    @Test
    fun testMixedTotalDoesNotEndEarlyWhenOneSiteShrinksOrFails() = runBlocking {
        val cbA = ScriptedAdapter(
            site = SiteID.chaturbate,
            pages = mutableListOf(
                CatalogPage(rooms = listOf(Room(username = "cb_1", show = ShowState.publicShow)), total = 500, nextOffset = 460),
            ),
        )
        val c4A = ScriptedAdapter(
            site = SiteID.cam4,
            pages = mutableListOf(
                CatalogPage(rooms = listOf(Room(username = "c4_1", show = ShowState.publicShow, site = SiteID.cam4)), total = 120, nextOffset = 180),
            ),
        )
        val resA = SiteCatalog.fetch(
            mode = SiteMode.mixed,
            query = CatalogQuery(limit = 90),
            cursor = MixedCursor(listOf(SiteID.chaturbate, SiteID.cam4)),
            registry = SiteRegistry(listOf(cbA, c4A)),
        )
        assertFalse(resA.cursor.isFinished)
        assertEquals(640, resA.page.nextOffset)
        assertTrue(resA.page.total > resA.page.nextOffset, "已尽站点的超额游标不能侵占另一站余量")
        assertEquals(680, resA.page.total)

        val cbB = FakeAdapter(
            site = SiteID.chaturbate,
            rooms = listOf(Room(username = "cb_1", show = ShowState.publicShow)),
            total = 20,
            step = 20,
        )
        val c4B = FakeAdapter(
            site = SiteID.cam4,
            rooms = emptyList(),
            total = 0,
            step = 0,
            error = vela.core.base.site.CatalogFailure.Status(503),
        )
        val resB = SiteCatalog.fetch(
            mode = SiteMode.mixed,
            query = CatalogQuery(limit = 20),
            cursor = MixedCursor(listOf(SiteID.chaturbate, SiteID.cam4)),
            registry = SiteRegistry(listOf(cbB, c4B)),
        )
        assertFalse(resB.cursor.isFinished)
        assertEquals(20, resB.page.nextOffset)
        assertTrue(resB.page.total > resB.page.nextOffset, "仍有未结束站点时 total 必须大于 nextOffset")
    }

    @Test
    fun testMixedCancellationRethrowsInsteadOfReturningPartial() = runBlocking {
        val cb = FakeAdapter(
            site = SiteID.chaturbate,
            rooms = listOf(Room(username = "cb_1", show = ShowState.publicShow)),
            total = 10,
            step = 1,
        )
        val c4 = FakeAdapter(
            site = SiteID.cam4,
            rooms = emptyList(),
            total = 0,
            step = 0,
            error = kotlinx.coroutines.CancellationException("cancelled"),
        )
        try {
            SiteCatalog.fetch(
                mode = SiteMode.mixed,
                query = CatalogQuery(limit = 2),
                cursor = MixedCursor(listOf(SiteID.chaturbate, SiteID.cam4)),
                registry = SiteRegistry(listOf(cb, c4)),
            )
            fail("应重抛 CancellationException")
        } catch (_: kotlinx.coroutines.CancellationException) {
            // 预期
        }
    }

    @Test
    fun testMixedFilteredEmptyPageWithAdvancingCursorDoesNotEndSite() = runBlocking {
        val cb = ScriptedAdapter(
            site = SiteID.chaturbate,
            pages = mutableListOf(
                CatalogPage(rooms = listOf(Room(username = "cb_1", show = ShowState.publicShow)), total = 10, nextOffset = 2),
                CatalogPage(rooms = listOf(Room(username = "cb_2", show = ShowState.publicShow)), total = 10, nextOffset = 4),
            ),
        )
        val c4 = ScriptedAdapter(
            site = SiteID.cam4,
            pages = mutableListOf(
                CatalogPage(rooms = emptyList(), total = 10, nextOffset = 2),
                CatalogPage(rooms = listOf(Room(username = "c4_found", show = ShowState.publicShow, site = SiteID.cam4)), total = 10, nextOffset = 4),
            ),
        )
        val registry = SiteRegistry(listOf(cb, c4))
        val first = SiteCatalog.fetch(
            mode = SiteMode.mixed,
            query = CatalogQuery(limit = 2),
            cursor = MixedCursor(listOf(SiteID.chaturbate, SiteID.cam4)),
            registry = registry,
        )
        assertFalse(first.cursor.ended.contains(SiteID.cam4))
        assertEquals(2, first.cursor.offsets[SiteID.cam4])

        val second = SiteCatalog.fetch(
            mode = SiteMode.mixed,
            query = CatalogQuery(limit = 2),
            cursor = first.cursor,
            registry = registry,
        )
        assertEquals(listOf(0, 2), c4.requestedOffsets)
        assertEquals(listOf("cb_2", "c4:c4_found"), second.page.rooms.map { it.id })
    }
}

private class ScriptedAdapter(
    override val site: SiteID,
    private val pages: MutableList<CatalogPage>,
) : SiteAdapter {
    val requestedOffsets = mutableListOf<Int>()
    override val capabilities: SiteCapabilities =
        if (site == SiteID.chaturbate) {
            SiteCapabilities.serverGender + SiteCapabilities.serverRegion + SiteCapabilities.serverTag +
                SiteCapabilities.freshCams + SiteCapabilities.keywordSearch
        } else {
            SiteCapabilities.strictMaster
        }
    override val challengeHome: URI? = null

    override suspend fun fetchDirectory(query: CatalogQuery): CatalogPage {
        requestedOffsets.add(query.offset)
        return if (pages.isEmpty()) CatalogPage(rooms = emptyList(), total = 0, nextOffset = query.offset) else pages.removeAt(0)
    }

    override suspend fun resolveStream(room: Room): SourcedTicket =
        SourcedTicket(StreamTicket(success = false, url = null, status = ShowState.offline), StreamOrigin.edge)

    override suspend fun status(keys: List<RoomKey>, now: Instant): Map<RoomKey, FavoriteStatus> = emptyMap()
    override fun thumbnail(room: Room, now: Instant): URI? = null
    override fun mediaHeaders(key: RoomKey): Map<String, String> = emptyMap()
    override fun masterFailure(status: Int): StreamFailure? = null
}

private class OneShot(private val result: HTTPResult) : HTTPClient {
    override suspend fun send(request: HTTPRequest): HTTPResult = result
}

private class FakeAdapter(
    override val site: SiteID,
    private val rooms: List<Room>,
    private val total: Int,
    private val step: Int,
    private val error: Throwable? = null,
    private val rejectFilters: Boolean = false,
) : SiteAdapter {
    var calls: Int = 0
        private set
    var networkCalls: Int = 0
        private set

    override val capabilities: SiteCapabilities =
        if (site == SiteID.chaturbate) {
            SiteCapabilities.serverGender + SiteCapabilities.serverRegion + SiteCapabilities.serverTag +
                SiteCapabilities.freshCams + SiteCapabilities.keywordSearch
        } else {
            SiteCapabilities.strictMaster
        }
    override val challengeHome: URI? = null

    override suspend fun fetchDirectory(query: CatalogQuery): CatalogPage {
        calls += 1
        if (rejectFilters && (query.region != null || query.tag != null || query.newCams)) {
            return CatalogPage(rooms = emptyList(), total = 0, nextOffset = 0)
        }
        networkCalls += 1
        error?.let { throw it }
        return CatalogPage(rooms = rooms, total = total, nextOffset = query.offset + step)
    }

    override suspend fun resolveStream(room: Room): SourcedTicket =
        SourcedTicket(StreamTicket(success = false, url = null, status = ShowState.offline), StreamOrigin.edge)

    override suspend fun status(keys: List<RoomKey>, now: Instant): Map<RoomKey, FavoriteStatus> = emptyMap()

    override fun thumbnail(room: Room, now: Instant): URI? = null

    override fun mediaHeaders(key: RoomKey): Map<String, String> = emptyMap()

    override fun masterFailure(status: Int): StreamFailure? = null
}
