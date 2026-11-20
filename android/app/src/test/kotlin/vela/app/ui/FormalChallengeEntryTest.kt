package vela.app.ui

import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import android.os.Looper
import vela.app.MainActivity
import vela.app.stage.StageSurface
import vela.app.stage.challengeEntryUrl

/**
 * 正式入口必须把质询地址交给界面。地址为空时不创建 WebView，用户无法通关。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h800dp")
class FormalChallengeEntryTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    @Test
    fun testMainActivityChallengeSheetLoadsEntryUrl() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        val stage = activity.stage
        stage.ageConfirmed = true
        stage.showSurface(StageSurface.Challenge)
        compose.waitForIdle()
        shadowOf(Looper.getMainLooper()).idle()

        val web = findWeb(activity.findViewById(android.R.id.content))
        assertNotNull("正式质询页没有 WebView", web)
        assertEquals(challengeEntryUrl(), web!!.tag)
    }

    private fun findWeb(root: View?): WebView? {
        if (root is WebView) return root
        if (root is ViewGroup) {
            for (index in 0 until root.childCount) {
                findWeb(root.getChildAt(index))?.let { return it }
            }
        }
        return null
    }
}
