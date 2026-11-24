package vela.core.recommend

import java.time.Instant
import vela.core.base.model.FeedSource
import vela.core.base.model.Gender
import vela.core.base.model.Room
import vela.core.base.model.RoomQuery
import vela.core.base.model.ShowState
import vela.core.base.site.CatalogPage
import vela.core.base.site.CatalogQuery
import vela.core.feed.FeedStore
import vela.core.taste.BetaSampler
import vela.core.taste.Taste
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FeedRankingTests {
    private val now: Instant = Instant.ofEpochSecond(1_800_000_000)

    private fun room(
        name: String,
        viewers: Int = 100,
        tags: List<String> = emptyList(),
        gender: Gender? = Gender.female,
        secs: Int? = null,
    ): Room = Room(
        username = name,
        viewers = viewers,
        gender = gender,
        tags = tags,
        show = ShowState.publicShow,
        secondsOnline = secs,
    )

    private fun names(rooms: List<Room>): List<String> = rooms.map { it.username }

    private fun trained(hits: Map<String, Int> = emptyMap(), misses: Map<String, Int> = emptyMap()): Taste {
        var taste = Taste()
        for ((tag, n) in hits) {
            repeat(n) {
                taste = taste.recording(
                    room = room("x", tags = listOf(tag), gender = null),
                    watched = 30.0,
                    source = FeedSource.guess,
                    now = now,
                )
            }
        }
        for ((tag, n) in misses) {
            repeat(n) {
                taste = taste.recording(
                    room = room("x", tags = listOf(tag), gender = null),
                    watched = 1.0,
                    source = FeedSource.guess,
                    now = now,
                )
            }
        }
        return taste
    }

    @Test
    fun testPopularKeepsOrderAndNewestSortsWithMissingLast() {
        val rooms = listOf(
            room("a", secs = 500),
            room("b", secs = null),
            room("c", secs = 60),
            room("d", secs = 500),
            room("e", secs = null),
        )
        assertEquals(
            listOf("a", "b", "c", "d", "e"),
            names(Recommender.arrange(rooms, FeedSource.popular, Taste(), now, 1UL)),
        )
        assertEquals(
            listOf("a", "b", "c", "d", "e"),
            names(Recommender.arrange(rooms, FeedSource.popular, Taste(), now, 1UL)),
        )
        assertEquals(
            listOf("c", "a", "d", "b", "e"),
            names(Recommender.arrange(rooms, FeedSource.fresh, Taste(), now, 1UL)),
        )
    }

    @Test
    fun testPageLimits() {
        assertEquals(40, FeedSource.popular.pageLimit)
        assertEquals(90, FeedSource.guess.pageLimit)
        assertEquals(90, FeedSource.fresh.pageLimit)
        assertEquals(1, FeedSource.popular.pageCount)
        assertEquals(3, FeedSource.guess.pageCount)
        assertEquals(3, FeedSource.fresh.pageCount)
        assertTrue((FeedSource.entries.maxOfOrNull { it.pageLimit } ?: 0) <= CatalogQuery.maxPageSize)
    }

    @Test
    fun testHotIsLogarithmicAndCapped() {
        assertTrue(
            Recommender.hot(room("x", viewers = 10_000)) - Recommender.hot(room("y", viewers = 1_000)) < 0.25,
        )
        assertEquals(1.0, Recommender.hot(room("z", viewers = 5_000_000)), 1e-9)
        assertEquals(0.0, Recommender.hot(room("w", viewers = 0)))
    }

    @Test
    fun testSeenPenaltyFadesAfterSixHours() {
        val sample = room("a")
        val recent = Taste().marking(seen = "A", now = now.minusSeconds(3_600L))
        val old = Taste().marking(seen = "a", now = now.minusSeconds(7L * 3_600L))
        assertTrue(Recommender.seenPenalty(sample, recent, now) > 0.3)
        assertEquals(0.0, Recommender.seenPenalty(sample, old, now))
    }

    @Test
    fun testBetaSamplerStaysInRangeAndMatchesMean() {
        val rng = SplitMix64(5UL)
        val draws = List(4_000) { BetaSampler.sample(alpha = 9.0, beta = 3.0, rng = rng) }
        assertTrue(draws.all { it > 0.0 && it < 1.0 })
        assertEquals(0.75, draws.sum() / draws.size.toDouble(), 0.02)
    }

    /** SplitMix64 与 Swift `UInt64` 逐位相同：加法与乘法都回绕。向量是该算法的前四个输出。 */
    @Test
    fun testSplitMix64MatchesCanonicalWords() {
        val expected = mapOf(
            0UL to listOf(
                0xE220A8397B1DCDAFuL,
                0x6E789E6AA1B965F4uL,
                0x06C45D188009454FuL,
                0xF88BB8A8724C81ECuL,
            ),
            1UL to listOf(
                0x910A2DEC89025CC1uL,
                0xBEEB8DA1658EEC67uL,
                0xF893A2EEFB32555EuL,
                0x71C18690EE42C90BuL,
            ),
            42UL to listOf(
                0xBDD732262FEB6E95uL,
                0x28EFE333B266F103uL,
                0x47526757130F9F52uL,
                0x581CE1FF0E4AE394uL,
            ),
        )
        for ((seed, words) in expected) {
            val rng = SplitMix64(seed)
            assertEquals(words, List(words.size) { rng.next() }, "seed $seed")
        }
    }

    @Test
    fun testRecommendIsDeterministicForSeedAndKeepsAllRooms() {
        val rooms = (0 until 60).map { room("r$it", viewers = it * 10, tags = listOf("t${it % 5}")) }
        val a = Recommender.recommend(rooms, Taste(), now, 42UL)
        val b = Recommender.recommend(rooms, Taste(), now, 42UL)
        val c = Recommender.recommend(rooms, Taste(), now, 43UL)
        assertEquals(a, b)
        assertNotEquals(names(a), names(c))
        assertEquals(names(rooms).toSet(), names(a).toSet())
        assertEquals(rooms.size, a.size)
    }

    @Test
    fun testLikedTagsRankAheadAndDislikedBehind() {
        val taste = trained(hits = mapOf("fav" to 12), misses = mapOf("meh" to 12))
        val rooms = (0 until 20).map { room("L$it", tags = listOf("fav")) } +
            (0 until 20).map { room("M$it", tags = listOf("meh")) } +
            (0 until 20).map { room("N$it", tags = listOf("new$it")) }
        fun meanRank(out: List<Room>, prefix: String): Double {
            val ranks = out.mapIndexedNotNull { index, item ->
                if (item.username.startsWith(prefix)) index.toDouble() else null
            }
            return ranks.sum() / ranks.size.toDouble()
        }
        for (seed in 1UL..10UL) {
            val out = Recommender.recommend(rooms, taste, now, seed)
            assertTrue(meanRank(out, "L") < 20.0, "seed $seed")
            assertTrue(meanRank(out, "M") > 40.0, "seed $seed")
            assertTrue(out.take(10).count { it.username.startsWith("L") } > 3, "seed $seed")
        }
    }

    @Test
    fun testUnknownTagsStillSurfaceAmongUndecidedOnes() {
        val rooms = (0 until 8).map { room("r$it", tags = listOf("t$it")) }
        val firsts = (1..60).map { seed ->
            Recommender.recommend(rooms, Taste(), now, seed.toULong()).first().username
        }.toSet()
        assertTrue(firsts.size > 5)
    }

    @Test
    fun testRecommendKeepsVetoedRoomsVisibilityIsTheQueryJob() {
        val taste = Taste().vetoing(Taste.tagKey("nope"), now)
        val rooms = listOf(
            room("a", tags = listOf("ok")),
            room("b", tags = listOf("Nope", "ok")),
            room("c", tags = listOf("ok")),
        )
        assertEquals(setOf("a", "b", "c"), names(Recommender.recommend(rooms, taste, now, 1UL)).toSet())
        val query = RoomQuery(hidden = taste.vetoedKeys)
        assertEquals(listOf("a", "c"), rooms.filter { query.matches(it) }.map { it.username })
    }

    @Test
    fun testSeenRoomsMoveBackButNeverDisappear() {
        val rooms = (0 until 10).map { room("r$it", viewers = 1_000 - it) }
        var taste = Taste()
        for (item in rooms) {
            taste = taste.marking(seen = item.username, now = now.minusSeconds(60L))
        }
        assertEquals(10, Recommender.recommend(rooms, taste, now, 3UL).size)
        val one = Taste().marking(seen = "r0", now = now.minusSeconds(60L))
        var lastCount = 0
        for (seed in 1UL..50UL) {
            val last = Recommender.recommend(rooms.take(4), one, now, seed).lastOrNull()?.username
            if (last == "r0") lastCount += 1
        }
        assertTrue(lastCount > 25)
    }

    @Test
    fun testHotnessOnlyBreaksTies() {
        val same = listOf(
            room("quiet", viewers = 10, tags = listOf("x"), gender = null),
            room("busy", viewers = 20_000, tags = listOf("x"), gender = null),
        )
        assertEquals("busy", Recommender.recommend(same, Taste(), now, 2UL).first().username)
        val taste = trained(hits = mapOf("liked" to 10))
        val mixed = listOf(
            room("busy", viewers = 20_000, tags = listOf("other"), gender = null),
            room("quiet", viewers = 10, tags = listOf("liked"), gender = null),
        )
        val wins = (1..40).count { seed ->
            Recommender.recommend(mixed, taste, now, seed.toULong()).first().username == "quiet"
        }
        assertTrue(wins > 30)
    }

    @Test
    fun testSmallListsNeverCrash() {
        for (n in 0 until 6) {
            val rooms = (0 until n).map { room("r$it", viewers = 100 - it) }
            assertEquals(names(rooms).toSet(), names(Recommender.recommend(rooms, Taste(), now, 9UL)).toSet(), "n=$n")
        }
    }

    @Test
    fun testArrangeDispatchesBySource() {
        val rooms = listOf(room("a", secs = 500), room("b", secs = 60))
        assertEquals(listOf("b", "a"), names(Recommender.arrange(rooms, FeedSource.fresh, Taste(), now, 1UL)))
        assertEquals(listOf("a", "b"), names(Recommender.arrange(rooms, FeedSource.favorites, Taste(), now, 1UL)))
    }

    @Test
    fun testStoredSourceMapsLegacyRecommended() {
        assertEquals(FeedSource.guess, FeedSource.restore("recommended"))
        assertEquals(FeedSource.guess, FeedSource.restore("guess"))
        assertEquals(FeedSource.favorites, FeedSource.restore("favorites"))
        assertNull(FeedSource.restore("bogus"))
        assertNull(FeedSource.restore(null))
    }

    @Test
    fun testReasonNamesBestTagsFavoritesAndFallback() {
        val taste = trained(hits = mapOf("blonde" to 6, "latina" to 3, "asmr" to 1), misses = mapOf("bad" to 8))
        val sample = room("a", tags = listOf("asmr", "latina", "blonde", "bad"))
        assertEquals("因为你常看 blonde、latina", Recommender.reason(sample, taste, emptySet(), now))
        assertEquals(
            "试试新口味",
            Recommender.reason(room("a", tags = listOf("bad", "zzz")), taste, emptySet(), now),
        )
        assertEquals("你收藏的主播", Recommender.reason(room("A", tags = emptyList()), taste, setOf("a"), now))
        val veto = taste.vetoing(Taste.tagKey("blonde"), now)
        assertEquals("因为你常看 latina、asmr", Recommender.reason(sample, veto, emptySet(), now))
    }

    @Test
    fun testRelatedPicksUnseenSimilarRooms() {
        val cur = room("cur", tags = listOf("a", "b", "c"))
        val pool = listOf(
            room("near1", tags = listOf("a", "b", "c")),
            room("near2", tags = listOf("a", "b")),
            room("far", tags = listOf("a", "x", "y", "z")),
            room("none", tags = listOf("q")),
            room("seen", tags = listOf("a", "b", "c")),
            room("cur", tags = listOf("a", "b", "c")),
        )
        val prefs = Taste().marking(seen = "seen", now = now)
        assertEquals(listOf("near1", "near2", "far"), Recommender.related(cur, pool, prefs, limit = 3))
        assertEquals(emptyList(), Recommender.related(room("t", tags = emptyList()), pool, prefs))
    }

    @Test
    fun testPromoteAfterCurrentKeepsHistoryAndCurrent() {
        val rooms = (0 until 8).map { room("r$it") }
        val store = FeedStore(catalog = rooms, currentUsername = "r2")
        val before = store.catalog
        store.promoteAfterCurrent(listOf("R6", "r4", "r1", "r2", "ghost", "r6"))
        assertEquals(listOf("r0", "r1", "r2", "r6", "r4", "r3", "r5", "r7"), names(store.catalog))
        assertEquals("r2", store.currentUsername)
        assertEquals(2, store.index)
        assertEquals(before.take(3), store.catalog.take(3))
        assertEquals(names(before).toSet(), names(store.catalog).toSet())
        store.step(-1)
        assertEquals("r1", store.current?.username)
    }

    @Test
    fun testPromoteWithoutCurrentOrMatchesIsNoop() {
        val store = FeedStore(catalog = (0 until 3).map { room("r$it") }, currentUsername = null)
        store.promoteAfterCurrent(listOf("r2"))
        assertEquals(listOf("r0", "r1", "r2"), names(store.catalog))
        val last = FeedStore(catalog = (0 until 3).map { room("r$it") }, currentUsername = "r2")
        last.promoteAfterCurrent(listOf("r0"))
        assertEquals(listOf("r0", "r1", "r2"), names(last.catalog))
    }

    @Test
    fun testAppendDedupesAndSeenDoesNotEmptyTheFeed() {
        var prefs = Taste()
        val first = (0 until 5).map { room("r$it") }
        val second = (3 until 9).map { room("r$it") }
        for (item in first + second) {
            prefs = prefs.marking(seen = item.username, now = now)
        }
        val store = FeedStore()
        store.replace(
            CatalogPage(rooms = Recommender.recommend(first, prefs, now, 1UL), total = 9, nextOffset = 5),
        )
        store.append(CatalogPage(rooms = Recommender.recommend(second, prefs, now, 2UL), total = 9, nextOffset = 11))
        assertEquals(9, store.catalog.size)
        assertEquals(9, names(store.catalog).toSet().size)
        assertFalse(store.rooms.isEmpty())
    }
}
