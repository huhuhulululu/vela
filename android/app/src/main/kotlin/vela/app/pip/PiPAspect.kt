package vela.app.pip

import android.util.Rational
import kotlin.math.max
import kotlin.math.min

/**
 * 系统画中画宽高比夹在 [MIN]…[MAX]（约 1:2.39 到 2.39:1）。
 * 视频比例超出时夹到边界，避免 enterPictureInPictureMode 抛 IllegalArgumentException。
 */
object PiPAspect {
    /** Android 文档：最窄约 1:2.39。 */
    const val MIN: Double = 1.0 / 2.39

    /** Android 文档：最宽约 2.39:1。 */
    const val MAX: Double = 2.39

    /** 没有有效画面时用竖屏 9:16。 */
    val FALLBACK: Rational = Rational(9, 16)

    fun clamp(width: Double, height: Double): Rational {
        if (!width.isFinite() || !height.isFinite() || width <= 0.0 || height <= 0.0) {
            return FALLBACK
        }
        val raw = width / height
        if (!raw.isFinite() || raw <= 0.0) return FALLBACK
        val clamped = min(MAX, max(MIN, raw))
        return rationalOf(clamped)
    }

    fun clamp(ratio: Double): Double {
        if (!ratio.isFinite() || ratio <= 0.0) return FALLBACK.toDouble()
        return min(MAX, max(MIN, ratio))
    }

    private fun rationalOf(value: Double): Rational {
        // 用千分比保持精度，系统再约简。
        val numerator = (value * 1000.0).toInt().coerceAtLeast(1)
        return Rational(numerator, 1000)
    }
}
