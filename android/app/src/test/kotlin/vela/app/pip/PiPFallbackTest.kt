package vela.app.pip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import vela.core.lifecycle.PhoneScenePhase
import vela.core.lifecycle.PiPPolicy

class PiPFallbackTest {
    @Test
    fun testTimeoutIsThreeSeconds() {
        assertEquals(3.0, PiPFallback.TIMEOUT_SECONDS, 0.0)
    }

    @Test
    fun testOnlyActiveCountsAsEntered() {
        assertTrue(PiPFallback.shouldStop(PhoneScenePhase.BACKGROUND, PiPPolicy.State.NONE))
        assertTrue(
            PiPFallback.shouldStop(PhoneScenePhase.BACKGROUND, PiPPolicy.State.STARTING),
        )
        assertTrue(PiPFallback.shouldStop(PhoneScenePhase.BACKGROUND, PiPPolicy.State.STOPPING))
        assertFalse(PiPFallback.shouldStop(PhoneScenePhase.BACKGROUND, PiPPolicy.State.ACTIVE))
        assertFalse(PiPFallback.shouldStop(PhoneScenePhase.ACTIVE, PiPPolicy.State.NONE))
        assertFalse(PiPFallback.shouldStop(PhoneScenePhase.INACTIVE, PiPPolicy.State.NONE))
    }
}
