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
        assertTrue(ratio.toDouble() <= PiPAspect.MAX)
        assertTrue(ratio.toDouble() >= PiPAspect.MIN)
        assertTrue(ratio.toFloat() <= PiPAspect.MAX.toFloat())
    }

    @Test
    fun testClampsTallerThanMin() {
        val ratio = PiPAspect.clamp(1.0, 16.0)
        assertTrue("下界不允许跌破 MIN: ${ratio.toDouble()} < ${PiPAspect.MIN}", ratio.toDouble() >= PiPAspect.MIN)
        assertTrue("下界 float 不允许跌破 1/2.39: ${ratio.toFloat()}", ratio.toFloat() >= (1.0 / 2.39).toFloat())
        assertTrue(ratio.toDouble() <= PiPAspect.MAX)
    }

    @Test
    fun testNearMinRatioDoesNotTruncateBelowMin() {
        // 0.4185 略大于 MIN(0.418410...)，千分位向下截断会变成 418/1000 = 0.418 < MIN。
        val ratio = PiPAspect.clamp(4185.0, 10000.0)
        assertTrue("靠近下界的输入截断后不得跌破 MIN: ${ratio.toDouble()}", ratio.toDouble() >= PiPAspect.MIN)
        assertTrue(ratio.toFloat() >= (1.0 / 2.39).toFloat())
        assertTrue(ratio.toDouble() <= PiPAspect.MAX)
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
