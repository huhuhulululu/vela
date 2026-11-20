package vela.core.lifecycle

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import vela.core.base.net.EndpointBackoff

class AwayRetryTests {
    @Test
    fun testFirstAwayRetriesOnceThenHolds() {
        val away = AwayRetry()
        away.enter("alpha")
        assertEquals(AwayRetry.Decision.RetryLater(AwayRetry.delay), away.onAway("alpha"))
        assertEquals(60.0, AwayRetry.delay)
        // 延迟到点真正开始重试（记一次），重新起播同一房间：仍离开就保持提示，不再排第二次。
        away.beginRetry()
        away.enter("alpha")
        assertEquals(AwayRetry.Decision.Hold, away.onAway("alpha"))
        away.enter("Alpha")
        assertEquals(AwayRetry.Decision.Hold, away.onAway("ALPHA"))
    }

    @Test
    fun testNotes() {
        assertEquals("暂时离开，稍后自动重试", AwayRetry.Decision.RetryLater(60.0).note)
        assertEquals("暂时离开，可稍后手动重试或换个房间", AwayRetry.Decision.Hold.note)
    }

    @Test
    fun testSwitchingRoomsResets() {
        val away = AwayRetry()
        away.enter("alpha")
        away.onAway("alpha")
        away.enter("beta")
        assertNull(away.room)
        assertEquals(AwayRetry.Decision.RetryLater(AwayRetry.delay), away.onAway("beta"))
        // 回到原房间也是新的一次：可以再自动重试一次。
        away.enter("alpha")
        assertEquals(AwayRetry.Decision.RetryLater(AwayRetry.delay), away.onAway("alpha"))
    }

    @Test
    fun testAwayForDifferentRoomWithoutEnterStillCountsSeparately() {
        val away = AwayRetry()
        away.onAway("alpha")
        assertEquals(AwayRetry.Decision.RetryLater(AwayRetry.delay), away.onAway("beta"))
        away.beginRetry()
        assertEquals(AwayRetry.Decision.Hold, away.onAway("beta"))
    }

    @Test
    fun testSuccessfulPlaybackClears() {
        val away = AwayRetry()
        away.onAway("alpha")
        away.clear()
        away.enter("alpha")
        assertFalse(away.retried)
        assertEquals(AwayRetry.Decision.RetryLater(AwayRetry.delay), away.onAway("alpha"))
    }

    @Test
    fun testShouldFireOnlyForSameRoomGenerationAndAllowed() {
        fun fire(
            room: String? = "alpha",
            gen: Int = 3,
            allowed: Boolean = true,
            cancelled: Boolean = false,
        ): Boolean = AwayRetry.shouldFire(
            scheduledRoom = "alpha",
            currentRoom = room,
            scheduledGeneration = 3,
            currentGeneration = gen,
            playbackAllowed = allowed,
            cancelled = cancelled,
        )
        assertTrue(fire())
        assertTrue(fire(room = "ALPHA"))
        assertFalse(fire(room = "beta"), "切房后不再重试")
        assertFalse(fire(room = null))
        assertFalse(fire(gen = 4), "期间有新的起播")
        assertFalse(fire(allowed = false), "暂停或隐藏时不重试")
        assertFalse(fire(cancelled = true))
    }

    @Test
    fun testPauseCancelsPendingRetryEvenIfResumedBeforeDeadline() {
        val timer = AwayRetryTimer()
        timer.schedule()
        assertTrue(timer.pending)
        assertFalse(timer.gateChanged(allowed = true), "仍允许播放：不动")
        assertTrue(timer.gateChanged(allowed = false), "暂停：作废排程")
        assertFalse(timer.pending)
        assertFalse(timer.gateChanged(allowed = true), "到期前恢复不重新排程")
        assertFalse(
            AwayRetry.shouldFire(
                scheduledRoom = "a",
                currentRoom = "a",
                scheduledGeneration = 1,
                currentGeneration = 1,
                playbackAllowed = true,
                cancelled = !timer.pending,
            ),
            "到点也不触发",
        )
    }

    @Test
    fun testPauseWithoutPendingIsNoop() {
        val timer = AwayRetryTimer()
        assertFalse(timer.gateChanged(allowed = false))
    }

    @Test
    fun testClearDropsPending() {
        val timer = AwayRetryTimer()
        timer.schedule()
        timer.clear()
        assertFalse(timer.pending)
    }

    @Test
    fun testCancelledRetryDoesNotConsumeTheAutoRetry() {
        val away = AwayRetry()
        away.enter("alpha")
        assertEquals(AwayRetry.Decision.RetryLater(AwayRetry.delay), away.onAway("alpha"))
        // 排程后被暂停取消，没有 beginRetry；手动重试仍离开时还有一次自动重试。
        away.enter("alpha")
        assertEquals(AwayRetry.Decision.RetryLater(AwayRetry.delay), away.onAway("alpha"))
        away.beginRetry()
        away.enter("alpha")
        assertEquals(AwayRetry.Decision.Hold, away.onAway("alpha"))
    }

    /** 暂停发生在解析返回之前：away 结果回来时门控已关，只给手动重试提示、不建自动重试任务。 */
    @Test
    fun testAwayResultAfterPauseGivesManualNoteWithoutScheduling() {
        val away = AwayRetry()
        away.enter("alpha")
        val decision = away.onAway("alpha")
        assertEquals(AwayRetry.Decision.RetryLater(AwayRetry.delay), decision)
        assertEquals(AwayRetry.Decision.Hold, AwayRetry.gated(decision, playbackAllowed = false))
        assertEquals(AwayRetry.heldNote, AwayRetry.gated(decision, playbackAllowed = false).note)
        assertEquals(decision, AwayRetry.gated(decision, playbackAllowed = true))
        assertEquals(AwayRetry.Decision.Hold, AwayRetry.gated(AwayRetry.Decision.Hold, playbackAllowed = true))
        // 被门控挡掉的那次没有消耗自动重试：恢复后手动重试仍 away 时还有一次。
        away.enter("alpha")
        assertEquals(AwayRetry.Decision.RetryLater(AwayRetry.delay), away.onAway("alpha"))
    }

    @Test
    fun testBackoffNoteFollowsGate() {
        assertEquals(EndpointBackoff.waitingNote, EndpointBackoff.pendingNote(playbackAllowed = true))
        assertEquals(EndpointBackoff.heldNote, EndpointBackoff.pendingNote(playbackAllowed = false))
    }

    /** L0a 把同名用例标了 deferred:C9。行为在这里锁住，C9 再对原文件。 */
    @Test
    fun testNoteAfterCancelSwitchesWaitingNotes() {
        assertEquals(AwayRetry.heldNote, AwayRetry.noteAfterCancel(AwayRetry.waitingNote))
        assertEquals(EndpointBackoff.heldNote, AwayRetry.noteAfterCancel(EndpointBackoff.waitingNote))
        assertEquals("别的提示", AwayRetry.noteAfterCancel("别的提示"))
        assertNull(AwayRetry.noteAfterCancel(null))
    }
}
