package vela.app.ui

import android.net.Uri
import android.webkit.WebResourceRequest
import java.util.Collections
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import vela.app.infra.OriginWebViewClient

/**
 * 主框架 POST 不会走 shouldOverrideUrlLoading。拦截器必须自己挡掉白名单外的地址，
 * 侧车也不能把外站的加载完成当成可执行脚本的页面。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WebViewGuardTest {
    @Test
    fun testMainFramePostCannotLeaveTheChallengeAllowlist() {
        val client = ChallengeNavigationClient()
        val blocked = client.shouldInterceptRequest(
            null,
            frame("https://evil.example/steal", method = "POST", mainFrame = true),
        )
        assertNotNull(blocked)
        assertEquals(403, blocked!!.statusCode)

        val allowed = client.shouldInterceptRequest(
            null,
            frame("https://chaturbate.com/auth/login/", method = "POST", mainFrame = true),
        )
        assertNull(allowed)

        val foreignGet = client.shouldInterceptRequest(
            null,
            frame("https://evil.example/pixel.gif", method = "GET", mainFrame = false),
        )
        assertNotNull(foreignGet)
        assertEquals(403, foreignGet!!.statusCode)
    }

    @Test
    fun testSidecarDoesNotFinishOnAForeignOrigin() {
        var finished: String? = null
        var errors = 0
        var rejected = 0
        val client = OriginWebViewClient(
            siteRoot = "https://chaturbate.com",
            onFinished = { finished = it },
            onMainFrameError = { errors += 1 },
            onOriginRejected = { rejected += 1 },
        )
        client.onPageFinished(null, "https://evil.example/pwn")
        assertNull(finished)
        assertEquals(1, errors)
        assertEquals(1, rejected)
        client.onPageFinished(null, "about:blank")
        assertNull(finished)
        assertEquals(1, errors)
        client.onPageFinished(null, "https://chaturbate.com/auth/login/?next=/")
        assertEquals("https://chaturbate.com/auth/login/?next=/", finished)
        assertEquals(1, errors)
        assertTrue(vela.app.stage.sidecarMayRunScript("https://chaturbate.com/auth/login/?next=/", "https://chaturbate.com"))
        assertTrue(!vela.app.stage.sidecarMayRunScript("https://evil.example/pwn", "https://chaturbate.com"))
        assertTrue(!vela.app.stage.sidecarMayRunScript(null, "https://chaturbate.com"))
    }

    private fun frame(url: String, method: String, mainFrame: Boolean): WebResourceRequest =
        object : WebResourceRequest {
            override fun getUrl(): Uri = Uri.parse(url)
            override fun isForMainFrame(): Boolean = mainFrame
            override fun isRedirect(): Boolean = false
            override fun hasGesture(): Boolean = true
            override fun getMethod(): String = method
            override fun getRequestHeaders(): Map<String, String> = Collections.emptyMap()
        }
}
