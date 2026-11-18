package vela.core.taste

import java.time.Instant
import vela.core.base.model.FeedSource
import vela.core.base.model.Room
import vela.core.base.model.ShowState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TasteRecordingOverloadTests {
    private val now: Instant = Instant.ofEpochSecond(1_800_000_000)

    private fun room(): Room =
        Room(username = "a", viewers = 10, gender = null, tags = listOf("x"), show = ShowState.publicShow)

    private fun dailyHits(taste: Taste): Int = taste.daily.values.sumOf { it.hit }

    private fun dailyMisses(taste: Taste): Int = taste.daily.values.sumOf { it.miss }

    @Test
    fun testLearnSecondsAndDailySecondsAreIndependent() {
        val sample = room()
        val split = Taste().recording(
            room = sample,
            learnSeconds = 60.0,
            daily = 1.0,
            source = FeedSource.guess,
            now = now,
        )
        assertEquals(1 + Feedback.grade(60.0).hit, split.posterior(Taste.tagKey("x"), now).alpha, 1e-9)
        assertEquals(0, dailyHits(split))
        assertEquals(1, dailyMisses(split))

        val learnOnly = Taste().recording(
            room = sample,
            learnSeconds = 60.0,
            daily = 10.0,
            source = FeedSource.guess,
            now = now,
        )
        assertTrue(learnOnly.daily.isEmpty())
        assertEquals(1 + Feedback.grade(60.0).hit, learnOnly.posterior(Taste.tagKey("x"), now).alpha, 1e-9)

        val dailyOnly = Taste().recording(
            room = sample,
            learnSeconds = 10.0,
            daily = 40.0,
            source = FeedSource.guess,
            now = now,
        )
        assertTrue(dailyOnly.beliefs.isEmpty())
        assertEquals(1, dailyHits(dailyOnly))
        assertNotNull(dailyOnly.lastSeen("a"))

        for (seconds in listOf(1.0, 10.0, 25.0, 60.0)) {
            val original = Taste().recording(room = sample, watched = seconds, source = FeedSource.guess, now = now)
            val overload = Taste().recording(
                room = sample,
                learnSeconds = seconds,
                daily = seconds,
                source = FeedSource.guess,
                now = now,
            )
            assertEquals(original, overload, "watched $seconds")
            val other = Taste().recording(room = sample, watched = seconds, source = FeedSource.popular, now = now)
            val otherOverload = Taste().recording(
                room = sample,
                learnSeconds = seconds,
                daily = seconds,
                source = FeedSource.popular,
                now = now,
            )
            assertEquals(other, otherOverload, "popular $seconds")
        }

        val paused = Taste().settingPaused(true).recording(
            room = sample,
            learnSeconds = 60.0,
            daily = 1.0,
            source = FeedSource.guess,
            now = now,
        )
        assertTrue(paused.beliefs.isEmpty())
        assertTrue(paused.daily.isEmpty())
        assertNotNull(paused.lastSeen("a"))
    }
}
