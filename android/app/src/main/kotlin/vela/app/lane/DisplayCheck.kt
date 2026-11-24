package vela.app.lane

import android.graphics.Bitmap
import java.util.Locale
import kotlin.math.max
import vela.core.hls.DisplayFit

/**
 * 画面日志与垫底。
 * 日志格式按计划照抄：`display-check lane= gravity=fit presentation= bounds= videoRect= uncropped=`。
 * 模糊是缩到长边 16 像素再由视图放大，不用裁切模式。
 */
object DisplayCheck {
    const val BLUR_EDGE: Int = 16

    /** 没有缩略图时的深色渐变两端。不是纯黑。 */
    const val FALLBACK_TOP: Int = 0xFF241C28.toInt()
    const val FALLBACK_BOTTOM: Int = 0xFF4A3B32.toInt()

    fun line(
        lane: String,
        presentation: DisplayFit.Size,
        bounds: DisplayFit.Rect,
        videoRect: DisplayFit.Rect,
    ): String {
        val uncropped = DisplayFit.isUncropped(videoRect, bounds, presentation)
        return "display-check lane=$lane gravity=fit " +
            "presentation=${fmt(presentation.width)}x${fmt(presentation.height)} " +
            "bounds=${rect(bounds)} videoRect=${rect(videoRect)} uncropped=$uncropped"
    }

    /** 长边缩到 [edge]，另一边按比例，至少 1 像素。放大留给视图。 */
    fun blurThumbnail(source: Bitmap, edge: Int = BLUR_EDGE): Bitmap {
        val longEdge = max(source.width, source.height).coerceAtLeast(1)
        val scale = edge.toFloat() / longEdge.toFloat()
        val width = max(1, (source.width * scale).toInt())
        val height = max(1, (source.height * scale).toInt())
        if (width == source.width && height == source.height) return source
        return Bitmap.createScaledBitmap(source, width, height, true)
    }

    fun fallbackBackdrop(width: Int, height: Int): Bitmap {
        val safeW = width.coerceAtLeast(1)
        val safeH = height.coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(safeW, safeH, Bitmap.Config.ARGB_8888)
        // 逐行插值。Robolectric 不画 LinearGradient，纯色或空白会变成大块纯黑。
        val top = FALLBACK_TOP
        val bottom = FALLBACK_BOTTOM
        for (y in 0 until safeH) {
            val t = if (safeH == 1) 0f else y.toFloat() / (safeH - 1).toFloat()
            val color = blend(top, bottom, t)
            for (x in 0 until safeW) bitmap.setPixel(x, y, color)
        }
        return bitmap
    }

    private fun blend(start: Int, end: Int, t: Float): Int {
        fun channel(shift: Int): Int {
            val from = (start shr shift) and 0xFF
            val to = (end shr shift) and 0xFF
            return (from + ((to - from) * t).toInt()) and 0xFF
        }
        return (channel(24) shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }

    /** 留边（视频矩形以外）里纯黑像素的占比。没有留边时是 0。 */
    fun letterboxPureBlackFraction(
        bitmap: Bitmap,
        originX: Int,
        originY: Int,
        viewWidth: Int,
        viewHeight: Int,
        video: DisplayFit.Rect,
    ): Double {
        if (viewWidth <= 0 || viewHeight <= 0) return 0.0
        val left = video.x.toInt()
        val top = video.y.toInt()
        val right = (video.x + video.width).toInt()
        val bottom = (video.y + video.height).toInt()
        val maxX = minOf(bitmap.width, originX + viewWidth)
        val maxY = minOf(bitmap.height, originY + viewHeight)
        var black = 0
        var total = 0
        var y = originY.coerceAtLeast(0)
        while (y < maxY) {
            var x = originX.coerceAtLeast(0)
            val localY = y - originY
            while (x < maxX) {
                val localX = x - originX
                val inside = localX >= left && localX < right && localY >= top && localY < bottom
                if (!inside) {
                    total += 1
                    if ((bitmap.getPixel(x, y) and 0xFFFFFF) == 0) black += 1
                }
                x += 1
            }
            y += 1
        }
        if (total == 0) return 0.0
        return black.toDouble() / total.toDouble()
    }

    private fun fmt(value: Double): String = String.format(Locale.US, "%.2f", value)

    private fun rect(rect: DisplayFit.Rect): String =
        "${fmt(rect.x)},${fmt(rect.y)},${fmt(rect.width)}x${fmt(rect.height)}"
}

internal object LaneLog {
    const val TAG: String = "VelaLane"
    private val lines = ArrayDeque<String>()

    fun i(message: String) {
        android.util.Log.i(TAG, message)
        synchronized(lines) {
            lines.addLast(message)
            while (lines.size > 300) lines.removeFirst()
        }
    }

    fun snapshot(): List<String> = synchronized(lines) { lines.toList() }

    fun clear() {
        synchronized(lines) { lines.clear() }
    }
}
