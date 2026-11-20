package vela.core.hls

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LaneQualityTests {
    private val t0: Instant = Instant.ofEpochSecond(1_800_000_000)

    /** serious 及以上按 displayHeight × screenScale 降档：663px → 1.696M，且不低于 896k。未发热不封顶。 */
    @Test
    fun testSeriousCapsByDisplaySize663To1696kFloor896k() {
        val cool = LaneQuality()
        assertFalse(cool.isLimited)
        assertEquals(Double.POSITIVE_INFINITY, cool.peakBitrate(displayHeight = 221.0, screenScale = 3.0))
        assertEquals(Double.POSITIVE_INFINITY, cool.peakBitrate(displayHeight = 400.0, screenScale = 3.0))
        cool.observe(PhoneThermalState.FAIR, t0.plusSeconds(600.0))
        assertEquals(Double.POSITIVE_INFINITY, cool.peakBitrate(displayHeight = 221.0, screenScale = 3.0))

        val hot = LaneQuality()
        hot.observe(PhoneThermalState.SERIOUS, t0)
        assertTrue(hot.isLimited)
        assertEquals(896_000.0, hot.peakBitrate(displayHeight = 221.0, screenScale = 1.0))
        assertEquals(1_696_000.0, hot.peakBitrate(displayHeight = 221.0, screenScale = 3.0))
        assertEquals(1_696_000.0, hot.peakBitrate(displayHeight = 663.0, screenScale = 1.0))
        assertEquals(5_128_000.0, hot.peakBitrate(displayHeight = 400.0, screenScale = 3.0))
        assertEquals(LaneQuality.minimumBitrate, hot.peakBitrate(displayHeight = 10.0, screenScale = 1.0))
        assertEquals(LaneQuality.minimumBitrate, hot.peakBitrate(displayHeight = 0.0, screenScale = 3.0))
        assertTrue(hot.peakBitrate(displayHeight = 1.0, screenScale = 1.0) >= LaneQuality.minimumBitrate)
        assertEquals(896_000.0, LaneQuality.minimumBitrate)

        val critical = LaneQuality()
        critical.observe(PhoneThermalState.CRITICAL, t0)
        assertEquals(1_696_000.0, critical.peakBitrate(displayHeight = 663.0, screenScale = 1.0))
    }

    /** 回到 fair 及以下后，第 59 秒不回升，满 60 秒才回升；中途再热则重新计时。 */
    @Test
    fun testThermalStaysLimitedAt59SecondsAndRecoversAt60() {
        val quality = LaneQuality()
        quality.observe(PhoneThermalState.SERIOUS, t0)
        quality.observe(PhoneThermalState.FAIR, t0)
        quality.observe(PhoneThermalState.FAIR, t0.plusSeconds(59.0))
        assertTrue(quality.isLimited)
        assertEquals(1_696_000.0, quality.peakBitrate(displayHeight = 663.0, screenScale = 1.0))

        quality.observe(PhoneThermalState.SERIOUS, t0.plusSeconds(59.0))
        quality.observe(PhoneThermalState.FAIR, t0.plusSeconds(59.0))
        quality.observe(PhoneThermalState.NOMINAL, t0.plusSeconds(59.0 + 59.0))
        assertTrue(quality.isLimited)
        quality.observe(PhoneThermalState.NOMINAL, t0.plusSeconds(59.0 + 60.0))
        assertFalse(quality.isLimited)
        assertEquals(Double.POSITIVE_INFINITY, quality.peakBitrate(displayHeight = 663.0, screenScale = 1.0))

        val direct = LaneQuality()
        direct.observe(PhoneThermalState.CRITICAL, t0)
        direct.observe(PhoneThermalState.NOMINAL, t0)
        direct.observe(PhoneThermalState.FAIR, t0.plusSeconds(60.0))
        assertFalse(direct.isLimited)
        assertEquals(Double.POSITIVE_INFINITY, direct.peakBitrate(displayHeight = 221.0, screenScale = 3.0))
    }
}
