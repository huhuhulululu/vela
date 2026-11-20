package vela.core.taste

import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneId
import java.util.Comparator
import vela.core.base.model.FeedSource
import vela.core.base.model.Gender
import vela.core.base.model.Prefs
import vela.core.base.model.Room
import vela.core.base.model.ShowState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TasteTests {
    private val now: Instant = Instant.ofEpochSecond(1_800_000_000)
    private val utc: ZoneId = ZoneId.of("UTC")

    private fun room(
        name: String,
        tags: List<String> = listOf("x"),
        gender: Gender? = Gender.female,
    ): Room = Room(username = name, viewers = 10, gender = gender, tags = tags, show = ShowState.publicShow)

    private fun key(tag: String): String = Taste.tagKey(tag)

    @Test
    fun testHitMissAndTheDeadZoneBetweenThreeAndTwentySeconds() {
        val sample = room("a", tags = listOf("x", "y"))
        val hit = Taste().recording(room = sample, watched = 25.0, source = FeedSource.guess, now = now)
        val hitEvidence = Feedback.grade(25.0).hit
        assertEquals(1 + hitEvidence, hit.posterior(key("x"), now).alpha, 1e-9)
        assertEquals(1, hit.beliefs[key("x")]?.hits)
        assertEquals(1 + hitEvidence / 2, hit.posterior(Taste.genderKey(Gender.female), now).alpha, 1e-9)
        val miss = Taste().recording(room = sample, watched = 1.0, source = FeedSource.guess, now = now)
        assertEquals(1 + Feedback.skipMiss, miss.posterior(key("x"), now).beta, 1e-9)
        assertEquals(1, miss.beliefs[key("x")]?.misses)
        for (dwell in listOf(3.0, 10.0, 19.9)) {
            val mid = Taste().recording(room = sample, watched = dwell, source = FeedSource.guess, now = now)
            assertTrue(mid.beliefs.isEmpty(), "dwell $dwell")
            assertTrue(mid.daily.isEmpty())
            assertNotNull(mid.lastSeen("A"))
        }
        assertNotNull(Taste().recording(room = sample, watched = 20.0, source = FeedSource.guess, now = now).beliefs[key("x")])
    }

    @Test
    fun testOtherSourcesLearnAtHalfWeightAndStayOutOfDailyStats() {
        val sample = room("a")
        val taste = Taste().recording(room = sample, watched = 30.0, source = FeedSource.popular, now = now)
        assertEquals(1 + Feedback.grade(30.0).hit / 2, taste.posterior(key("x"), now).alpha, 1e-9)
        assertTrue(taste.daily.isEmpty())
    }

    @Test
    fun testRecordingDoesNotMutateInput() {
        val taste = Taste()
        taste.recording(room = room("a"), watched = 30.0, source = FeedSource.guess, now = now)
        assertEquals(Taste(), taste)
    }

    @Test
    fun testNotInterestedIsAStrongMissAndCountsInDaily() {
        val taste = Taste().rejecting(room = room("a"), source = FeedSource.guess, now = now)
        assertEquals(1 + Taste.rejectStep, taste.posterior(key("x"), now).beta, 1e-9)
        assertEquals(DayTally(hit = 0, miss = 1), taste.daily[Taste.dayKey(now)])
        assertNotNull(taste.lastSeen("a"))
        assertTrue(Taste().rejecting(room = room("a"), source = FeedSource.popular, now = now).daily.isEmpty())
    }

    @Test
    fun testFollowingIsStrongerThanOneHit() {
        val followed = Taste().following(room = room("a"), now = now).posterior(key("x"), now).alpha
        val watched = Taste().recording(room = room("a"), watched = 90.0, source = FeedSource.guess, now = now)
            .posterior(key("x"), now).alpha
        assertTrue(followed > watched)
    }

    @Test
    fun testPausedStopsLearningButKeepsSeen() {
        val paused = Taste().settingPaused(true)
        val sample = room("a")
        val updated = listOf(
            paused.recording(room = sample, watched = 60.0, source = FeedSource.guess, now = now),
            paused.rejecting(room = sample, source = FeedSource.guess, now = now),
            paused.following(room = sample, now = now),
        )
        for (taste in updated) {
            assertTrue(taste.beliefs.isEmpty())
            assertTrue(taste.daily.isEmpty())
            assertTrue(taste.paused)
        }
        assertNotNull(paused.recording(room = sample, watched = 60.0, source = FeedSource.guess, now = now).lastSeen("a"))
        val trained = Taste().recording(room = sample, watched = 60.0, source = FeedSource.guess, now = now)
            .settingPaused(true)
        assertTrue(trained.mean(key("x"), now) > 0.5)
    }

    @Test
    fun testEvidenceHalvesEverySevenDaysTowardThePrior() {
        val taste = Taste().boosting(key("x"), now = now)
        assertEquals(1 + Taste.boostStep, taste.posterior(key("x"), now).alpha, 1e-9)
        val later = now.plusSeconds(Taste.halfLife.toLong())
        assertEquals(1 + Taste.boostStep / 2, taste.posterior(key("x"), later).alpha, 1e-9)
        val old = taste.posterior(key("x"), now.plusSeconds(70L * 86_400))
        assertEquals(1.0, old.alpha, 0.01)
        assertEquals(1.0, old.beta, 1e-9)
    }

    @Test
    fun testNewEvidenceDecaysOldBeforeAdding() {
        val taste = Taste().boosting(key("x"), now = now)
        val later = now.plusSeconds(Taste.halfLife.toLong())
        val next = taste.recording(room = room("a"), watched = 30.0, source = FeedSource.guess, now = later)
        assertEquals(
            1 + Taste.boostStep / 2 + Feedback.grade(30.0).hit,
            next.posterior(key("x"), later).alpha,
            1e-9,
        )
    }

    @Test
    fun testBoostVetoRemove() {
        var taste = Taste().vetoing(key("x"), now = now)
        assertTrue(taste.isVetoed(key("x")))
        assertTrue(taste.isVetoed(room("a", tags = listOf("X"))))
        taste = taste.boosting(key("x"), now = now)
        assertFalse(taste.isVetoed(key("x")))
        assertTrue(taste.mean(key("x"), now) > 0.7)
        assertNull(taste.removing(key("x")).beliefs[key("x")])
        val vetoed = Taste().vetoing(key("y"), now = now)
        assertTrue(vetoed.isVetoed(key("y")))
        assertTrue(vetoed.capped(now.plusSeconds(400L * 86_400)).isVetoed(key("y")))
    }

    @Test
    fun testResetAndEraseKeepOnlyWhatTheyShould() {
        val taste = Taste().recording(room = room("a"), watched = 30.0, source = FeedSource.guess, now = now)
            .settingPaused(true)
        val reset = taste.resettingProfile()
        assertTrue(reset.beliefs.isEmpty())
        assertTrue(reset.daily.isEmpty())
        assertNotNull(reset.lastSeen("a"))
        assertTrue(reset.paused)
        val erased = taste.erased()
        assertEquals(Taste(paused = true), erased)
        assertNull(erased.lastSeen("a"))
    }

    @Test
    fun testCorruptOversizedAndUnwritable() {
        val dir = Files.createTempDirectory("vela-taste-")
        try {
            val url = dir.resolve("t.json")
            Files.write(url, "{nope".toByteArray(Charsets.UTF_8))
            assertEquals(Taste(), TasteStore(url).load(now))
            Files.write(url, ByteArray(Prefs.maxFileBytes + 1) { 0x20 })
            assertEquals(Taste(), TasteStore(url).load(now))
            assertFalse(TasteStore(Path.of("/dev/null/x/t.json")).save(Taste()))
        } finally {
            deleteRecursively(dir)
        }
    }

    @Test
    fun testBeliefCapKeepsStrongestAndVetoed() {
        var taste = Taste().vetoing(key("keepme"), now = now)
        for (i in 0 until 700) {
            taste = taste.recording(
                room = room("r$i", tags = listOf("tag$i"), gender = null),
                watched = 30.0,
                source = FeedSource.guess,
                now = now,
            )
        }
        taste = taste.boosting(key("tag699"), now = now)
        assertTrue(taste.beliefs.size <= Taste.maxBeliefs)
        assertTrue(taste.isVetoed(key("keepme")))
        assertNotNull(taste.beliefs[key("tag699")])
    }

    @Test
    fun testLongNamesClippedAndFileStaysSmall() {
        val long = "x".repeat(500)
        val taste = Taste().recording(
            room = Room(username = long, viewers = 1, tags = listOf(long), show = ShowState.publicShow),
            watched = 30.0,
            source = FeedSource.guess,
            now = now,
        )
        assertTrue(taste.beliefs.keys.all { it.length <= Prefs.maxNameLength + 2 })
        var big = Taste()
        for (i in 0 until 2_000) {
            big = big.vetoing("t:some-fairly-long-tag-name-number-$i", now)
        }
        val url = Files.createTempFile("vela-taste-", ".json")
        try {
            assertTrue(TasteStore(url).save(big, now))
            assertTrue(Files.size(url) < 150_000)
            assertEquals(Taste.maxBeliefs, TasteStore(url).load(now).beliefs.size)
        } finally {
            Files.deleteIfExists(url)
        }
    }

    @Test
    fun testDailyCountsCapAtThirtyDays() {
        var taste = Taste()
        for (day in 0 until 45) {
            taste = taste.recording(
                room = room("a"),
                watched = 30.0,
                source = FeedSource.guess,
                now = now.plusSeconds(day.toLong() * 86_400),
            )
        }
        assertEquals(Taste.maxDays, taste.daily.size)
        assertNotNull(taste.daily[Taste.dayKey(now.plusSeconds(44L * 86_400))])
        assertNull(taste.daily[Taste.dayKey(now)])
        val two = taste.recording(
            room = room("a"),
            watched = 1.0,
            source = FeedSource.guess,
            now = now.plusSeconds(44L * 86_400),
        )
        assertEquals(DayTally(hit = 1, miss = 1), two.daily[Taste.dayKey(now.plusSeconds(44L * 86_400))])
    }

    @Test
    fun testSummaryHitRateNeedsEnoughData() {
        var taste = Taste()
        repeat(4) { taste = taste.recording(room = room("a"), watched = 30.0, source = FeedSource.guess, now = now) }
        repeat(5) { taste = taste.recording(room = room("a"), watched = 1.0, source = FeedSource.guess, now = now) }
        assertNull(taste.summary(now, utc).hitRate)
        taste = taste.recording(
            room = room("a"),
            watched = 1.0,
            source = FeedSource.guess,
            now = now.minusSeconds(86_400),
        )
        val summary = taste.summary(now, utc)
        assertEquals(0.4, summary.hitRate ?: -1.0, 1e-9)
        assertEquals(7, summary.week.size)
        assertEquals(4, summary.week.last().hit)
        assertEquals(1, summary.week[5].miss)
        val old = Taste().recording(
            room = room("a"),
            watched = 30.0,
            source = FeedSource.guess,
            now = now.minusSeconds(8L * 86_400),
        )
        assertEquals(0, old.summary(now, utc).week.sumOf { it.total })
    }

    @Test
    fun testSummarySplitsLikedDislikedAndGender() {
        var taste = Taste()
        repeat(5) {
            taste = taste.recording(
                room = room("a", tags = listOf("loved")),
                watched = 30.0,
                source = FeedSource.guess,
                now = now,
            )
        }
        repeat(15) {
            taste = taste.recording(
                room = room("b", tags = listOf("hated")),
                watched = 1.0,
                source = FeedSource.guess,
                now = now,
            )
        }
        taste = taste.vetoing(key("vetoed"), now = now).recording(
            room = room("c", tags = listOf("ok"), gender = null),
            watched = 30.0,
            source = FeedSource.guess,
            now = now,
        )
        val summary = taste.summary(now, utc)
        assertEquals(listOf("loved", "ok"), summary.liked.map { it.name })
        assertEquals(5, summary.liked.first().hits)
        assertEquals(setOf("hated", "vetoed"), summary.disliked.map { it.name }.toSet())
        assertEquals(Taste.genderKey(Gender.female), summary.genders.first().key)
        assertEquals(4, summary.tagCount)
    }

    @Test
    fun testSuggestedTagsAreCommonAndNew() {
        val rooms = listOf(
            room("a", tags = listOf("Blonde", "latina")),
            room("b", tags = listOf("blonde", "milf")),
            room("c", tags = listOf("blonde", "latina", "known")),
        )
        val taste = Taste().boosting(key("known"), now = now)
        assertEquals(listOf("blonde", "latina", "milf"), taste.suggestedTags(rooms))
        assertEquals(listOf("blonde", "latina"), taste.suggestedTags(rooms, limit = 2))
    }
}

private fun deleteRecursively(path: Path) {
    if (!Files.exists(path)) return
    Files.walk(path).use { stream ->
        stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
    }
}
