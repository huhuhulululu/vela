package vela.app.pip

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import vela.core.lifecycle.PhoneScenePhase
import vela.core.lifecycle.PiPPolicy

class PipFallbackTest {
    @Test
    fun testBackgroundStartingTriggersFallback() {
        assertTrue(
            PipFallback.shouldStop(PhoneScenePhase.BACKGROUND, PiPPolicy.State.STARTING),
        )
    }

    @Test
    fun testBackgroundActiveDoesNotTriggerFallback() {
        assertFalse(
            PipFallback.shouldStop(PhoneScenePhase.BACKGROUND, PiPPolicy.State.ACTIVE),
        )
    }

    @Test
    fun testForegroundIgnoresState() {
        assertFalse(
            PipFallback.shouldStop(PhoneScenePhase.ACTIVE, PiPPolicy.State.STARTING),
        )
    }
}