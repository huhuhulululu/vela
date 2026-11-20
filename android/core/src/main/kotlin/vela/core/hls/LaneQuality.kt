package vela.core.hls

import java.time.Instant

/**
 * 设备热状态。对应 nominal / fair / serious / critical。
 * core 不读系统 API，调用方把读数映射进来。
 */
enum class PhoneThermalState {
    NOMINAL,
    FAIR,
    SERIOUS,
    CRITICAL,
}

/**
 * 双窗画质上限。平时不封顶（正无穷，两路都用最高档）。
 * 热状态到 serious 及以上时按画面实际显示高度降档；回到 fair 及以下并连续满 60 秒后回升。
 */
class LaneQuality {
    var isLimited: Boolean = false
        private set

    /** 回到 fair 及以下的起点。再次升到 serious 就清掉，60 秒必须连续。 */
    private var fairSince: Instant? = null

    fun observe(state: PhoneThermalState, now: Instant) {
        if (state >= PhoneThermalState.SERIOUS) {
            isLimited = true
            fairSince = null
            return
        }
        if (!isLimited) return
        if (fairSince == null) fairSince = now
        val since = fairSince
        if (since != null && now.secondsSince(since) >= recoveryDelay) {
            isLimited = false
            fairSince = null
        }
    }

    /**
     * 未降档时返回正无穷。降档时用 `displayHeight × screenScale` 查 [PowerPolicy]，
     * 再保证不低于 [minimumBitrate]。高度不是有限正数时用下限。
     */
    fun peakBitrate(displayHeight: Double, screenScale: Double): Double {
        if (!isLimited) return Double.POSITIVE_INFINITY
        val pixels = displayHeight * screenScale
        if (!pixels.isFinite() || pixels <= 0.0) return minimumBitrate
        val estimated = PowerPolicy.peakBitrate(pixelHeight = pixels, resting = false)
        if (!estimated.isFinite()) return minimumBitrate
        return maxOf(minimumBitrate, estimated)
    }

    companion object {
        const val recoveryDelay: Double = 60.0
        const val minimumBitrate: Double = 896_000.0
    }
}
