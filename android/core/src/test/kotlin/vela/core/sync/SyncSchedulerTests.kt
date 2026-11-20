package vela.core.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SyncSchedulerTests {
    @Test
    fun testLaunchAndForegroundAlwaysRunPanelsWaitSixtySeconds() {
        val s = SyncScheduler()
        assertTrue(s.allows(SyncScheduler.Trigger.LAUNCH, 0u))
        assertTrue(s.allows(SyncScheduler.Trigger.PANEL, 0u), "从未联系过服务端时面板触发也跑")
        s.record(SyncCycleResult.UpToDate, 1_000u)
        assertTrue(s.allows(SyncScheduler.Trigger.FOREGROUND, 2_000u))
        assertFalse(s.allows(SyncScheduler.Trigger.PANEL, 61_000u))
        assertTrue(s.allows(SyncScheduler.Trigger.PANEL, 61_001u))
        assertFalse(s.allows(SyncScheduler.Trigger.ACTIVATE, 30_000u), "激活按面板口径")
    }

    @Test
    fun testEditDebounceAndMinimumGapBetweenPuts() {
        val s = SyncScheduler()
        assertEquals(5_000u, s.editDelay(0u))
        s.record(SyncCycleResult.Pushed, 100_000u)
        assertEquals(9_000u, s.editDelay(101_000u), "两次 PUT 至少间隔 10 秒")
        assertEquals(5_000u, s.editDelay(200_000u))
    }

    @Test
    fun testFailuresBackOffFromThirtySecondsDoublingToOneHour() {
        val s = SyncScheduler()
        s.record(SyncCycleResult.Transient, 0u)
        assertFalse(s.allows(SyncScheduler.Trigger.FOREGROUND, 29_999u))
        assertTrue(s.allows(SyncScheduler.Trigger.FOREGROUND, 30_000u))
        s.record(SyncCycleResult.Transient, 30_000u)
        assertFalse(s.allows(SyncScheduler.Trigger.LOCAL_EDIT, 89_999u))
        assertTrue(s.allows(SyncScheduler.Trigger.LOCAL_EDIT, 90_000u))
        repeat(20) { s.record(SyncCycleResult.Transient, 0u) }
        assertEquals(3_600_000u, s.backoffUntil)
        s.record(SyncCycleResult.Pushed, 5u)
        assertEquals(0, s.failures)
        assertTrue(s.allows(SyncScheduler.Trigger.LOCAL_EDIT, 6u))
    }

    @Test
    fun testRateLimitAndQuotaBackoff() {
        val s = SyncScheduler()
        s.record(SyncCycleResult.RateLimited(42), 1_000u)
        assertFalse(s.allows(SyncScheduler.Trigger.MANUAL, 42_999u), "429 连立即同步也按 Retry-After 等")
        assertTrue(s.allows(SyncScheduler.Trigger.MANUAL, 43_000u))
        s.record(SyncCycleResult.QuotaFull, 0u)
        assertFalse(s.allows(SyncScheduler.Trigger.LAUNCH, 3_599_999u))
        val t = SyncScheduler()
        t.record(SyncCycleResult.Transient, 0u)
        assertFalse(t.allows(SyncScheduler.Trigger.LOCAL_EDIT, 1u))
        assertTrue(t.allows(SyncScheduler.Trigger.MANUAL, 1u), "普通退避不挡用户点「立即同步」")
    }

    @Test
    fun testBackgroundOnlyWhenUnpushed() {
        val s = SyncScheduler()
        assertFalse(s.allowsBackground(3u, 3u, 0u))
        assertTrue(s.allowsBackground(4u, 3u, 0u))
    }

    @Test
    fun testUnavailableStartupIsRetriedOnDiscreteTriggers() {
        for (t in listOf(
            SyncScheduler.Trigger.FOREGROUND,
            SyncScheduler.Trigger.ACTIVATE,
            SyncScheduler.Trigger.PANEL,
            SyncScheduler.Trigger.MANUAL,
        )) {
            assertTrue(SyncScheduler.retriesStartup(true, t), "$t")
        }
        assertFalse(SyncScheduler.retriesStartup(true, SyncScheduler.Trigger.LOCAL_EDIT))
        assertFalse(SyncScheduler.retriesStartup(false, SyncScheduler.Trigger.FOREGROUND))
    }
}
