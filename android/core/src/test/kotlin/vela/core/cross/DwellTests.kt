package vela.core.cross

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import vela.core.base.model.Gender
import vela.core.base.model.Room
import vela.core.base.model.ShowState
import vela.core.base.site.CatalogPage
import vela.core.feed.FeedStore
import vela.core.hls.PlaybackPolicy
import vela.core.ledger.DwellTracker
import vela.core.taste.Taste

class DwellTests {
    private val t0: Instant = Instant.ofEpochSecond(1_800_000_000)

    private fun room(name: String, secs: Int? = null): Room =
        Room(username = name, viewers = 10, gender = Gender.female, tags = listOf("x"), show = ShowState.publicShow, secondsOnline = secs)

    private fun at(seconds: Long): Instant = t0.plusSeconds(seconds)

    /** 与 StageModel.updateFeed 同一判定：当前用户名变了才结算并换记账。 */
    private fun change(
        feed: FeedStore,
        tracker: DwellTracker,
        now: Instant,
        body: (FeedStore) -> Unit,
    ): DwellTracker.Settled? {
        val before = feed.current?.username
        body(feed)
        if (feed.current?.username == before) return null
        return tracker.switchTo(feed.current, now)
    }

    @Test
    fun testSwitchSettlesPreviousRoomNotNewOne() {
        val feed = FeedStore()
        val tracker = DwellTracker()
        val page = CatalogPage(rooms = listOf(room("a"), room("b")), total = 2, nextOffset = 2)
        assertNull(change(feed, tracker, at(0)) { it.replace(page) })
        assertEquals("a", tracker.room?.username)
        tracker.setActive(true, at(0))
        val settled = change(feed, tracker, at(25)) { it.step(1) }
        assertEquals("a", settled?.room?.username)
        assertEquals(25.0, settled?.dwell ?: 0.0, 0.001)
        assertEquals("b", tracker.room?.username)
    }

    @Test
    fun testFirstRoomIsRecordedWhenLeft() {
        val tracker = DwellTracker()
        assertNull(tracker.switchTo(room("first"), at(0)))
        tracker.setActive(true, at(0))
        val settled = tracker.switchTo(room("second"), at(70))
        assertEquals("first", settled?.room?.username)
        val taste = Taste().recording(room = settled!!.room, watched = settled.dwell, source = vela.core.base.model.FeedSource.guess, now = at(70))
        assertEquals(at(70), taste.lastSeen("first"))
    }

    @Test
    fun testRelatedFiresOnlyAfterThresholdInSameRoom() {
        val tracker = DwellTracker()
        tracker.switchTo(room("a"), at(0))
        tracker.setActive(true, at(0))
        assertEquals(DwellTracker.RelatedPlan.Wait(20.0), tracker.relatedPlan("a", at(10)))
        assertEquals(DwellTracker.RelatedPlan.Due, tracker.relatedPlan("A", at(30)))
        assertEquals(DwellTracker.RelatedPlan.None, tracker.relatedPlan("b", at(30)))
        tracker.markRelatedDone()
        assertEquals(DwellTracker.RelatedPlan.None, tracker.relatedPlan("a", at(31)))
        tracker.switchTo(room("b"), at(31))
        assertEquals(DwellTracker.RelatedPlan.None, tracker.relatedPlan("a", at(40)))
    }

    @Test
    fun testOnlyActiveTimeCounts() {
        val tracker = DwellTracker()
        tracker.switchTo(room("a"), at(0))
        tracker.setActive(true, at(0))
        tracker.setActive(false, at(10))
        tracker.setActive(false, at(50))
        tracker.setActive(true, at(110))
        tracker.setActive(true, at(120))
        assertEquals(35.0, tracker.dwell(at(135)), 0.001)
        val settled = tracker.switchTo(null, at(135))
        assertEquals(35.0, settled?.dwell ?: 0.0, 0.001)
    }

    @Test
    fun testPausedRoomDoesNotReachRelatedThreshold() {
        val tracker = DwellTracker()
        tracker.switchTo(room("a"), at(0))
        tracker.setActive(true, at(0))
        tracker.setActive(false, at(5))
        assertEquals(DwellTracker.RelatedPlan.Idle, tracker.relatedPlan("a", at(600)))
        tracker.setActive(true, at(600))
        assertEquals(DwellTracker.RelatedPlan.Wait(25.0), tracker.relatedPlan("a", at(600)))
    }

    @Test
    fun testDwellOnlyCountsWhileCurrentRoomActuallyPlays() {
        assertTrue(PlaybackPolicy.dwellActive(allowed = true, playing = "A", current = "a"))
        assertFalse(PlaybackPolicy.dwellActive(allowed = true, playing = null, current = "a"))
        assertFalse(PlaybackPolicy.dwellActive(allowed = true, playing = "old", current = "a"))
        assertFalse(PlaybackPolicy.dwellActive(allowed = false, playing = "a", current = "a"))
        assertFalse(PlaybackPolicy.dwellActive(allowed = true, playing = "a", current = null))
    }

    @Test
    fun testRoomThatNeverPlayedSettlesNothingAndNeverTriggersRelated() {
        val tracker = DwellTracker()
        tracker.switchTo(room("a"), at(0))
        assertEquals(DwellTracker.RelatedPlan.Idle, tracker.relatedPlan("a", at(120)))
        assertEquals(0.0, tracker.dwell(at(120)), 0.0)
        tracker.setActive(true, at(120))
        tracker.markFailed()
        assertNull(tracker.switchTo(room("b"), at(200)))
    }

    @Test
    fun testDiscardedRoomGivesNoPositiveSignalEvenAfterPlaybackReady() {
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
    fun testFailedRoomLeavesNoLearningSignal() {
        val tracker = DwellTracker()
        tracker.switchTo(room("bad"), at(0))
        tracker.markFailed()
        assertNull(tracker.switchTo(room("next"), at(2)))
        tracker.setActive(true, at(2))
        tracker.markFailed()
        tracker.clearFailure()
        assertEquals("next", tracker.finish(at(10))?.room?.username)
    }

    @Test
    fun testFinishSettlesOnceAndEmpties() {
        val tracker = DwellTracker()
        tracker.switchTo(room("a"), at(0))
        tracker.setActive(true, at(0))
        assertEquals(4.0, tracker.finish(at(4))?.dwell ?: 0.0, 0.001)
        assertNull(tracker.finish(at(9)))
    }
}
