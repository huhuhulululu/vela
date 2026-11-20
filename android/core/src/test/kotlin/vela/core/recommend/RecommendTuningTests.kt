package vela.core.recommend

import java.time.Instant
import vela.core.base.model.FeedSource
import vela.core.base.model.Gender
import vela.core.base.model.Region
import vela.core.base.model.Room
import vela.core.base.model.ShowState
import vela.core.base.site.CatalogQuery
import vela.core.ledger.StreamerAffinity
import vela.core.ledger.WatchLedger
import vela.core.taste.DayTally
import vela.core.taste.Feedback
import vela.core.taste.TagRarity
import vela.core.taste.Taste
import vela.core.taste.TasteTargeting
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 推荐优化四项：反馈分级、常见标签降权、主播亲和度、按口味定向拉候选。 */
class RecommendTuningTests {
    private val now: Instant = Instant.ofEpochSecond(1_800_000_000)

    private fun room(
        name: String,
        viewers: Int = 100,
        tags: List<String> = listOf("x"),
        gender: Gender? = null,
    ): Room = Room(username = name, viewers = viewers, gender = gender, tags = tags, show = ShowState.publicShow)

    private fun key(tag: String): String = Taste.tagKey(tag)

    private fun meanRank(out: List<Room>, prefix: String): Double {
        val ranks = out.mapIndexedNotNull { index, item ->
            if (item.username.startsWith(prefix)) index.toDouble() else null
        }
        return ranks.sum() / ranks.size.toDouble()
    }

    @Test
    fun testGradeTiers() {
        for (seconds in listOf(0.0, 1.0, 2.99)) {
            assertEquals(Feedback(hit = 0.0, miss = 0.3), Feedback.grade(seconds), "$seconds")
        }
        for (seconds in listOf(3.0, 10.0, 19.99)) {
            assertEquals(Feedback.none, Feedback.grade(seconds), "$seconds")
        }
        assertEquals(1.0, Feedback.grade(20.0).hit, 1e-9)
        assertEquals(1.5, Feedback.grade(60.0).hit, 1e-9)
        assertEquals(2.0, Feedback.grade(180.0).hit, 1e-9)
        assertEquals(2.0, Feedback.grade(3_600.0).hit, 1e-9)
        assertEquals(Feedback.none, Feedback.grade(Double.NaN))
        assertEquals(0.0, Feedback.grade(60.0).miss)
        var last = 0.0
        var seconds = 20.0
        while (seconds <= 400.0) {
            val hit = Feedback.grade(seconds).hit
            assertTrue(hit >= last)
            last = hit
            seconds += 7.0
        }
        assertTrue(Feedback.followHit > Feedback.maxHit)
        assertEquals(3.0, Taste.followStep)
    }

    @Test
    fun testDailyOutcomeKeepsTwentyAndThreeSecondCutoffs() {
        assertEquals(false, Feedback.dailyOutcome(1.0))
        assertNull(Feedback.dailyOutcome(3.0))
        assertNull(Feedback.dailyOutcome(19.9))
        assertEquals(true, Feedback.dailyOutcome(20.0))
    }

    @Test
    fun testGradedRecordingWeightsAndDaily() {
        val sample = room("a", tags = listOf("x"), gender = Gender.female)
        val skip = Taste().recording(room = sample, watched = 1.0, source = FeedSource.guess, now = now)
        assertEquals(1.3, skip.posterior(key("x"), now).beta, 1e-9)
        assertEquals(1.15, skip.posterior(Taste.genderKey(Gender.female), now).beta, 1e-9)
        assertEquals(1, skip.beliefs[key("x")]?.misses)
        assertEquals(DayTally(hit = 0, miss = 1), skip.daily[Taste.dayKey(now)])
        val browse = Taste().recording(room = sample, watched = 10.0, source = FeedSource.guess, now = now)
        assertTrue(browse.beliefs.isEmpty())
        assertTrue(browse.daily.isEmpty())
        assertNotNull(browse.lastSeen("a"))
        val long = Taste().recording(room = sample, watched = 60.0, source = FeedSource.guess, now = now)
        assertEquals(2.5, long.posterior(key("x"), now).alpha, 1e-9)
        assertEquals(DayTally(hit = 1, miss = 0), long.daily[Taste.dayKey(now)])
        val other = Taste().recording(room = sample, watched = 180.0, source = FeedSource.popular, now = now)
        assertEquals(2.0, other.posterior(key("x"), now).alpha, 1e-9)
        assertTrue(other.daily.isEmpty())
        val paused = Taste().settingPaused(true).recording(room = sample, watched = 180.0, source = FeedSource.guess, now = now)
        assertTrue(paused.beliefs.isEmpty())
        assertNotNull(paused.lastSeen("a"))
    }

    @Test
    fun testFollowAndRejectSteps() {
        val sample = room("a")
        assertEquals(4.0, Taste().following(room = sample, now = now).posterior(key("x"), now).alpha, 1e-9)
        assertEquals(4.0, Taste().rejecting(room = sample, source = FeedSource.guess, now = now).posterior(key("x"), now).beta, 1e-9)
        val long = Taste().recording(room = sample, watched = 10_000.0, source = FeedSource.guess, now = now)
            .posterior(key("x"), now).alpha
        assertTrue(Taste().following(room = sample, now = now).posterior(key("x"), now).alpha > long)
    }

    @Test
    fun testRarityWeights() {
        val rooms = (0 until 100).map { index ->
            room("r$index", tags = listOf("teen") + (if (index < 5) listOf("piano") else emptyList()) + listOf("u$index"))
        }
        val rarity = TagRarity(rooms)
        assertEquals(100, rarity.documentFrequency(key("teen")))
        assertEquals(TagRarity.floor, rarity.weight(key("teen")), 1e-9)
        assertEquals(1.0, rarity.weight(key("u3")), 1e-9)
        assertEquals(1.0, rarity.weight(key("never")), 1e-9)
        assertTrue(rarity.weight(key("piano")) > 0.5)
        assertTrue(rarity.weight(key("piano")) < 1.0)
        assertEquals(1.0, TagRarity(emptyList()).weight(key("x")))
        assertEquals(1.0, TagRarity(listOf(room("a"))).weight(key("x")))
        assertEquals(1, TagRarity(listOf(room("a", tags = listOf("X", "x")), room("b", tags = listOf("y")))).documentFrequency(key("x")))
    }

    @Test
    fun testRecordingSplitsEvidenceByRarity() {
        val rooms = (0 until 100).map { index ->
            room("r$index", tags = listOf("teen") + (if (index < 5) listOf("piano") else emptyList()))
        }
        val rarity = TagRarity(rooms)
        val taste = Taste().recording(room = rooms[0], watched = 180.0, source = FeedSource.guess, now = now, rarity = rarity)
        val teen = taste.posterior(key("teen"), now).alpha - 1.0
        val piano = taste.posterior(key("piano"), now).alpha - 1.0
        assertEquals(2.0 * TagRarity.floor, teen, 1e-9)
        assertEquals(2.0 * rarity.weight(key("piano")), piano, 1e-9)
        assertTrue(piano > teen * 4)
        val skip = Taste().recording(room = rooms[50], watched = 1.0, source = FeedSource.guess, now = now, rarity = rarity)
        assertEquals(1.0 + 0.3 * TagRarity.floor, skip.posterior(key("teen"), now).beta, 1e-9)
    }

    @Test
    fun testRarityShrinksCommonTagInfluenceOnRanking() {
        var taste = Taste()
        repeat(6) {
            taste = taste.recording(room = room("x", tags = listOf("teen")), watched = 30.0, source = FeedSource.guess, now = now)
            taste = taste.recording(room = room("x", tags = listOf("piano")), watched = 30.0, source = FeedSource.guess, now = now)
        }
        val rooms = (0 until 40).map { room("A$it", tags = listOf("teen", "a$it")) } +
            (0 until 10).map { room("B$it", tags = listOf("piano", "b$it")) } +
            (0 until 50).map { room("C$it", tags = listOf("c$it")) }
        val signals = RankSignals(rarity = TagRarity(rooms))
        var gapPlain = 0.0
        var gapRare = 0.0
        for (seed in 1UL..20UL) {
            val plain = Recommender.recommend(rooms, taste, now, seed)
            val rare = Recommender.recommend(rooms, taste, now, seed, signals = signals)
            gapPlain += meanRank(plain, "A") - meanRank(plain, "B")
            gapRare += meanRank(rare, "A") - meanRank(rare, "B")
            assertEquals(rooms.size, rare.size)
        }
        assertTrue(kotlin.math.abs(gapPlain / 20.0) < 6.0)
        assertTrue(gapRare / 20.0 > 10.0)
    }

    @Test
    fun testEmptySignalsKeepLegacyOrder() {
        val rooms = (0 until 30).map { room("r$it", viewers = it, tags = listOf("t${it % 4}")) }
        val taste = Taste().recording(room = room("x", tags = listOf("t1")), watched = 30.0, source = FeedSource.guess, now = now)
        assertEquals(
            Recommender.recommend(rooms, taste, now, 7UL),
            Recommender.recommend(rooms, taste, now, 7UL, signals = RankSignals.none),
        )
    }

    private fun ledger(name: String, seconds: Double, visits: Int): WatchLedger {
        var value = WatchLedger()
        val times = maxOf(visits, 1)
        for (i in 0 until times) {
            val each = if (visits > 0) seconds / visits.toDouble() else seconds
            value = value.recording(
                username = name,
                seconds = each,
                start = i.toDouble() * 7_200.0,
                now = i.toDouble() * 7_200.0 + each,
            )
        }
        return value
    }

    @Test
    fun testAffinityBoundsAndZeroCases() {
        val heavy = ledger("Star", seconds = 100_000.0, visits = 40)
        assertEquals(StreamerAffinity.cap, StreamerAffinity.score(ledger = heavy, username = "star"), 1e-9)
        assertTrue(StreamerAffinity.cap < Recommender.favoriteBoost)
        assertEquals(0.0, StreamerAffinity.score(ledger = heavy, username = "nobody"))
        assertEquals(0.0, StreamerAffinity.score(ledger = heavy, username = "STAR", blocked = setOf("star")))
        assertEquals(0.0, StreamerAffinity.score(ledger = WatchLedger(), username = "star"))
        val light = StreamerAffinity.score(ledger = ledger("s", seconds = 120.0, visits = 1), username = "s")
        val more = StreamerAffinity.score(ledger = ledger("s", seconds = 1_200.0, visits = 3), username = "s")
        assertTrue(light > 0.0)
        assertTrue(more > light)
        assertTrue(more < StreamerAffinity.cap)
    }

    @Test
    fun testAffinityRanksBelowFavoritesAndSkipsBlocked() {
        val book = ledger("loved", seconds = 100_000.0, visits = 40)
            .recording(username = "banned", seconds = 100_000.0, start = 0.0, now = 1.0)
        val rooms = listOf(room("plain"), room("loved"), room("fav"), room("banned"))
        for (seed in 1UL..10UL) {
            val signals = RankSignals(ledger = book, blocked = setOf("banned"))
            val out = Recommender.recommend(
                rooms,
                Taste(),
                now,
                seed,
                favorites = setOf("fav"),
                signals = signals,
            )
            assertEquals(listOf("fav", "loved", "banned", "plain"), out.map { it.username }, "seed $seed")
        }
    }

    private fun trained(hits: Map<String, Int>, misses: Map<String, Int> = emptyMap()): Taste {
        var taste = Taste()
        for ((tag, n) in hits) {
            repeat(n) {
                taste = taste.recording(room = room("x", tags = listOf(tag)), watched = 30.0, source = FeedSource.guess, now = now)
            }
        }
        for ((tag, n) in misses) {
            repeat(n) {
                taste = taste.recording(room = room("x", tags = listOf(tag)), watched = 1.0, source = FeedSource.guess, now = now)
            }
        }
        return taste
    }

    @Test
    fun testFavoriteTagsSelection() {
        val taste = trained(
            hits = mapOf(
                "piano" to 8,
                "cosplay" to 5,
                "asmr" to 4,
                "yoga" to 3,
                "teen" to 10,
                "once" to 1,
                "mixed" to 3,
                "nope" to 9,
            ),
            misses = mapOf("mixed" to 6),
        ).vetoing(key("nope"), now)
        val rooms = (0 until 50).map { index ->
            room("r$index", tags = listOf("teen") + (if (index < 3) listOf("piano") else emptyList()))
        }
        val rarity = TagRarity(rooms)
        assertEquals(listOf("piano", "cosplay", "asmr"), TasteTargeting.favoriteTags(taste, now, rarity))
        assertEquals(listOf("teen", "piano", "cosplay"), TasteTargeting.favoriteTags(taste, now))
        assertEquals(listOf("piano", "cosplay"), TasteTargeting.favoriteTags(taste, now, rarity, limit = 2))
        assertEquals(emptyList(), TasteTargeting.favoriteTags(trained(mapOf("once" to 1)), now))
        assertEquals(emptyList(), TasteTargeting.favoriteTags(Taste(), now))
    }

    @Test
    fun testTargetedQueries() {
        val base = CatalogQuery(gender = Gender.female, region = Region.northAmerica)
        val queries = TasteTargeting.queries(listOf("piano", "#Cosplay", "piano"), base)
        assertEquals(listOf("piano", "cosplay"), queries.map { it.tag })
        assertTrue(
            queries.all {
                it.limit == CatalogQuery.maxPageSize &&
                    it.offset == 0 &&
                    it.gender == Gender.female &&
                    it.region == Region.northAmerica
            },
        )
        assertTrue(queries[0].url().toString().contains("hashtags=piano"))
        assertEquals(emptyList(), TasteTargeting.queries(listOf("piano"), CatalogQuery(tag = "user")))
        assertEquals(emptyList(), TasteTargeting.queries(emptyList()))
    }

    @Test
    fun testMergingKeepsRegularOrderAndDedupes() {
        val regular = listOf(room("a"), room("B"), room("c"))
        val pages = listOf(
            listOf(room("b"), room("d")),
            listOf(room("D"), room("e"), room("A")),
        )
        assertEquals(listOf("a", "B", "c", "d", "e"), TasteTargeting.merging(regular, pages).map { it.username })
        assertEquals(listOf("a", "B", "c"), TasteTargeting.merging(regular, emptyList()).map { it.username })
        assertEquals(listOf("b", "d", "e", "A"), TasteTargeting.merging(emptyList(), pages).map { it.username })
    }
}
