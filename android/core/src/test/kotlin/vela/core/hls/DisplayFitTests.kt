package vela.core.hls

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 画面按原始比例放进窗格：完全落在窗格内，且宽高比与片源相差不超过 1%，才算没裁切。 */
class DisplayFitTests {
    /** 16:9 放进竖屏整屏（393×852）和双窗半屏（393×410）都是上下留边，uncropped 为真。 */
    @Test
    fun testSixteenByNineFitsPhoneAndHalfPaneUncropped() {
        val presentation = DisplayFit.Size(width = 1920.0, height = 1080.0)
        val phone = DisplayFit.Rect(x = 0.0, y = 0.0, width = 393.0, height = 852.0)
        val pane = DisplayFit.Rect(x = 0.0, y = 0.0, width = 393.0, height = 410.0)
        for (bounds in listOf(phone, pane)) {
            val fitted = DisplayFit.fitted(presentation, bounds)
            assertTrue(
                DisplayFit.isUncropped(videoRect = fitted, bounds = bounds, presentation = presentation),
                "bounds ${bounds.width}x${bounds.height}",
            )
            assertTrue(fitted.width > 0.0)
            assertTrue(fitted.height > 0.0)
            assertTrue(fitted.height < bounds.height, "16:9 在竖屏窗格里应当上下留边")
            assertTrue(fitted.minX >= bounds.minX)
            assertTrue(fitted.minY >= bounds.minY)
            assertTrue(fitted.maxX <= bounds.maxX)
            assertTrue(fitted.maxY <= bounds.maxY)
        }
    }

    /** aspectFill 会有一条边超出窗格，不能当成未裁切。 */
    @Test
    fun testAspectFillSizedVideoRectIsNotUncropped() {
        val presentation = DisplayFit.Size(width = 16.0, height = 9.0)
        val boundsList = listOf(
            DisplayFit.Rect(x = 0.0, y = 0.0, width = 393.0, height = 852.0),
            DisplayFit.Rect(x = 0.0, y = 0.0, width = 393.0, height = 410.0),
        )
        for (bounds in boundsList) {
            val scale = maxOf(bounds.width / presentation.width, bounds.height / presentation.height)
            val width = presentation.width * scale
            val height = presentation.height * scale
            val videoRect = DisplayFit.Rect(
                x = bounds.x + bounds.width / 2.0 - width / 2.0,
                y = bounds.y + bounds.height / 2.0 - height / 2.0,
                width = width,
                height = height,
            )
            assertFalse(
                DisplayFit.isUncropped(videoRect = videoRect, bounds = bounds, presentation = presentation),
                "bounds ${bounds.width}x${bounds.height}",
            )
        }
    }

    @Test
    fun testAspectToleranceIsOnePercent() {
        val presentation = DisplayFit.Size(width = 100.0, height = 100.0)
        val bounds = DisplayFit.Rect(x = 0.0, y = 0.0, width = 200.0, height = 200.0)
        val onLimit = DisplayFit.Rect(x = 0.0, y = 0.0, width = 101.0, height = 100.0)
        val overLimit = DisplayFit.Rect(x = 0.0, y = 0.0, width = 102.0, height = 100.0)
        assertTrue(DisplayFit.isUncropped(videoRect = onLimit, bounds = bounds, presentation = presentation))
        assertFalse(DisplayFit.isUncropped(videoRect = overLimit, bounds = bounds, presentation = presentation))
    }
}
