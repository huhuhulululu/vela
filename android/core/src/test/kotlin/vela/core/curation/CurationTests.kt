package vela.core.curation

import java.io.IOException
import java.nio.file.Files
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import vela.core.base.io.PreferenceQuarantine
import vela.core.base.site.FavoriteStatus

/** 收藏值与落盘。跨组的 FeedStore / Recommender 用例留给 C9。 */
class CurationTests {
    @Test
    fun testFavoriteAndBlockAreMutuallyExclusiveAndCaseInsensitive() {
        var curation = Curation().favoriting("Alice")
        assertTrue(curation.isFavorite("alice"))
        curation = curation.blocking("ALICE")
        assertFalse(curation.isFavorite("Alice"))
        assertTrue(curation.isBlocked("alice"))
        curation = curation.favoriting("alice")
        assertTrue(curation.isFavorite("Alice"))
        assertFalse(curation.isBlocked("alice"))
        assertEquals(emptyList(), curation.unfavoriting("ALICE").favorites)
        assertEquals(emptyList(), curation.blocking("bob").unblocking("BOB").blocked)
    }

    @Test
    fun testNewestFirstDedupeAndIgnoreBlank() {
        val curation = Curation().favoriting("a").favoriting("b").favoriting("A").favoriting("  ")
        assertEquals(listOf("A", "b"), curation.favorites)
    }

    @Test
    fun testCapsKeepNewest() {
        var curation = Curation()
        for (index in 0 until Curation.maxEntries + 20) {
            curation = curation.blocking("u$index")
        }
        assertEquals(Curation.maxEntries, curation.blocked.size)
        assertEquals("u${Curation.maxEntries + 19}", curation.blocked.first())
        assertFalse(curation.isBlocked("u0"))
        val big = Curation(favorites = (0 until 1_000).map { "f$it" })
        assertEquals(Curation.maxEntries, big.favorites.size)
    }

    @Test
    fun testRestoreDoesNotUnblock() {
        val start = Curation(favorites = listOf("A", "B"))
        val removed = start.unfavoriting("A")
        val blocked = removed.blocking("A")
        assertFalse(blocked.canRestoreFavorite("A"))
        assertEquals(blocked, blocked.restoringFavorite("A"))
        assertTrue(blocked.isBlocked("A"))
    }

    @Test
    fun testRestoreBringsBackFavoriteAtFront() {
        val removed = Curation(favorites = listOf("A", "B")).unfavoriting("A")
        assertTrue(removed.canRestoreFavorite("a"))
        assertEquals(listOf("A", "B"), removed.restoringFavorite("A").favorites)
    }

    @Test
    fun testRestoreVoidWhenReFavorited() {
        val again = Curation(favorites = listOf("B")).favoriting("A")
        assertFalse(again.canRestoreFavorite("A"))
        assertEquals(again, again.restoringFavorite("A"))
    }

    @Test
    fun testStoreRoundTripAndCapOnLoad() {
        val directory = Files.createTempDirectory("vela-curation-")
        try {
            val url = directory.resolve("feed-curation.json")
            val store = CurationStore(url)
            assertEquals(Curation(), store.load())
            val saved = Curation().favoriting("x").blocking("y")
            assertTrue(store.save(saved))
            assertEquals(saved, store.load())

            val oversize = (0 until 1_000).map { "n$it" }
            val payload = buildJsonObject {
                put("favorites", JsonArray(oversize.map { JsonPrimitive(it) }))
                put("blocked", JsonArray(listOf(JsonPrimitive("n1"))))
            }
            Files.writeString(url, payload.toString())
            val loaded = store.load()
            assertTrue(loaded.favorites.size <= Curation.maxEntries)
            assertFalse(loaded.isFavorite("n1"))
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun testStoreSurvivesCorruptFileAndUnwritablePath() {
        val directory = Files.createTempDirectory("vela-curation-")
        try {
            val url = directory.resolve("feed-curation.json")
            Files.createDirectories(url.parent)
            Files.writeString(url, "not json")
            assertEquals(Curation(), CurationStore(url).load())

            val blocker = directory.resolve("file")
            Files.write(blocker, ByteArray(0))
            val bad = CurationStore(blocker.resolve("sub/c.json"))
            assertFalse(bad.save(Curation().blocking("z")))
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun testRenameFailureMakesStoreReadOnlyUntilGoodLoad() {
        val directory = Files.createTempDirectory("vela-curation-")
        val now = Instant.ofEpochSecond(1_700_000_000)
        try {
            val url = directory.resolve("feed-curation.json")
            Files.writeString(url, "broken")
            val store = CurationStore(url)
            store.moveItem = { _, _ -> throw IOException("fileWriteNoPermission") }
            assertEquals(Curation(), store.load(now))
            assertTrue(store.isReadOnly)
            assertFalse(store.save(Curation().blocking("x")))
            assertEquals("broken", Files.readString(url))

            store.moveItem = PreferenceQuarantine.fileMover
            assertTrue(store.save(Curation().favoriting("ok")).not())
            Files.writeString(url, """{"favorites":["ok"],"blocked":[]}""")
            assertEquals(listOf("ok"), store.load(now).favorites)
            assertFalse(store.isReadOnly)
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}

class FavoriteRetryGateTests {
    @Test
    fun testCooldownAfterFailure() {
        val gate = FavoriteRetryGate()
        val t0 = Instant.ofEpochSecond(1_000)
        assertTrue(gate.allowsAutoCheck(t0))
        gate.recordFailure(t0)
        assertFalse(gate.allowsAutoCheck(t0.plusSeconds(59)))
        assertTrue(gate.allowsAutoCheck(t0.plusSeconds(60)))
        gate.reset()
        assertTrue(gate.allowsAutoCheck(t0.plusSeconds(1)))
    }

    @Test
    fun testUnresolvedOnlyConsidersPlannedNames() {
        val online = FavoriteStatus.Offline
        assertFalse(FavoriteResolver.hasUnresolved(listOf("Alice"), mapOf("alice" to online)))
        assertTrue(FavoriteResolver.hasUnresolved(listOf("Alice", "Bob"), mapOf("alice" to online)))
        assertFalse(FavoriteResolver.hasUnresolved(listOf("Alice"), mapOf("alice" to online)))
        assertFalse(FavoriteResolver.hasUnresolved(emptyList(), emptyMap()))
    }

    @Test
    fun testNoFailureRecordedKeepsAutoCheckOpen() {
        val gate = FavoriteRetryGate()
        val t0 = Instant.ofEpochSecond(1_000)
        val incomplete = FavoriteResolver.hasUnresolved(listOf("a"), mapOf("a" to FavoriteStatus.Offline))
        if (incomplete) gate.recordFailure(t0) else gate.reset()
        assertTrue(gate.allowsAutoCheck(t0.plusSeconds(1)))
    }
}
