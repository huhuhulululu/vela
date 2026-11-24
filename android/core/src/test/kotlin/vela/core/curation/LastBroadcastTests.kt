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
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import vela.core.base.model.Room
import vela.core.base.model.timeIntervalSince1970
import vela.core.base.net.EndpointBackoffBox
import vela.core.base.net.HTTPClient
import vela.core.base.net.HTTPRequest
import vela.core.base.net.HTTPResult
import vela.core.base.site.CatalogFailure
import vela.core.base.site.FavoriteStatus

private class BioClient(
    private val respond: (String) -> HTTPResult,
) : HTTPClient {
    private val inFlight = AtomicInteger(0)
    private val peakValue = AtomicInteger(0)
    private val names = ArrayList<String>()

    val peak: Int
        get() = peakValue.get()

    val requested: List<String>
        get() = synchronized(names) { names.toList() }

    override suspend fun send(request: HTTPRequest): HTTPResult {
        val name = username(request.url)
        val now = inFlight.incrementAndGet()
        peakValue.updateAndGet { maxOf(it, now) }
        synchronized(names) { names.add(name) }
        delay(10)
        inFlight.decrementAndGet()
        return respond(name)
    }

    private fun username(url: String): String {
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
}

private fun bioJson(text: String, status: Int = 200): HTTPResult =
    HTTPResult(status = status, data = text.toByteArray(Charsets.UTF_8))

class LastBroadcastTests {
    @BeforeTest
    fun setUp() {
        EndpointBackoffBox.shared.reset()
    }

    private val now: Instant = Instant.ofEpochSecond(1_800_000_000)

    private fun decode(result: HTTPResult): Instant? = BioContext.decode(result.status, result.data, now).at

    @Test
    fun testRequestShape() {
        val request = BioContext.request("alpha_1")
        assertEquals("https://chaturbate.com/api/biocontext/alpha_1/", request.url)
        assertEquals("GET", request.method)
        assertEquals(8.0, request.timeoutSeconds)
        assertEquals("XMLHttpRequest", request.header("X-Requested-With"))
    }

    @Test
    fun testDecodesISOLastBroadcast() {
        val date = decode(bioJson("""{"last_broadcast":"2027-01-15T07:00:00Z","follower_count":3}"""))
        assertEquals(Instant.parse("2027-01-15T07:00:00Z"), date)
        val fractional = decode(bioJson("""{"last_broadcast":"2027-01-15T07:00:00.250Z"}"""))
        val base = Instant.parse("2027-01-15T07:00:00Z").timeIntervalSince1970()
        assertEquals(base + 0.25, fractional?.timeIntervalSince1970() ?: 0.0, 0.001)
    }

    @Test
    fun testDecodesPlainAndNumericForms() {
        val plain = decode(bioJson("""{"last_broadcast":"2027-01-15 07:00:00"}"""))
        assertEquals(Instant.parse("2027-01-15T07:00:00Z"), plain)
        assertEquals(Instant.ofEpochSecond(1_799_990_000), decode(bioJson("""{"last_broadcast":1799990000}""")))
        assertEquals(Instant.ofEpochSecond(1_799_990_000), decode(bioJson("""{"last_broadcast":1799990000000}""")))
    }

    @Test
    fun testFallsBackToSecondsSinceLastBroadcast() {
        assertEquals(
            now.minusSeconds(3600),
            decode(bioJson("""{"last_broadcast":null,"time_since_last_broadcast":"3600"}""")),
        )
        assertNull(decode(bioJson("""{"time_since_last_broadcast":"3 hours ago"}""")))
    }

    @Test
    fun testFutureTimeIsClampedToNow() {
        assertEquals(now, decode(bioJson("""{"last_broadcast":1900000000}""")))
    }

    @Test
    fun testMissingAndDeletedAreAnswersWithoutTime() {
        assertNull(decode(bioJson("{}")))
        assertNull(decode(bioJson("", status = 404)))
        assertNull(decode(bioJson("""{"detail":"gone"}""", status = 403)))
    }

    @Test
    fun testChallengeAndBadPayloadThrow() {
        val challenge = assertFailsWith<CatalogFailure> { decode(bioJson("<html>Just a moment...</html>")) }
        assertEquals(CatalogFailure.Challenge, challenge)
        val blocked = assertFailsWith<CatalogFailure> { decode(bioJson("<p>nope</p>", status = 403)) }
        assertEquals(CatalogFailure.Challenge, blocked)
        val malformed = assertFailsWith<CatalogFailure> { decode(bioJson("not json")) }
        assertEquals(CatalogFailure.Malformed, malformed)
        val status = assertFailsWith<CatalogFailure> { decode(bioJson("{}", status = 500)) }
        assertEquals(CatalogFailure.Status(500), status)
    }

    @Test
    fun testRelativeCopy() {
        fun ago(seconds: Double): String = BroadcastAgo.string(now.minusNanos((seconds * 1_000_000_000.0).toLong()), now)
        assertEquals("上次开播 刚刚", ago(20.0))
        assertEquals("上次开播 刚刚", ago(-50.0))
        assertEquals("上次开播 5 分钟前", ago(5.0 * 60))
        assertEquals("上次开播 3 小时前", ago(3.0 * 3600 + 59))
        assertEquals("上次开播 2 天前", ago(2.0 * 86_400))
        assertEquals("上次开播 2 个月前", ago(65.0 * 86_400))
        assertEquals("上次开播 2 年前", ago(800.0 * 86_400))
    }

    @Test
    fun testCacheExpiresAfterThirtyMinutes() {
        val cache = LastBroadcastCache().storing(
            mapOf("Alpha" to LastBroadcast(now), "beta" to LastBroadcast(null)),
            now,
        )
        assertEquals(listOf("gamma"), cache.pending(listOf("alpha", "BETA", "gamma", "Gamma"), now.plusSeconds(60)))
        assertEquals(
            listOf("alpha", "gamma"),
            cache.pending(listOf("alpha", "gamma"), now.plusSeconds(LastBroadcastCache.ttl.toLong() + 1)),
        )
        assertEquals(mapOf("alpha" to now), cache.dates)
        assertEquals(emptyMap(), cache.pruned(listOf("beta")).dates)
    }

    @Test
    fun testFetcherRespectsConcurrencyLimit() = runBlocking {
        val client = BioClient { bioJson("""{"last_broadcast":1799990000}""") }
        val names = (0 until 10).map { "user$it" }
        val result = LastBroadcastFetcher(client, now = { Instant.ofEpochSecond(1_800_000_000) }).fetch(names)
        assertEquals(10, result.found.size)
        assertFalse(result.challenged)
        assertTrue(client.peak <= FavoriteFetcher.maxConcurrent)
        assertEquals(LastBroadcast(Instant.ofEpochSecond(1_799_990_000)), result.found["user3"])
    }

    @Test
    fun testFetcherStopsOnChallengeAndSkipsFailures() = runBlocking {
        val client = BioClient { name ->
            when (name) {
                "b" -> bioJson("<html>just a moment</html>")
                "a" -> bioJson("{}", status = 500)
                else -> bioJson("{}")
            }
        }
        val names = listOf("a", "b") + (0 until 20).map { "n$it" }
        val result = LastBroadcastFetcher(client, concurrency = 1).fetch(names)
        assertTrue(result.challenged)
        assertNull(result.found["a"])
        assertEquals(listOf("a", "b"), client.requested)
    }

    @Test
    fun testBoardShowsLastBroadcastAndSortsOfflineByRecency() {
        val statuses = mapOf(
            "old" to FavoriteStatus.Offline,
            "new" to FavoriteStatus.Offline,
            "nodate" to FavoriteStatus.Offline,
            "gone" to FavoriteStatus.NotFound,
            "live" to FavoriteStatus.Online(Room(username = "live")),
        )
        val dates = mapOf(
            "old" to now.minusSeconds(3 * 86_400L),
            "new" to now.minusSeconds(2 * 3_600L),
        )
        val board = FavoritesBoard.make(
            favorites = listOf("nodate", "old", "gone", "live", "new", "unseen"),
            statuses = statuses,
            catalog = emptyList(),
            lastBroadcast = dates,
            now = now,
        )
        assertEquals(listOf("live"), board.online.map { it.username })
        assertEquals(listOf("new", "old", "nodate", "unseen", "gone"), board.others.map { it.username })
        assertEquals(
            listOf("上次开播 2 小时前", "上次开播 3 天前", "离线", "未查询", "账号不存在"),
            board.others.map { it.detail },
        )
    }
}
