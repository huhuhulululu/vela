package vela.core.hls

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlaybackFlowFixTests {
    private fun stuck(
        bufferAdvancing: Boolean = false,
        renewing: Boolean = false,
        retry: Boolean = true,
        playing: Boolean = false,
        refreshing: Boolean = false,
    ): PlaybackPolicy.StuckAction = PlaybackPolicy.stuckAction(
        sameItem = true,
        allowed = true,
        itemPlayed = true,
        isPlaying = playing,
        refreshing = refreshing,
        retryAvailable = retry,
        bufferAdvancing = bufferAdvancing,
        renewing = renewing,
    )

    @Test
    fun testStuckWithoutProgressStillRecoversOrGivesUp() {
        assertEquals(PlaybackPolicy.StuckAction.RECOVER, stuck())
        assertEquals(PlaybackPolicy.StuckAction.GIVE_UP, stuck(retry = false))
        assertEquals(10.0, PlaybackPolicy.stuckAfter, "10 秒阈值保留")
    }

    @Test
    fun testStuckRearmsWhileBufferGrowsOrRenewing() {
        assertEquals(PlaybackPolicy.StuckAction.REARM, stuck(bufferAdvancing = true))
        assertEquals(PlaybackPolicy.StuckAction.REARM, stuck(renewing = true))
        assertEquals(PlaybackPolicy.StuckAction.REARM, stuck(bufferAdvancing = true, retry = false), "有进展时不放弃")
    }

    @Test
    fun testStuckIgnoreTakesPriorityOverProgress() {
        assertEquals(PlaybackPolicy.StuckAction.IGNORE, stuck(bufferAdvancing = true, playing = true))
        assertEquals(PlaybackPolicy.StuckAction.IGNORE, stuck(renewing = true, refreshing = true))
    }

    @Test
    fun testBufferAdvanced() {
        assertTrue(PlaybackPolicy.bufferAdvanced(previousEnd = 10.0, currentEnd = 10.5))
        assertTrue(PlaybackPolicy.bufferAdvanced(previousEnd = 0.0, currentEnd = PlaybackPolicy.bufferAdvanceEpsilon))
        assertFalse(PlaybackPolicy.bufferAdvanced(previousEnd = 10.0, currentEnd = 10.05), "浮点抖动不算")
        assertFalse(PlaybackPolicy.bufferAdvanced(previousEnd = 10.0, currentEnd = 9.0))
        assertFalse(PlaybackPolicy.bufferAdvanced(previousEnd = null, currentEnd = 12.0))
        assertFalse(PlaybackPolicy.bufferAdvanced(previousEnd = 10.0, currentEnd = null))
    }

    @Test
    fun testEndOfListKeepsReasonAndDoesNotCount() {
        val tracker = SkipTracker()
        val outcome = tracker.attemptAuto { false }
        assertEquals(SkipTracker.AutoOutcome.EndOfList, outcome)
        assertEquals(0, tracker.count, "没跳成不计数")
        assertEquals("主播已离线，已到列表尽头", outcome.notice(keeping = "主播已离线"))
        assertEquals("已到列表尽头", outcome.notice(keeping = null))
        assertEquals("已到列表尽头", outcome.notice(keeping = "  "))
    }

    @Test
    fun testMovedHasNoNotice() {
        val tracker = SkipTracker()
        assertEquals(SkipTracker.AutoOutcome.Moved, tracker.attemptAuto { true })
        assertEquals(1, tracker.count)
        assertNull(SkipTracker.AutoOutcome.Moved.notice(keeping = "x"))
    }

    @Test
    fun testLimitOnlyAfterRealConsecutiveSkips() {
        val tracker = SkipTracker()
        repeat(SkipTracker.limit) { assertEquals(SkipTracker.AutoOutcome.Moved, tracker.attemptAuto { true }) }
        var called = false
        val outcome = tracker.attemptAuto {
            called = true
            true
        }
        assertEquals(SkipTracker.AutoOutcome.LimitReached, outcome)
        assertFalse(called, "到上限不再移动")
        assertEquals("连续几个房间都无法播放", outcome.notice(keeping = "主播已离线"))
    }

    @Test
    fun testEndOfListAfterSomeSkipsIsStillEndOfList() {
        val tracker = SkipTracker()
        assertEquals(SkipTracker.AutoOutcome.Moved, tracker.attemptAuto { true })
        assertEquals(SkipTracker.AutoOutcome.EndOfList, tracker.attemptAuto { false })
        tracker.reset()
        assertEquals(0, tracker.count)
    }
}
