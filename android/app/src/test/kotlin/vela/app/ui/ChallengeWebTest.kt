package vela.app.ui

import android.app.Activity
import android.net.Uri
import android.view.View
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import java.util.Collections
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import vela.core.base.net.UserAgent

/**
 * 正式质询页以前只开了 JavaScript，也拦不住跳到别的主机。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h800dp")
class ChallengeWebTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun testNavigationStaysOnSiteRootChallengeCdnAndDebugLoopback() {
        assertTrue(challengeNavigationAllowed("https://chaturbate.com/", "https://chaturbate.com", false))
        assertTrue(challengeNavigationAllowed("https://www.chaturbate.com/x", "https://chaturbate.com", false))
        assertTrue(
            challengeNavigationAllowed(
                "https://challenges.cloudflare.com/cdn-cgi/challenge",
                "https://chaturbate.com",
                false,
            ),
        )
        assertTrue(
            challengeNavigationAllowed(
                "https://static.cloudflareinsights.com/beacon.min.js",
                "https://chaturbate.com",
                false,
            ),
        )
        assertFalse(challengeNavigationAllowed("https://evil.example/", "https://chaturbate.com", false))
        assertFalse(challengeNavigationAllowed("http://chaturbate.com/", "https://chaturbate.com", false))
        assertFalse(challengeNavigationAllowed("javascript:alert(1)", "https://chaturbate.com", false))
        assertFalse(challengeNavigationAllowed("http://10.0.2.2/", "https://chaturbate.com", false))
        assertTrue(challengeNavigationAllowed("http://10.0.2.2:9/__test/challenge-ok", "https://chaturbate.com", true))
        assertTrue(challengeNavigationAllowed("http://127.0.0.1:9/", "http://10.0.2.2:9", true))
        assertTrue(
            challengeNavigationAllowed("http://10.0.2.2:9/", "http://10.0.2.2:9", false),
        )
        assertFalse(challengeNavigationAllowed("https://chaturbate.com/", "http://10.0.2.2:9", false))
    }

    @Test
    fun testFormalChallengeWebUsesTheSharedPolicyAndDestroysOnRelease() {
        var shown by mutableStateOf(true)
        compose.setContent {
            if (shown) {
                Box(Modifier.requiredSize(360.dp, 800.dp)) {
                    vela.app.ui.ChallengeSheet(
                        onDone = {},
                        onClose = {},
                        pageUrl = "https://chaturbate.com/",
                    )
                }
            }
        }
        compose.waitForIdle()
        val web = findWeb(resumedActivity().findViewById(android.R.id.content))
        assertWebConfigured(web)
        shown = false
        compose.waitForIdle()
        assertTrue(shadowOf(web).wasDestroyCalled())
    }

    @Test
    fun testStageChallengeSheetUsesTheSameWebPolicy() {
        // 舞台包里那份没有 destroy 的质询页已删。共享配置仍是 configureChallengeWeb，正式页走同一套。
        compose.setContent {
            Box(Modifier.requiredSize(360.dp, 800.dp)) {
                AndroidView(
                    factory = { context ->
                        WebView(context).also { configureChallengeWeb(it) {} }
                    },
                )
            }
        }
        compose.waitForIdle()
        val web = findWeb(resumedActivity().findViewById(android.R.id.content))
        assertWebConfigured(web)
    }

    private fun resumedActivity(): Activity =
        ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).single()

    private fun assertWebConfigured(web: WebView?) {
        assertTrue(web != null)
        val view = web!!
        assertEquals(UserAgent.safari, view.settings.userAgentString)
        assertTrue(view.settings.domStorageEnabled)
        assertTrue(view.settings.javaScriptEnabled)
        assertTrue(view.settings.mediaPlaybackRequiresUserGesture)
        assertFalse(view.settings.allowFileAccess)
        assertFalse(view.settings.allowContentAccess)
        val client = view.webViewClient
        assertTrue(client.shouldOverrideUrlLoading(view, request("https://evil.example/steal")))
        assertFalse(client.shouldOverrideUrlLoading(view, request("https://chaturbate.com/room")))
        assertFalse(client.shouldOverrideUrlLoading(view, request("https://challenges.cloudflare.com/cdn-cgi")))
    }

    private fun request(url: String): WebResourceRequest = object : WebResourceRequest {
        override fun getUrl(): Uri = Uri.parse(url)
        override fun isForMainFrame(): Boolean = true
        override fun isRedirect(): Boolean = false
        override fun hasGesture(): Boolean = true
        override fun getMethod(): String = "GET"
        override fun getRequestHeaders(): Map<String, String> = Collections.emptyMap()
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
