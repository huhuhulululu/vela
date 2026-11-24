package vela.core.taste

import java.net.URI
import java.time.Instant
import vela.core.base.model.Room
import vela.core.base.site.CatalogFailure
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HotTagsTests {
    private val now: Instant = Instant.ofEpochSecond(1_800_000_000)

    private fun values(url: URI): Map<String, String> {
        val query = url.rawQuery ?: return emptyMap()
        val out = LinkedHashMap<String, String>()
        for (part in query.split('&')) {
            val eq = part.indexOf('=')
            if (eq < 0) {
                out[part] = ""
            } else {
                out[part.substring(0, eq)] = part.substring(eq + 1)
            }
        }
        return out
    }

    private fun data(text: String): ByteArray = text.toByteArray(Charsets.UTF_8)

    @Test
    fun testURLMatchesSiteTable() {
        val url = HotTags.url()
        assertTrue(url.toString().startsWith("https://chaturbate.com/api/ts/hashtags/tag-table-data/?"))
        assertEquals(mapOf("sort" to "-rc", "page" to "1", "g" to "", "limit" to "20"), values(url))
        assertEquals("XMLHttpRequest", HotTags.request().header("X-Requested-With"))
    }

    @Test
    fun testDecodeNormalizesAndSkipsBadItems() {
        val body = """
            {"hashtags":[{"hashtag":"#Asian","room_count":812},{"hashtag":"asian","room_count":3},
             {"room_count":9},{"hashtag":"  ","room_count":1},{"hashtag":"bigboobs","room_count":"77"},{"hashtag":"new"}],
             "total":5000}
        """.trimIndent()
        val tags = HotTags.decode(status = 200, data = data(body))
        assertEquals(
            listOf(HotTag("asian", 812), HotTag("bigboobs", 77), HotTag("new", 0)),
            tags,
        )
    }

    @Test
    fun testDecodeFailures() {
        val challenge = assertFailsWith<CatalogFailure> {
            HotTags.decode(status = 200, data = data("<html>Just a moment</html>"))
        }
        assertEquals(CatalogFailure.Challenge, challenge)
        val forbidden = assertFailsWith<CatalogFailure> {
            HotTags.decode(status = 403, data = data("{}"))
        }
        assertEquals(CatalogFailure.Challenge, forbidden)
        val status = assertFailsWith<CatalogFailure> {
            HotTags.decode(status = 500, data = data("{}"))
        }
        assertEquals(CatalogFailure.Status(500), status)
        val malformed = assertFailsWith<CatalogFailure> {
            HotTags.decode(status = 200, data = data("""{"total":1}"""))
        }
        assertEquals(CatalogFailure.Malformed, malformed)
    }

    @Test
    fun testCacheLastsSixHours() {
        val cache = HotTagCache().storing(listOf(HotTag("a", 1)), now)
        assertEquals(listOf("a"), cache.fresh(now.plusSeconds(3600))?.map { it.name })
        assertFalse(cache.needsFetch(now.plusSeconds(3600)))
        assertNull(cache.fresh(now.plusSeconds(HotTagCache.ttl.toLong() + 1)))
        assertTrue(cache.needsFetch(now.plusSeconds(HotTagCache.ttl.toLong() + 1)))
    }

    @Test
    fun testFailureCoolsDownAndEmptyCountsAsFailure() {
        assertTrue(HotTagCache().needsFetch(now))
        val failed = HotTagCache().recordingFailure(now)
        assertFalse(failed.needsFetch(now.plusSeconds(30)))
        assertTrue(failed.needsFetch(now.plusSeconds(HotTagCache.retryAfter.toLong())))
        val empty = HotTagCache().storing(emptyList(), now)
        assertNull(empty.fresh(now))
        assertFalse(empty.needsFetch(now.plusSeconds(1)))
    }

    @Test
    fun testColdStartPrefersHotTagsNotInProfile() {
        val taste = Taste().boosting(Taste.tagKey("asian"), now)
        val hot = listOf("asian", "milf", "new", "latina").map { HotTag(it, 1) }
        val rooms = listOf(Room(username = "r", tags = listOf("local")))
        assertEquals(listOf("milf", "new"), taste.suggestedTags(hot = hot, fallback = rooms, limit = 2))
    }

    @Test
    fun testColdStartFallsBackToOnlineRoomTags() {
        val rooms = listOf(
            Room(username = "a", tags = listOf("x", "y")),
            Room(username = "b", tags = listOf("x")),
        )
        assertEquals(listOf("x", "y"), Taste().suggestedTags(hot = null, fallback = rooms))
        val known = Taste().boosting(Taste.tagKey("hot"), now)
        assertEquals(listOf("x", "y"), known.suggestedTags(hot = listOf(HotTag("hot", 9)), fallback = rooms))
    }

    @Test
    fun testFilterHintsUseHotTagsOrFallBack() {
        val hot = (0 until 12).map { HotTag("t$it", 12 - it) }
        assertEquals((0 until 8).map { "t$it" }, HotTags.filterHints(hot = hot, rooms = emptyList()))
        val rooms = listOf(
            Room(username = "a", tags = listOf("x", "y")),
            Room(username = "b", tags = listOf("y")),
        )
        assertEquals(listOf("y", "x"), HotTags.filterHints(hot = emptyList(), rooms = rooms))
        assertEquals(emptyList(), HotTags.filterHints(hot = null, rooms = emptyList()))
    }
}
