package vela.core.hls

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlaybackRefreshTests {
    @Test
    fun testFailureClearsPendingAndReportsReason() {
        val tracker = RefreshTracker()
        tracker.begin()
        assertEquals("需要确认访问后才能播放", tracker.fail(reason = "需要确认访问后才能播放"))
        assertFalse(tracker.pending)
    }

    @Test
    fun testFailureWithoutReasonStillGivesText() {
        val tracker = RefreshTracker()
        tracker.begin()
        assertEquals("画面没能刷新", tracker.fail(reason = null))
    }

    @Test
    fun testNewItemOrStopClearsSoFailureIsSilent() {
        val tracker = RefreshTracker()
        tracker.begin()
        tracker.clear()
        assertNull(tracker.fail(reason = "x"))
        assertNull(tracker.fail(reason = "x"))
    }

    @Test
    fun testSupersededTaskDoesNotOwnOutcome() {
        assertTrue(RefreshTracker.ownsOutcome(taskGeneration = 4, currentGeneration = 5))
        assertTrue(RefreshTracker.ownsOutcome(taskGeneration = 4, currentGeneration = 4))
        assertFalse(RefreshTracker.ownsOutcome(taskGeneration = 4, currentGeneration = 6))
    }

    @Test
    fun testWatchdogIgnoresItemWhileRefreshing() {
        assertTrue(PlaybackPolicy.startupStalled(sameItem = true, allowed = true, itemPlayed = false, isPlaying = false))
        assertFalse(
            PlaybackPolicy.startupStalled(
                sameItem = true,
                allowed = true,
                itemPlayed = false,
                isPlaying = false,
                refreshing = true,
            ),
        )
    }

    @Test
    fun testRecoveryClearsOnlyPlayerNote() {
        assertTrue(PlaybackNoteRule.clearOnRecovery(note = "迟迟没有开始", playerNote = "迟迟没有开始"))
        assertFalse(PlaybackNoteRule.clearOnRecovery(note = "连续几个房间都无法播放", playerNote = "迟迟没有开始"))
        assertFalse(PlaybackNoteRule.clearOnRecovery(note = null, playerNote = "x"))
        assertFalse(PlaybackNoteRule.clearOnRecovery(note = "x", playerNote = null))
    }

    @Test
    fun testFillContinuesWhenListEmptyAndSameGeneration() {
        assertTrue(LoadFollowUp.needsFill(tokenUnchanged = true, visibleRooms = 0))
        assertFalse(LoadFollowUp.needsFill(tokenUnchanged = false, visibleRooms = 0))
        assertFalse(LoadFollowUp.needsFill(tokenUnchanged = true, visibleRooms = 3))
    }
}
