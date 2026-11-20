package vela.app.pip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PiPAspectTest {
    @Test
    fun testClampsWiderThanMax() {
        val ratio = PiPAspect.clamp(16.0, 1.0)
        assertTrue(ratio.toDouble() <= PiPAspect.MAX + 0.001)
        assertTrue(ratio.toDouble() >= PiPAspect.MIN - 0.001)
    }

    @Test
    fun testClampsTallerThanMin() {
        val ratio = PiPAspect.clamp(1.0, 16.0)
        assertTrue(ratio.toDouble() >= PiPAspect.MIN - 0.001)
        assertTrue(ratio.toDouble() <= PiPAspect.MAX + 0.001)
    }

    @Test
    fun testKeepsSixteenByNine() {
        val ratio = PiPAspect.clamp(1920.0, 1080.0)
        assertEquals(16.0 / 9.0, ratio.toDouble(), 0.01)
    }

    @Test
    fun testInvalidFallsBack() {
        assertEquals(PiPAspect.FALLBACK, PiPAspect.clamp(0.0, 100.0))
        assertEquals(PiPAspect.FALLBACK, PiPAspect.clamp(Double.NaN, 1.0))
    }
}
