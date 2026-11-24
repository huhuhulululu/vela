package vela.core.hls
import kotlin.math.abs

/**
 * 按原始比例把画面放进窗格，不裁切。宽高用 `Double`，对应 64 位平台上的 `CGFloat`。
 */
object DisplayFit {
    data class Size(val width: Double, val height: Double)

    data class Rect(val x: Double, val y: Double, val width: Double, val height: Double) {
        val minX: Double get() = x
        val minY: Double get() = y
        val maxX: Double get() = x + width
        val maxY: Double get() = y + height

        companion object {
            val zero = Rect(0.0, 0.0, 0.0, 0.0)
        }
    }

    /** 宽高比允许的相对误差。正好落在这个比例上算未裁切。 */
    const val aspectTolerance: Double = 0.01

    /** 限制的那条边与窗格对齐，另一条边留白，画面在窗格里居中。 */
    fun fitted(presentation: Size, bounds: Rect): Rect {
        if (!positiveFinite(presentation.width) || !positiveFinite(presentation.height) ||
            !positiveFinite(bounds.width) || !positiveFinite(bounds.height)
        ) {
            return Rect.zero
        }
        val widthScale = bounds.width / presentation.width
        val heightScale = bounds.height / presentation.height
        val fittedWidth: Double
        val fittedHeight: Double
        if (widthScale <= heightScale) {
            fittedWidth = bounds.width
            fittedHeight = presentation.height * widthScale
        } else {
            fittedHeight = bounds.height
            fittedWidth = presentation.width * heightScale
        }
        val x = bounds.minX + (bounds.width - fittedWidth) / 2.0
        val y = bounds.minY + (bounds.height - fittedHeight) / 2.0
        return Rect(x, y, fittedWidth, fittedHeight)
    }

    /**
     * `videoRect` 完全落在 `bounds` 内，且宽高比与 `presentation` 相差不超过 1%。
     * 正好 1% 时浮点误差可能略大于 0.01，仍算在容差内。
     */
    fun isUncropped(videoRect: Rect, bounds: Rect, presentation: Size): Boolean {
        if (!positiveFinite(videoRect.width) || !positiveFinite(videoRect.height) ||
            !positiveFinite(presentation.width) || !positiveFinite(presentation.height)
        ) {
            return false
        }
        if (!contains(bounds, videoRect)) return false
        val presented = presentation.width / presentation.height
        if (!positiveFinite(presented)) return false
        val actual = videoRect.width / videoRect.height
        if (!actual.isFinite()) return false
        val delta = abs(actual - presented) / presented
        return delta <= aspectTolerance || abs(delta - aspectTolerance) < 0.000_000_001
    }

    private fun contains(bounds: Rect, rect: Rect): Boolean {
        val slack = 0.01
        return rect.minX >= bounds.minX - slack &&
            rect.minY >= bounds.minY - slack &&
            rect.maxX <= bounds.maxX + slack &&
            rect.maxY <= bounds.maxY + slack
    }

    private fun positiveFinite(value: Double): Boolean = value > 0.0 && value.isFinite()
}
