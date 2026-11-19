package vela.app.ui

import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.compose.ui.platform.ComposeView
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 质询页不能读本地文件或 content URI。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChallengeWebAccessTest {
    @Test
    fun testChallengeWebDisablesFileAndContentAccess() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        activity.setContentView(
            ComposeView(activity).apply {
                setContent {
                    ChallengeSheet(onDone = {}, onClose = {}, pageUrl = "about:blank")
                }
            },
        )
        Robolectric.flushForegroundThreadScheduler()
        val web = findWeb(activity.window.decorView)
        assertNotNull(web)
        assertFalse(web!!.settings.allowFileAccess)
        assertFalse(web.settings.allowContentAccess)
    }
}

private fun findWeb(view: View): WebView? {
    if (view is WebView) return view
    if (view is ViewGroup) {
        for (i in 0 until view.childCount) {
            findWeb(view.getChildAt(i))?.let { return it }
        }
    }
    return null
}
