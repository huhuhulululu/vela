package vela.core.ledger

import java.time.Instant
import vela.core.base.model.Gender
import vela.core.base.model.Room
import vela.core.base.model.ShowState
import vela.core.base.model.timeIntervalSince1970
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 观看账本的口径：暂停学习的时段不计、真实开始时刻、第三次观看的 60 秒检查点、作废后的停留。 */
class WatchAccountingTests {
    private val t0: Instant = Instant.ofEpochSecond(1_800_000_000)

    private fun at(seconds: Long): Instant = t0.plusSeconds(seconds)

    private fun room(name: String = "a"): Room =
        Room(username = name, viewers = 10, gender = Gender.female, tags = listOf("x"), show = ShowState.publicShow)

    private fun playing(tracker: DwellTracker, start: Long = 0) {
        tracker.switchTo(room(), at(start))
        tracker.setActive(true, at(start))
    }

    @Test
    fun testPausedSegmentIsExcludedFromLedgerSeconds() {
        val tracker = DwellTracker()
        playing(tracker)
        tracker.setLedgerExcluded(true, at(30))
        assertEquals(30.0, tracker.ledgerDwell(at(100)), 0.001, "排除段内冻结")
        tracker.setLedgerExcluded(false, at(100))
        assertEquals(50.0, tracker.ledgerDwell(at(120)), 0.001)
        val settled = tracker.finish(at(130))
        assertEquals(130.0, settled?.dwell ?: 0.0, 0.001)
        assertEquals(60.0, settled?.ledgerSeconds ?: 0.0, 0.001)
    }

    @Test
    fun testStillExcludedWhenSettledKeepsOnlyPreSegment() {
        val tracker = DwellTracker()
        playing(tracker)
        tracker.setLedgerExcluded(true, at(20))
        val settled = tracker.switchTo(room("b"), at(500))
        assertEquals(20.0, settled?.ledgerSeconds ?: 0.0, 0.001)
    }

    @Test
    fun testExclusionResetsOnRoomSwitchAndIsIdempotent() {
        val tracker = DwellTracker()
        playing(tracker)
        tracker.setLedgerExcluded(true, at(10))
        tracker.setLedgerExcluded(true, at(50))
        tracker.setLedgerExcluded(false, at(60))
        tracker.setLedgerExcluded(false, at(70))
        assertEquals(20.0, tracker.ledgerDwell(at(70)), 0.001)
        tracker.switchTo(room("b"), at(70))
        tracker.setActive(true, at(70))
        assertEquals(10.0, tracker.ledgerDwell(at(80)), 0.001)
    }

    @Test
    fun testExclusionAcrossInactiveGap() {
        val tracker = DwellTracker()
        playing(tracker)
        tracker.setActive(false, at(40))
        tracker.setLedgerExcluded(true, at(40))
        tracker.setActive(true, at(100))
        tracker.setLedgerExcluded(false, at(130))
        assertEquals(40.0, tracker.ledgerDwell(at(130)), 0.001)
    }

    @Test
    fun testStartedAtIsFirstActiveAndSurvivesPauses() {
        val tracker = DwellTracker()
        tracker.switchTo(room(), at(0))
        assertNull(tracker.startedAt)
        tracker.setActive(true, at(5))
        tracker.setActive(false, at(65))
        tracker.setActive(true, at(300))
        assertEquals(at(5), tracker.startedAt)
        val settled = tracker.finish(at(400))
        assertEquals(at(5), settled?.start)
        assertNull(tracker.startedAt)
    }

    @Test
    fun testNeverPlayedHasNoStart() {
        val tracker = DwellTracker()
        tracker.switchTo(room(), at(0))
        assertNull(tracker.finish(at(10))?.start)
    }

    @Test
    fun testLedgerVisitGapUsesRealStart() {
        val first = WatchLedger().recording(
            username = "a",
            seconds = 70.0,
            start = t0.timeIntervalSince1970(),
            now = t0.timeIntervalSince1970(),
        )
        val start = t0.timeIntervalSince1970() + 31 * 60
        val second = first.recording(username = "a", seconds = 70.0, start = start, now = start + 3 * 3600)
        assertEquals(2, second.entries["a"]?.visitCount)
        assertEquals(start, second.entries["a"]?.lastVisitStart)
    }

    @Test
    fun testDiscardedIsExposedAndSettlesNothing() {
        val tracker = DwellTracker()
        playing(tracker)
        assertFalse(tracker.isDiscarded)
        tracker.discard()
        assertTrue(tracker.isDiscarded)
        assertNull(tracker.finish(at(900)))
    }

    private fun ledgerWithVisits(count: Int): WatchLedger {
        var ledger = WatchLedger()
        for (i in 0 until count) {
            val start = t0.timeIntervalSince1970() + i.toDouble() * 3600
            ledger = ledger.recording(username = "a", seconds = 70.0, start = start, now = start + 70)
        }
        return ledger
    }

    @Test
    fun testWakesAtSixtySecondsWhenThirdVisitWouldQualify() {
        val ledger = ledgerWithVisits(2)
        val liveStart = t0.timeIntervalSince1970() + 3 * 3600
        val wait = ledger.secondsUntilNextSuggestCheck(
            username = "a",
            favorited = false,
            blocked = false,
            liveSeconds = 15.0,
            liveStart = liveStart,
        )
        assertEquals(45.0, wait ?: 0.0, 0.001)
        assertTrue(
            ledger.shouldSuggest(
                username = "a",
                favorited = false,
                blocked = false,
                liveSeconds = 60.0,
                liveStart = liveStart,
            ),
        )
    }

    @Test
    fun testNoVisitCheckpointWhenOnlyOneVisitRecorded() {
        val ledger = ledgerWithVisits(1)
        val wait = ledger.secondsUntilNextSuggestCheck(
            username = "a",
            favorited = false,
            blocked = false,
            liveSeconds = 15.0,
            liveStart = t0.timeIntervalSince1970() + 3 * 3600,
        )
        assertEquals(600.0 - 70 - 15, wait ?: 0.0, 0.001, "只剩累计 10 分钟这个点")
    }

    @Test
    fun testNoVisitCheckpointInsideTheGapWindow() {
        val ledger = ledgerWithVisits(2)
        val liveStart = t0.timeIntervalSince1970() + 3600 + 10 * 60
        val wait = ledger.secondsUntilNextSuggestCheck(
            username = "a",
            favorited = false,
            blocked = false,
            liveSeconds = 5.0,
            liveStart = liveStart,
        )
        assertEquals(600.0 - 140 - 5, wait ?: 0.0, 0.001)
    }

    @Test
    fun testNoCheckpointAfterSixtySecondsOrWhenSettled() {
        val ledger = ledgerWithVisits(2)
        val liveStart = t0.timeIntervalSince1970() + 3 * 3600
        assertNull(
            ledger.secondsUntilNextSuggestCheck(
                username = "a",
                favorited = false,
                blocked = false,
                liveSeconds = 61.0,
                liveStart = liveStart,
            ),
            "已够三次，已可提示",
        )
        assertNull(
            ledger.secondsUntilNextSuggestCheck(
                username = "a",
                favorited = true,
                blocked = false,
                liveSeconds = 5.0,
                liveStart = liveStart,
            ),
        )
        assertNull(
            ledger.markingSuggested("a").secondsUntilNextSuggestCheck(
                username = "a",
                favorited = false,
                blocked = false,
                liveSeconds = 5.0,
                liveStart = liveStart,
            ),
        )
    }
}
