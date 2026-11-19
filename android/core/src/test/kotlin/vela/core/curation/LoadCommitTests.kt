package vela.core.curation

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import vela.core.base.model.FeedSource
import vela.core.base.model.Gender
import vela.core.base.model.Room
import vela.core.base.model.ShowState
import vela.core.base.site.CatalogPage

class LoadCommitTests {
    private val now: Instant = Instant.ofEpochSecond(3_000_000)

    private fun page(names: List<String>): CatalogPage {
        val rooms = names.map { Room(username = it, viewers = 5, gender = Gender.female, tags = listOf("x"), show = ShowState.publicShow) }
        return CatalogPage(rooms = rooms, total = rooms.size, nextOffset = rooms.size)
    }

    @Test
    fun testStaleResponseLeavesNoTrace() {
        val catalog = KnownCatalog()
        catalog.record(page(listOf("keep")).rooms, firstPage = true, at = now)
        val reasons = mutableMapOf("keep" to "旧理由")
        val applied = LoadCommit.apply(
            raw = page(listOf("old")),
            arranged = page(listOf("old")),
            offset = 0,
            source = FeedSource.guess,
            token = 3,
            current = 4,
            replacing = true,
            now = now.plusSeconds(500),
            catalog = catalog,
            reasons = reasons,
            reason = { "新理由" },
        )
        assertFalse(applied)
        assertEquals(listOf("keep"), catalog.rooms.map { it.username })
        assertEquals(now, catalog.entries.first().at)
        assertEquals(mapOf("keep" to "旧理由"), reasons)
    }

    @Test
    fun testFreshResponseRecordsCatalogAndReasons() {
        val catalog = KnownCatalog()
        val reasons = mutableMapOf("gone" to "x")
        val applied = LoadCommit.apply(
            raw = page(listOf("a", "b")),
            arranged = page(listOf("b")),
            offset = 0,
            source = FeedSource.guess,
            token = 4,
            current = 4,
            replacing = true,
            now = now,
            catalog = catalog,
            reasons = reasons,
            reason = { "r-${it.username}" },
        )
        assertTrue(applied)
        assertEquals(listOf("a", "b"), catalog.rooms.map { it.username })
        assertEquals(mapOf("b" to "r-b"), reasons)
    }

    @Test
    fun testFavoritesSourceDoesNotRecordCatalogOrReasons() {
        val catalog = KnownCatalog()
        val reasons = mutableMapOf<String, String>()
        LoadCommit.apply(
            raw = page(listOf("a")),
            arranged = page(listOf("a")),
            offset = 0,
            source = FeedSource.favorites,
            token = 1,
            current = 1,
            replacing = true,
            now = now,
            catalog = catalog,
            reasons = reasons,
            reason = { "r" },
        )
        assertTrue(catalog.rooms.isEmpty())
        assertTrue(reasons.isEmpty())
    }
}
