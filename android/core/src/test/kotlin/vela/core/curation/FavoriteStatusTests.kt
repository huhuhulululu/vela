package vela.core.curation

import java.net.URI
import java.net.URLDecoder
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import vela.core.base.model.Gender
import vela.core.base.model.Prefs
import vela.core.base.model.Room
import vela.core.base.model.ShowState
import vela.core.base.net.EndpointBackoffBox
import vela.core.base.net.HTTPClient
import vela.core.base.net.HTTPRequest
import vela.core.base.net.HTTPResult
import vela.core.base.site.CatalogFailure
import vela.core.base.site.ChatContext
import vela.core.base.site.FavoriteStatus

private fun json(text: String, status: Int = 200): HTTPResult =
    HTTPResult(status = status, data = text.toByteArray(Charsets.UTF_8))

private fun publicContext(name: String, viewers: Int = 10, start: Int = 1_000): HTTPResult = json(
    """
    {"room_status":"public","num_viewers":$viewers,"room_title":"hi","broadcaster_username":"$name",
     "broadcaster_gender":"female","age":24,"start_timestamp":$start}
    """.trimIndent(),
)

private fun catalogRoom(name: String, viewers: Int = 5, show: ShowState = ShowState.publicShow): Room =
    Room(username = name, viewers = viewers, show = show)

private fun requestUsername(url: String): String {
    val path = try {
        URI(url).path
    } catch (_: Exception) {
        return ""
    } ?: return ""
    val raw = path.split('/').filter { it.isNotEmpty() }.getOrNull(2) ?: return ""
    return try {
        URLDecoder.decode(raw, Charsets.UTF_8)
    } catch (_: Exception) {
        raw
    }
}

/** 记录请求次数与同时在途数的假网络层。 */
private class RecordingClient(
    private val respond: (String) -> HTTPResult,
) : HTTPClient {
    private val inFlight = AtomicInteger(0)
    private val peakValue = AtomicInteger(0)
    private val requestedNames = ArrayList<String>()

    val peak: Int
        get() = peakValue.get()

    val requested: List<String>
        get() = synchronized(requestedNames) { requestedNames.toList() }

    override suspend fun send(request: HTTPRequest): HTTPResult {
        val name = requestUsername(request.url)
        val now = inFlight.incrementAndGet()
        peakValue.updateAndGet { maxOf(it, now) }
        synchronized(requestedNames) { requestedNames.add(name) }
        delay(15)
        inFlight.decrementAndGet()
        return respond(name)
    }
}

class FavoriteStatusTests {
    @BeforeTest
    fun setUp() {
        EndpointBackoffBox.shared.reset()
    }

    private val now: Instant = Instant.ofEpochSecond(10_000)

    @Test
    fun testDecodeOnlinePublicRoom() {
        val result = publicContext("Alpha", viewers = 321)
        val status = ChatContext.decode(result.status, result.data, now)
        val room = assertNotNull(status.room)
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
            val data = """{"room_status":"$state","num_viewers":0,"broadcaster_username":"x"}"""
                .toByteArray(Charsets.UTF_8)
            assertEquals(FavoriteStatus.Offline, ChatContext.decode(200, data, now), state)
        }
        assertEquals(FavoriteStatus.NotFound, ChatContext.decode(404, ByteArray(0), now))
        val deleted = """{"status":403,"detail":"Room is deleted","code":"access-denied"}"""
            .toByteArray(Charsets.UTF_8)
        assertEquals(FavoriteStatus.NotFound, ChatContext.decode(403, deleted, now))
        val password = """{"status":401,"detail":"This room requires a password."}""".toByteArray(Charsets.UTF_8)
        assertEquals(FavoriteStatus.Offline, ChatContext.decode(401, password, now))
    }

    @Test
    fun testDecodeChallengeAndFailures() {
        val page = "<html><title>Just a moment...</title></html>".toByteArray(Charsets.UTF_8)
        for (status in listOf(200, 403, 503)) {
            val error = assertFailsWith<CatalogFailure> { ChatContext.decode(status, page, now) }
            assertEquals(CatalogFailure.Challenge, error)
        }
        val denied = assertFailsWith<CatalogFailure> {
            ChatContext.decode(403, "denied".toByteArray(Charsets.UTF_8), now)
        }
        assertEquals(CatalogFailure.Challenge, denied)
        val status = assertFailsWith<CatalogFailure> { ChatContext.decode(500, ByteArray(0), now) }
        assertEquals(CatalogFailure.Status(500), status)
        val malformed = assertFailsWith<CatalogFailure> {
            ChatContext.decode(200, "[1]".toByteArray(Charsets.UTF_8), now)
        }
        assertEquals(CatalogFailure.Malformed, malformed)
    }

    @Test
    fun testRequestShape() {
        val request = ChatContext.request("alpha_1")
        assertEquals("https://chaturbate.com/api/chatvideocontext/alpha_1/", request.url)
        assertEquals("XMLHttpRequest", request.header("X-Requested-With"))
        assertEquals("https://chaturbate.com/alpha_1/", request.header("Referer"))
        assertNotNull(request.header("User-Agent"))
        assertTrue(request.timeoutSeconds <= 10.0)
        assertNull(request.header("Cookie"))
    }

    @Test
    fun testCacheExpiryAndCaseInsensitivity() {
        val cache = FavoriteCache().storing(mapOf("alpha" to FavoriteStatus.Offline), now)
        assertEquals(FavoriteStatus.Offline, cache.fresh("ALPHA", now.plusSeconds(FavoriteCache.ttl.toLong())))
        assertNull(cache.fresh("alpha", now.plusSeconds(FavoriteCache.ttl.toLong() + 1)))
        assertEquals(FavoriteStatus.Offline, cache.status("Alpha"))
        assertNull(cache.status("beta"))
        val kept = cache.storing(mapOf("beta" to FavoriteStatus.NotFound), now).pruned(listOf("Beta"))
        assertNull(kept.status("alpha"))
        assertEquals(FavoriteStatus.NotFound, kept.status("beta"))
    }

    @Test
    fun testPlanPrefersFreshCacheThenCatalogThenAsksNetwork() {
        val catalog = KnownCatalog()
        catalog.record(
            listOf(catalogRoom("Alpha"), catalogRoom("beta", show = ShowState.privateShow), catalogRoom("cache")),
            firstPage = true,
            at = now,
        )
        val cache = FavoriteCache().storing(mapOf("cache" to FavoriteStatus.NotFound), now)
        val plan = FavoriteResolver.plan(
            favorites = listOf("alpha", "Beta", "cache", "gamma", "ALPHA", "gamma"),
            catalog = catalog,
            cache = cache,
            now = now.plusSeconds(60),
        )
        assertEquals("Alpha", plan.resolved["alpha"]?.room?.username)
        assertEquals(FavoriteStatus.Offline, plan.resolved["beta"])
        assertEquals(FavoriteStatus.NotFound, plan.resolved["cache"])
        assertEquals(setOf("alpha", "beta"), plan.fromCatalog.keys)
        assertEquals(listOf("gamma"), plan.pending)
    }

    @Test
    fun testPlanIgnoresStaleCatalogAndCache() {
        val catalog = KnownCatalog()
        catalog.record(listOf(catalogRoom("alpha")), firstPage = true, at = now)
        val cache = FavoriteCache().storing(mapOf("beta" to FavoriteStatus.Offline), now)
        val later = now.plusSeconds(FavoriteCache.ttl.toLong() + 1)
        val plan = FavoriteResolver.plan(listOf("alpha", "beta"), catalog, cache, later)
        assertTrue(plan.resolved.isEmpty())
        assertEquals(listOf("alpha", "beta"), plan.pending)
    }

    @Test
    fun testKnownCatalogAppendsAfterFirstPage() {
        val catalog = KnownCatalog()
        catalog.record(listOf(catalogRoom("a")), firstPage = true, at = now)
        catalog.record(listOf(catalogRoom("b")), firstPage = false, at = now)
        assertEquals(listOf("a", "b"), catalog.rooms.map { it.username })
        catalog.record(listOf(catalogRoom("c")), firstPage = true, at = now)
        assertEquals(listOf("c"), catalog.rooms.map { it.username })
    }

    @Test
    fun testOnlineRoomsSortByViewersThenFavoriteOrder() {
        val statuses = mapOf(
            "a" to FavoriteStatus.Online(catalogRoom("a", viewers = 5)),
            "b" to FavoriteStatus.Online(catalogRoom("b", viewers = 50)),
            "c" to FavoriteStatus.Online(catalogRoom("c", viewers = 5)),
            "d" to FavoriteStatus.Offline,
            "e" to FavoriteStatus.NotFound,
        )
        val rooms = FavoriteResolver.onlineRooms(listOf("a", "d", "b", "e", "c", "A"), statuses)
        assertEquals(listOf("b", "a", "c"), rooms.map { it.username })
    }

    @Test
    fun testFetcherNeverExceedsConcurrencyLimit() = runBlocking {
        val names = (0 until 12).map { "user$it" }
        val client = RecordingClient { publicContext(it) }
        val result = FavoriteFetcher(client).fetch(names)
        assertEquals(12, result.statuses.size)
        assertFalse(result.challenged)
        assertEquals(12, client.requested.size)
        assertTrue(client.peak <= FavoriteFetcher.maxConcurrent)
        assertEquals(FavoriteFetcher.maxConcurrent, client.peak)
    }

    @Test
    fun testFetcherSkipsFailuresAndMarksMissing() = runBlocking {
        val client = RecordingClient { name ->
            when (name) {
                "gone" -> json("", status = 404)
                "broken" -> json("", status = 500)
                "away" -> json("""{"room_status":"away","broadcaster_username":"away"}""")
                else -> publicContext(name)
            }
        }
        val result = FavoriteFetcher(client, backoff = null).fetch(listOf("live", "gone", "broken", "away"))
        assertEquals(FavoriteStatus.NotFound, result.statuses["gone"])
        assertEquals(FavoriteStatus.Offline, result.statuses["away"])
        assertNotNull(result.statuses["live"]?.room)
        assertNull(result.statuses["broken"])
        assertFalse(result.challenged)
    }

    @Test
    fun testFetcherStopsOnChallenge() = runBlocking {
        val page = HTTPResult(status = 403, data = "<html>Just a moment</html>".toByteArray(Charsets.UTF_8))
        val client = RecordingClient { page }
        val names = (0 until 20).map { "user$it" }
        val result = FavoriteFetcher(client).fetch(names)
        assertTrue(result.challenged)
        assertTrue(client.requested.size <= FavoriteFetcher.maxConcurrent)
        assertTrue(result.statuses.isEmpty())
    }

    @Test
    fun testFetcherWithNoNamesDoesNothing() = runBlocking {
        val client = RecordingClient { publicContext(it) }
        val result = FavoriteFetcher(client).fetch(emptyList())
        assertTrue(result.statuses.isEmpty())
        assertTrue(client.requested.isEmpty())
    }

    @Test
    fun testExchangeRoundTripAndValidation() {
        val data = FavoriteExchange.encode(listOf("alpha", "beta_2"))
        assertEquals(FavoriteExchange.Imported(listOf("alpha", "beta_2"), 0), FavoriteExchange.decode(data))
        val bare = """["ok_1"," padded ","bad name","x",""]""".toByteArray(Charsets.UTF_8)
        assertEquals(FavoriteExchange.Imported(listOf("ok_1", "padded"), 3), FavoriteExchange.decode(bare))
        val malformed = assertFailsWith<FavoriteExchange.Failure> {
            FavoriteExchange.decode("{".toByteArray(Charsets.UTF_8))
        }
        assertEquals(FavoriteExchange.Failure.Malformed, malformed)
        val huge = ByteArray(Prefs.maxFileBytes + 1) { 0x20 }
        val tooLarge = assertFailsWith<FavoriteExchange.Failure> { FavoriteExchange.decode(huge) }
        assertEquals(FavoriteExchange.Failure.TooLarge, tooLarge)
    }

    @Test
    fun testImportMergesDedupesAndHonorsLimits() {
        val base = Curation(favorites = listOf("alpha", "beta"), blocked = listOf("bad"))
        val merged = base.importing(listOf("BETA", "gamma", "bad", "gamma", "delta"))
        assertEquals(listOf("alpha", "beta", "gamma", "delta"), merged.curation.favorites)
        assertEquals(2, merged.added)

        val full = Curation(favorites = (0 until Curation.maxEntries).map { "u$it" })
        val capped = full.importing(listOf("extra1", "extra2"))
        assertEquals(Curation.maxEntries, capped.curation.favorites.size)
        assertEquals(0, capped.added)
        assertFalse(capped.curation.isFavorite("extra1"))
    }

    @Test
    fun testUsernameValidation() {
        assertTrue(Room.isValidUsername("Alpha_9"))
        assertFalse(Room.isValidUsername("a"))
        assertFalse(Room.isValidUsername("has space"))
        assertFalse(Room.isValidUsername("a".repeat(31)))
        assertFalse(Room.isValidUsername("名字很长"))
    }

    @Test
    fun testCam4ModeFavoritePlanSkipsChaturbateChallenge() = runBlocking {
        val cam4Room = Room(
            username = "sample_one",
            viewers = 12,
            show = ShowState.publicShow,
            site = vela.core.sites.SiteID.cam4,
        )
        val cache = FavoriteCache().storing(mapOf("c4:sample_one" to FavoriteStatus.Online(cam4Room)), now)
        val allFavorites = listOf("alice", "c4:sample_one")

        val cam4Plan = FavoriteResolver.plan(
            favorites = allFavorites,
            catalog = KnownCatalog(),
            cache = cache,
            now = now,
            mode = vela.core.sites.SiteMode.cam4,
        )
        assertEquals(emptyList(), cam4Plan.pending)

        val challenge = HTTPResult(status = 403, data = "<html>Just a moment</html>".toByteArray(Charsets.UTF_8))
        val cbClient = RecordingClient { challenge }
        val cbPending = cam4Plan.pending.filter { vela.core.sites.RoomKey.siteOfRef(it) == vela.core.sites.SiteID.chaturbate }
        val fetched = FavoriteFetcher(cbClient).fetch(cbPending)
        assertFalse(fetched.challenged)
        assertTrue(cbClient.requested.isEmpty())

        val rooms = FavoriteResolver.onlineRooms(listOf("c4:sample_one"), cam4Plan.resolved)
        assertEquals(listOf("c4:sample_one"), rooms.map { it.ref })

        val panelPlan = FavoriteResolver.plan(
            favorites = allFavorites,
            catalog = KnownCatalog(),
            cache = cache,
            now = now,
            mode = null,
        )
        assertEquals(listOf("alice"), panelPlan.pending)
    }
}
