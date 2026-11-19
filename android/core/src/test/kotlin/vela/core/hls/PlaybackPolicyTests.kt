package vela.core.hls

import java.net.URI
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import vela.core.base.play.StreamRendition

class PlaybackPolicyTests {
    private val t0: Instant = Instant.ofEpochSecond(1_800_000_000)
    private val url: URI = URI.create("https://example.com/a.m3u8")
    private val rend: StreamRendition
        get() = StreamRendition(video = url, audio = URI.create("https://example.com/a-audio.m3u8"))

    @Test
    fun testGateNeedsEveryCondition() {
        assertTrue(PlaybackPolicy.allowed(windowVisible = true, manuallyPaused = false, bossHidden = false, systemInactive = false))
        assertFalse(PlaybackPolicy.allowed(windowVisible = false, manuallyPaused = false, bossHidden = false, systemInactive = false))
        assertFalse(PlaybackPolicy.allowed(windowVisible = true, manuallyPaused = true, bossHidden = false, systemInactive = false))
        assertFalse(PlaybackPolicy.allowed(windowVisible = true, manuallyPaused = false, bossHidden = true, systemInactive = false))
        assertFalse(PlaybackPolicy.allowed(windowVisible = true, manuallyPaused = false, bossHidden = false, systemInactive = true))
    }

    @Test
    fun testResumeActionByPauseLength() {
        assertEquals(PlaybackPolicy.ResumeAction.RESUME, PlaybackPolicy.resumeAction(1.0))
        assertEquals(PlaybackPolicy.ResumeAction.RESUME, PlaybackPolicy.resumeAction(5.0))
        assertEquals(PlaybackPolicy.ResumeAction.RESUME, PlaybackPolicy.resumeAction(14.9))
        assertEquals(PlaybackPolicy.ResumeAction.RESUME, PlaybackPolicy.resumeAction(PlaybackPolicy.liveRejoinAfter))
        assertEquals(PlaybackPolicy.ResumeAction.REJOIN, PlaybackPolicy.resumeAction(PlaybackPolicy.liveRejoinAfter + 0.1))
        assertEquals(PlaybackPolicy.ResumeAction.REJOIN, PlaybackPolicy.resumeAction(22.0))
        assertEquals(PlaybackPolicy.ResumeAction.REJOIN, PlaybackPolicy.resumeAction(PlaybackPolicy.reresolveAfter - 1))
        assertEquals(PlaybackPolicy.ResumeAction.RERESOLVE, PlaybackPolicy.resumeAction(PlaybackPolicy.reresolveAfter))
        assertEquals(PlaybackPolicy.ResumeAction.RERESOLVE, PlaybackPolicy.resumeAction(3600.0))
        assertTrue(PlaybackPolicy.reresolveAfter < 32.0, "必须短于实测的最低闲置寿命")
    }

    /** 停播释放与侧车空闲销毁的边界：29.9 秒仍保留，30 秒与 30.1 秒放掉。 */
    @Test
    fun testIdleReleaseBoundaries() {
        assertEquals(30.0, PlaybackPolicy.releasePausedItemAfter)
        assertEquals(PlaybackPolicy.reresolveAfter, PlaybackPolicy.releasePausedItemAfter)
        assertFalse(PlaybackPolicy.shouldReleasePausedItem(pausedFor = 29.9, hiddenFor = 29.9))
        assertTrue(PlaybackPolicy.shouldReleasePausedItem(pausedFor = 30.0, hiddenFor = 30.0))
        assertTrue(PlaybackPolicy.shouldReleasePausedItem(pausedFor = 30.1, hiddenFor = 30.1))
        assertEquals(PlaybackPolicy.ResumeAction.REJOIN, PlaybackPolicy.resumeAction(29.9), "未满 30 秒仍要重建，播放项还在")
        assertEquals(PlaybackPolicy.ResumeAction.RERESOLVE, PlaybackPolicy.resumeAction(30.0))
        assertEquals(PlaybackPolicy.ResumeAction.RERESOLVE, PlaybackPolicy.resumeAction(30.1))

        assertEquals(30.0, SidecarIdlePolicy.seconds)
        assertFalse(SidecarIdlePolicy.shouldTearDown(29.9))
        assertTrue(SidecarIdlePolicy.shouldTearDown(30.0))
        assertTrue(SidecarIdlePolicy.shouldTearDown(30.1))
        assertTrue(SidecarIdlePolicy.seconds < 120.0, "短于最短的侧车冷却，超时后按需重建")
    }

    /** 窗口可见时手动暂停不释放；不可见才计时，且从变为不可见起算。 */
    @Test
    fun testReleaseOnlyWhenWindowInvisible() {
        assertFalse(PlaybackPolicy.shouldReleasePausedItem(pausedFor = 31.0, hiddenFor = null), "可见的手动暂停 31 秒不释放")
        assertFalse(PlaybackPolicy.shouldReleasePausedItem(pausedFor = 3600.0, hiddenFor = null))
        assertFalse(PlaybackPolicy.shouldReleasePausedItem(pausedFor = 60.0, hiddenFor = 29.9), "暂停后才变不可见，从不可见起再计 30 秒")
        assertTrue(PlaybackPolicy.shouldReleasePausedItem(pausedFor = 60.0, hiddenFor = 30.0))
        assertTrue(PlaybackPolicy.shouldReleasePausedItem(pausedFor = 60.0, hiddenFor = 30.1))
        assertFalse(PlaybackPolicy.shouldReleasePausedItem(pausedFor = 29.9, hiddenFor = 60.0), "不可见很久但停播未满 30 秒")
    }

    @Test
    fun testWindowInvisibleReasons() {
        assertFalse(PlaybackPolicy.windowInvisible(windowVisible = true, bossHidden = false, systemInactive = false, appHidden = false))
        assertTrue(PlaybackPolicy.windowInvisible(windowVisible = false, bossHidden = false, systemInactive = false, appHidden = false))
        assertTrue(PlaybackPolicy.windowInvisible(windowVisible = true, bossHidden = true, systemInactive = false, appHidden = false))
        assertTrue(PlaybackPolicy.windowInvisible(windowVisible = true, bossHidden = false, systemInactive = true, appHidden = false))
        assertTrue(PlaybackPolicy.windowInvisible(windowVisible = true, bossHidden = false, systemInactive = false, appHidden = true))
    }

    @Test
    fun testAudioCheckRechecksBeforeFailing() {
        assertEquals(PlaybackPolicy.AudioCheckStep.OK, PlaybackPolicy.audioCheckStep(enabledTracks = 1, isRecheck = false))
        assertEquals(PlaybackPolicy.AudioCheckStep.RECHECK, PlaybackPolicy.audioCheckStep(enabledTracks = 0, isRecheck = false))
        assertEquals(PlaybackPolicy.AudioCheckStep.OK, PlaybackPolicy.audioCheckStep(enabledTracks = 1, isRecheck = true))
        assertEquals(PlaybackPolicy.AudioCheckStep.FAIL, PlaybackPolicy.audioCheckStep(enabledTracks = 0, isRecheck = true))
    }

    @Test
    fun testWatchdogOnlyFiresWhenShouldBePlayingAndItemNeverPlayed() {
        assertTrue(PlaybackPolicy.startupStalled(sameItem = true, allowed = true, itemPlayed = false, isPlaying = false))
        assertFalse(PlaybackPolicy.startupStalled(sameItem = true, allowed = false, itemPlayed = false, isPlaying = false))
        assertFalse(PlaybackPolicy.startupStalled(sameItem = false, allowed = true, itemPlayed = false, isPlaying = false))
        assertFalse(PlaybackPolicy.startupStalled(sameItem = true, allowed = true, itemPlayed = true, isPlaying = false))
        assertFalse(PlaybackPolicy.startupStalled(sameItem = true, allowed = true, itemPlayed = false, isPlaying = true))
    }

    @Test
    fun testPreparedAddressExpires() {
        val cache = PreparedCache()
        cache.set(rend, cap = 1.0, username = "a", now = t0)
        assertEquals(20.0, PreparedCache.maxAge)
        assertTrue(PreparedCache.maxAge < 32.0, "必须短于实测的最低闲置寿命")
        assertEquals(rend, cache.fresh("a", cap = 1.0, now = t0.plusSeconds(PreparedCache.maxAge - 1)))
        assertNull(cache.fresh("a", cap = 1.0, now = t0.plusSeconds(PreparedCache.maxAge)))
        assertNull(cache.fresh("b", cap = 1.0, now = t0))
        assertNull(cache.fresh("a", cap = Double.POSITIVE_INFINITY, now = t0))
    }

    @Test
    fun testPreparedRefreshAndRemove() {
        val cache = PreparedCache()
        cache.set(rend, cap = 1.0, username = "a", now = t0)
        val newer = URI.create("https://example.com/b.m3u8")
        cache.set(StreamRendition(video = newer), cap = 1.0, username = "a", now = t0.plusSeconds(15.0))
        assertEquals(newer, cache.fresh("a", cap = 1.0, now = t0.plusSeconds(30.0))?.video)
        cache.remove("a")
        assertNull(cache.fresh("a", cap = 1.0, now = t0.plusSeconds(30.0)))
    }

    @Test
    fun testPreparedSetDropsExpiredEntries() {
        val cache = PreparedCache()
        cache.set(rend, cap = 1.0, username = "old", now = t0)
        cache.set(rend, cap = 1.0, username = "new", now = t0.plusSeconds(PreparedCache.maxAge + 1))
        assertNull(cache.fresh("old", cap = 1.0, now = t0.plusSeconds(1.0)))
    }

    @Test
    fun testStuckWatchdogDecision() {
        fun act(
            same: Boolean = true,
            allowed: Boolean = true,
            played: Boolean = true,
            playing: Boolean = false,
            refreshing: Boolean = false,
            budget: Boolean = true,
        ): PlaybackPolicy.StuckAction = PlaybackPolicy.stuckAction(
            sameItem = same,
            allowed = allowed,
            itemPlayed = played,
            isPlaying = playing,
            refreshing = refreshing,
            retryAvailable = budget,
        )
        assertEquals(PlaybackPolicy.StuckAction.RECOVER, act())
        assertEquals(PlaybackPolicy.StuckAction.IGNORE, act(allowed = false))
        assertEquals(PlaybackPolicy.StuckAction.IGNORE, act(same = false))
        assertEquals(PlaybackPolicy.StuckAction.IGNORE, act(played = false))
        assertEquals(PlaybackPolicy.StuckAction.IGNORE, act(playing = true))
        assertEquals(PlaybackPolicy.StuckAction.IGNORE, act(refreshing = true))
        assertEquals(PlaybackPolicy.StuckAction.GIVE_UP, act(budget = false))
        assertTrue(PlaybackPolicy.stuckAfter > 6.0)
        assertTrue(PlaybackPolicy.stuckAfter < PlaybackPolicy.startupTimeout)
    }
}

class RelayRefreshPolicyTests {
    @Test
    fun testRelayStopsRefreshingOnTerminalFailureAndWhilePaused() {
        assertTrue(PlaybackPolicy.relayShouldRefresh(allowed = true, failed = false))
        assertFalse(PlaybackPolicy.relayShouldRefresh(allowed = true, failed = true), "终态失败不再后台刷新")
        assertFalse(PlaybackPolicy.relayShouldRefresh(allowed = false, failed = false))
        assertFalse(PlaybackPolicy.relayShouldRefresh(allowed = false, failed = true))
    }
}

/** 资源降级的独立门控：普通闸门重算不放行，只有显式重试、切房、回前台才清除。 */
class DegradedHoldTests {
    @Test
    fun testDegradedLaneStaysClosedAcrossRepeatedGateRecomputes() {
        val hold = DegradedHold()
        assertTrue(hold.gate(true), "未降级时与原闸门一致")
        assertFalse(hold.gate(false))
        hold.degrade()
        repeat(5) { assertFalse(hold.gate(true)) }
        assertTrue(hold.held)
    }

    @Test
    fun testExplicitReleaseReopensLane() {
        val reasons = listOf(
            DegradedHold.Release.RETRY,
            DegradedHold.Release.ROOM_SWITCH,
            DegradedHold.Release.FOREGROUND_RESUME,
        )
        for (reason in reasons) {
            val hold = DegradedHold()
            hold.degrade()
            hold.release(reason)
            assertFalse(hold.held, reason.toString())
            assertTrue(hold.gate(true), "$reason 之后起播")
            assertFalse(hold.gate(false), "清除后仍服从全局闸门")
        }
    }
}
