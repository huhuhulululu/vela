package vela.core.cross

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import vela.core.base.io.maxFileBytes
import vela.core.base.model.FeedSource
import vela.core.base.model.Prefs
import vela.core.base.model.Room
import vela.core.base.model.ShowState
import vela.core.curation.Curation
import vela.core.curation.CurationStore
import vela.core.taste.Belief
import vela.core.taste.Taste
import vela.core.taste.TasteStore

/**
 * `PreferenceStore` / `Preferences` 结构体不移植。超限的偏好文件改由 `TasteStore` 走同一上限。
 */
class FileLimitTests {
    @Test
    fun testMaxFileBytesMatchesL0aPrefs() {
        assertEquals(Prefs.maxFileBytes, maxFileBytes)
    }

    @Test
    fun testOversizedFilesLoadAsEmpty() {
        val directory = Files.createTempDirectory("vela-limit-")
        try {
            val pref = directory.resolve("feed-taste.json")
            val cur = directory.resolve("feed-curation.json")
            val pad = " ".repeat(Prefs.maxFileBytes + 10)
            Files.write(pref, utf8("{\"weights\":{},\"seen\":{}}" + pad))
            Files.write(cur, utf8("{\"favorites\":[\"a\"],\"blocked\":[]}" + pad))
            assertEquals(Taste(), TasteStore(pref).load())
            assertEquals(Curation(), CurationStore(cur).load())
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun testNormalFilesStillLoad() {
        val directory = Files.createTempDirectory("vela-limit-")
        try {
            val url = directory.resolve("feed-curation.json")
            val store = CurationStore(url)
            assertTrue(store.save(Curation().favoriting("a")))
            assertEquals(listOf("a"), store.load().favorites)
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun testLongNamesAreClipped() {
        val long = "x".repeat(500)
        val room = Room(username = long, viewers = 1, tags = listOf(long), show = ShowState.publicShow)
        val taste = Taste().recording(room = room, watched = 100.0, source = FeedSource.guess, now = java.time.Instant.now())
        assertTrue(taste.beliefs.keys.all { it.length <= Prefs.maxNameLength + 2 })
        assertTrue(taste.seen.keys.all { it.length <= Prefs.maxNameLength })
        assertNotNull(taste.lastSeen(long))
        val cur = Curation().favoriting(long)
        assertEquals(Prefs.maxNameLength, cur.favorites.firstOrNull()?.length)
    }

    @Test
    fun testOversizedKeysDroppedOnLoad() {
        val now = java.time.Instant.ofEpochSecond(1_000_000_000_000L)
        val big = Taste(
            beliefs = mapOf("k".repeat(300) to Belief(hit = 1.0, at = 0.0)),
            seen = mapOf("s".repeat(300) to 1e12),
        )
        assertTrue(big.capped(now).beliefs.isEmpty())
        assertTrue(big.capped(now).seen.isEmpty())
    }
}
