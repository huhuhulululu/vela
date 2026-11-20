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
        assertEquals(1, c4.calls)
        assertEquals(0, c4.networkCalls)
        assertEquals(listOf("alice"), fetched.page.rooms.map { it.id })
        assertTrue(fetched.cursor.ended.contains(SiteID.cam4))
    }
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
        if (site == SiteID.chaturbate) SiteCapabilities.serverTag else SiteCapabilities.strictMaster
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
