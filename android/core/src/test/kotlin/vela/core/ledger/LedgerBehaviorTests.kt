package vela.core.ledger

import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import vela.core.base.io.PreferenceQuarantine
import vela.core.base.model.FeedSource
import vela.core.base.model.Gender
import vela.core.base.model.Room
import vela.core.base.model.ShowState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Swift 账本组没有单独断言、但本包类型必须稳住的行为。
 * 方法名故意不与任何 Swift `test…` 重名，避免 parity 把别组的用例算到这里。
 */
class LedgerBehaviorTests {
    private val t0: Instant = Instant.ofEpochSecond(1_800_000_000)

    private fun at(seconds: Long): Instant = t0.plusSeconds(seconds)

    private fun room(name: String): Room =
        Room(username = name, viewers = 1, gender = Gender.female, tags = listOf("x"), show = ShowState.publicShow)

    @Test
    fun ledgerShouldSettleOnSourceChange() {
        assertFalse(DwellTracker.shouldSettle(before = "a", after = "a", sourceChanged = false))
        assertTrue(DwellTracker.shouldSettle(before = "a", after = "a", sourceChanged = true))
        assertTrue(DwellTracker.shouldSettle(before = "a", after = "b", sourceChanged = false))
        assertFalse(DwellTracker.shouldSettle(before = null, after = null, sourceChanged = true))
        val tracker = DwellTracker()
        tracker.switchTo(room("a"), at(0))
        tracker.setActive(true, at(0))
        val settled = tracker.switchTo(room("a"), at(40))
        assertEquals(40.0, settled?.dwell ?: 0.0, 0.001)
        assertEquals(0.0, tracker.dwell(at(40)), 0.001)
    }

    @Test
    fun ledgerRelatedPlanOnceAndIdleWhilePaused() {
        val tracker = DwellTracker()
        tracker.switchTo(room("a"), at(0))
        tracker.setActive(true, at(0))
        assertEquals(DwellTracker.RelatedPlan.Wait(20.0), tracker.relatedPlan("a", at(10)))
        assertEquals(DwellTracker.RelatedPlan.None, tracker.relatedPlan("b", at(10)))
        tracker.setActive(false, at(10))
        assertEquals(DwellTracker.RelatedPlan.Idle, tracker.relatedPlan("a", at(600)))
        tracker.setActive(true, at(600))
        assertEquals(DwellTracker.RelatedPlan.Wait(20.0), tracker.relatedPlan("a", at(600)))
        assertEquals(DwellTracker.RelatedPlan.Due, tracker.relatedPlan("A", at(630)))
        tracker.markRelatedDone()
        assertEquals(DwellTracker.RelatedPlan.None, tracker.relatedPlan("a", at(631)))
        tracker.switchTo(room("b"), at(631))
        tracker.setActive(true, at(631))
        assertEquals(DwellTracker.RelatedPlan.None, tracker.relatedPlan("a", at(640)))
        assertEquals(DwellTracker.RelatedPlan.Wait(30.0), tracker.relatedPlan("b", at(631)))
    }

    @Test
    fun ledgerListOriginPrefersInstalling() {
        assertEquals(FeedSource.popular, ListOrigin.dwell(loaded = FeedSource.popular, installing = null))
        assertEquals(FeedSource.guess, ListOrigin.dwell(loaded = FeedSource.popular, installing = FeedSource.guess))
    }

    @Test
    fun ledgerFailedRoomSettlesNothingUntilCleared() {
        val tracker = DwellTracker()
        tracker.switchTo(room("bad"), at(0))
        tracker.setActive(true, at(0))
        tracker.markFailed()
        assertNull(tracker.switchTo(room("next"), at(2)))
        tracker.setActive(true, at(2))
        tracker.markFailed()
        tracker.clearFailure()
        assertEquals("next", tracker.finish(at(10))?.room?.username)
        assertNull(tracker.finish(at(12)))
    }

    @Test
    fun ledgerDiscardSurvivesClearFailure() {
        val tracker = DwellTracker()
        tracker.switchTo(room("a"), at(0))
        tracker.setActive(true, at(0))
        tracker.discard()
        tracker.clearFailure()
        assertNull(tracker.switchTo(room("b"), at(90)))
        tracker.setActive(true, at(90))
        assertEquals(10.0, tracker.switchTo(null, at(100))?.dwell ?: 0.0, 0.001)
    }

    @Test
    fun ledgerKeyTrimsAndEmptyNameIsIgnored() {
        assertEquals("amy", WatchLedger.key("  Amy \n"))
        val skipped = WatchLedger().recording(username = "   ", seconds = 10.0, start = 1.0, now = 2.0)
        assertTrue(skipped.entries.isEmpty())
        val kept = WatchLedger().recording(username = "  Amy ", seconds = 70.0, start = 1.0, now = 2.0)
        assertEquals(1, kept.entries["amy"]?.visitCount)
        assertEquals(70.0, kept.entries["amy"]?.totalSeconds)
    }

    @Test
    fun ledgerAffinityCapBlockedAndOrder() {
        val heavy = visits("Star", seconds = 100_000.0, count = 40)
        assertEquals(StreamerAffinity.cap, StreamerAffinity.score(heavy, "star"), 1e-9)
        assertEquals(0.0, StreamerAffinity.score(heavy, "nobody"))
        assertEquals(0.0, StreamerAffinity.score(heavy, "STAR", blocked = setOf("star")))
        assertEquals(0.0, StreamerAffinity.score(WatchLedger(), "star"))
        val light = StreamerAffinity.score(visits("s", seconds = 120.0, count = 1), "s")
        val more = StreamerAffinity.score(visits("s", seconds = 1_200.0, count = 3), "s")
        assertTrue(light > 0.0)
        assertTrue(more > light)
        assertTrue(more < StreamerAffinity.cap)
    }

    @Test
    fun ledgerStoreQuarantinesCorruptAndEraseClearsReadOnly() {
        val dir = Files.createTempDirectory("vela-ledger-store-")
        try {
            val now = Instant.ofEpochSecond(1_700_000_000)
            val url = dir.resolve("watch-ledger.json")
            Files.write(url, "[]".toByteArray(Charsets.UTF_8))
            val store = WatchLedgerStore(url)
            assertEquals(WatchLedger(), store.load(now))
            assertFalse(store.isReadOnly)
            assertEquals(
                listOf("watch-ledger.json.bad-1700000000"),
                PreferenceQuarantine.backups(url).map { it.fileName.toString() },
            )
            assertTrue(store.save(WatchLedger().markingSuggested("amy", now = 5.0)))
            val loaded = store.load(now)
            assertEquals(true, loaded.entries["amy"]?.suggested)

            val broken = dir.resolve("broken.json")
            val bad = "broken".toByteArray(Charsets.UTF_8)
            Files.write(broken, bad)
            val locked = WatchLedgerStore(broken)
            locked.moveItem = { _, _ -> throw java.io.IOException("fileWriteNoPermission") }
            assertEquals(WatchLedger(), locked.load(now))
            assertTrue(locked.isReadOnly)
            assertFalse(locked.save(WatchLedger()))
            assertTrue(bad.contentEquals(Files.readAllBytes(broken)))
            assertTrue(locked.erase())
            assertFalse(locked.isReadOnly)
            assertFalse(Files.exists(broken))
            assertTrue(locked.save(WatchLedger()))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun ledgerDefaultFileName() {
        val path = WatchLedgerStore.defaultURL(Path.of("Vela"))
        assertEquals("watch-ledger.json", path.fileName.toString())
        assertEquals("Vela", path.parent?.toString())
    }

    private fun visits(name: String, seconds: Double, count: Int): WatchLedger {
        var ledger = WatchLedger()
        val times = maxOf(count, 1)
        for (i in 0 until times) {
            val each = if (count > 0) seconds / count.toDouble() else seconds
            val start = i.toDouble() * 7_200
            ledger = ledger.recording(username = name, seconds = each, start = start, now = start + each)
        }
        return ledger
    }
}
