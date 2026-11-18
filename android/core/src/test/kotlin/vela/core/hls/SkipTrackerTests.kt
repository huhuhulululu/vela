package vela.core.hls

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SkipTrackerTests {
    @Test
    fun testSkipDirectionFollowsLastManualStep() {
        val skips = SkipTracker()
        assertEquals(1, skips.direction)
        skips.noteManual(-1)
        assertEquals(-1, skips.direction)
        skips.noteManual(5)
        assertEquals(1, skips.direction)
        skips.noteManual(0)
        assertEquals(1, skips.direction)
    }

    @Test
    fun testSkipStopsAfterLimitAndResets() {
        val skips = SkipTracker()
        repeat(SkipTracker.limit) { assertTrue(skips.registerAuto()) }
        assertFalse(skips.registerAuto())
        assertFalse(skips.registerAuto())
        skips.reset()
        assertTrue(skips.registerAuto())
        assertEquals(1, skips.direction)
    }

    @Test
    fun testManualStepClearsSkipCountButAutoDoesNot() {
        val skips = SkipTracker()
        skips.registerAuto()
        skips.registerAuto()
        skips.noteManual(-1)
        assertEquals(0, skips.count)
        assertTrue(SkipTracker.noticeSeconds >= 1.5)
    }
}
