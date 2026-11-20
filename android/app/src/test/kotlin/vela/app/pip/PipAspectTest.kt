package vela.app.pip

import android.util.Rational
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PipAspectTest {
    @Test
    fun testClampsTallVideoToMinimumRatio() {
        val aspect = PipAspect.clamp(108, 400)
        assertEquals(Rational(100, 239), aspect)
    }

    @Test
    fun testClampsWideVideoToMaximumRatio() {
        val aspect = PipAspect.clamp(400, 108)
        assertEquals(Rational(239, 100), aspect)
    }

    @Test
    fun testPreservesSixteenByNineInsideBand() {
        val aspect = PipAspect.clamp(16, 9)
        assertEquals(Rational(16, 9), aspect)
    }

    @Test
    fun testFallsBackWhenDimensionsMissing() {
        assertEquals(Rational(16, 9), PipAspect.clamp(0, 720))
    }
}