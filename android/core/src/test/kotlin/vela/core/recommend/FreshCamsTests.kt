package vela.core.recommend

import java.net.URI
import java.time.Instant
import vela.core.base.model.FeedSource
import vela.core.base.model.Gender
import vela.core.base.model.Region
import vela.core.base.model.Room
import vela.core.base.site.CatalogQuery
import vela.core.taste.Taste
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 新开播改用站点的 `new_cams=true` 筛选：只有新开播来源带它，可与其他筛选叠加，排序仍按开播时长。 */
class FreshCamsTests {
    private fun values(url: URI): Map<String, String> {
        val raw = url.rawQuery ?: return emptyMap()
        val items = LinkedHashMap<String, String>()
        for (item in raw.split('&')) {
            if (item.isEmpty()) continue
            val eq = item.indexOf('=')
            if (eq < 0) {
                items[item] = ""
            } else {
                items[item.substring(0, eq)] = item.substring(eq + 1)
            }
        }
        return items
    }

    @Test
    fun testOnlyFreshSourceUsesNewCams() {
        assertTrue(FeedSource.fresh.usesNewCams)
        for (source in listOf(FeedSource.guess, FeedSource.popular, FeedSource.favorites)) {
            assertFalse(source.usesNewCams)
        }
    }

    @Test
    fun testNewCamsParameterIsAddedAndStacksWithFilters() {
        val query = CatalogQuery(
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
            values(query.url()),
        )
    }

    @Test
    fun testNewCamsIsOffByDefault() {
        assertNull(values(CatalogQuery(limit = 40).url())["new_cams"])
    }

    @Test
    fun testFreshStillSortsShortestBroadcastFirst() {
        val rooms = listOf(
            Room(username = "late", secondsOnline = 900),
            Room(username = "none"),
            Room(username = "early", secondsOnline = 60),
        )
        val sorted = Recommender.arrange(rooms, FeedSource.fresh, Taste(), Instant.now(), 1UL)
        assertEquals(listOf("early", "late", "none"), sorted.map { it.username })
    }
}
