package vela.core.base.lane

import kotlin.math.max

/**
 * 竖屏多格的布局。N 格上下等高叠放，格间距与双窗一致。
 * 三格时每格最低 [minimumPaneHeight]；放不下就最多开 2 路。
 */
object LaneLayout {
    const val gap: Double = 2.0

    /** 三格时每格的最低高度（点）。 */
    const val minimumPaneHeight: Double = 160.0

    /** `(H − (N−1)×间距) / N`，至少 1 点。 */
    fun paneHeight(stageHeight: Double, lanes: Int): Double {
        val count = max(lanes, 1).toDouble()
        return max((stageHeight - (count - 1.0) * gap) / count, 1.0)
    }

    fun fitsThreeLanes(stageHeight: Double): Boolean =
        paneHeight(stageHeight, 3) >= minimumPaneHeight
}
