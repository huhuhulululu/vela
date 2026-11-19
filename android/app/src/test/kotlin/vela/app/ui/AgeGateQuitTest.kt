package vela.app.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import vela.app.MainActivity
import vela.app.stage.StageIntent

/**
 * 年龄确认页的退出只把内部标志置上时，Activity 不会结束，用户还停在这一页。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AgeGateQuitTest {
    @Test
    fun testQuitFromTheRealEntryFinishesTheActivity() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        assertFalse(activity.stage.state.value.ageConfirmed)
        assertFalse(activity.isFinishing)
        activity.stage.dispatch(StageIntent.Quit)
        assertTrue(activity.stage.quitRequested)
        assertTrue("点退出后 Activity 仍在", activity.isFinishing)
    }
}
