package vela.app.infra

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import vela.app.ui.agegate.AgeGateScreen
import vela.app.ui.theme.Theme

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AgeGateTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun testEnterAndQuitAreSeparate() {
        var entered = 0
        var quit = 0
        compose.setContent {
            AgeGateScreen(onEnter = { entered += 1 }, onQuit = { quit += 1 })
        }
        compose.onNodeWithText("VELA").assertIsDisplayed()
        compose.onNodeWithText("已满 18 岁").assertIsDisplayed().performClick()
        compose.onNodeWithText("退出").assertIsDisplayed().performClick()
        assertEquals(1, entered)
        assertEquals(1, quit)
    }

    @Test
    fun testShowingThePageDoesNotConfirmAge() {
        var entered = false
        compose.setContent {
            AgeGateScreen(onEnter = { entered = true }, onQuit = {})
        }
        compose.onNodeWithText("仅供年满 18 岁的人使用。", substring = true).assertIsDisplayed()
        assertFalse(entered)
    }

    @Test
    fun testThemeMatchesSharedPalette() {
        // Compose 把颜色收成 8 位通道，和 Swift 的 0..1 浮点差最多半级。
        val step = 1f / 255f
        assertEquals(0.91f, Theme.champagne.red, step)
        assertEquals(0.72f, Theme.champagne.green, step)
        assertEquals(0.52f, Theme.champagne.blue, step)
        assertEquals(0.045f, Theme.ink.red, step)
        assertEquals(0.92f, Theme.textPrimary.alpha, step)
        assertEquals(0.72f, Theme.textSecondary.alpha, step)
    }
}
