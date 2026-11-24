package vela.app.playchain

import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.net.URI
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import vela.app.MainActivity
import vela.app.infra.Endpoints
import vela.app.infra.Settings
import vela.app.stage.StageIntent
import vela.app.stage.StageSurface
import vela.app.stage.StageViewModel
import vela.app.stage.challengeEntryUrl

/**
 * 伪上游先对目录返回质询页。确认页必须是 WebView，在页面里打开通关地址后才起播。
 * 不访问真实站点：客户端拒绝仍指向直播站的地址。
 */
@RunWith(AndroidJUnit4::class)
class ChallengeEmulatorTest {
    @Test(timeout = 360_000)
    fun testChallengePageThenPlayback() {
        val upstream = upstream()
        if (upstream.startsWith("http://")) {
            val host = runCatching { URI(upstream).host }.getOrNull().orEmpty()
            val permitted = android.security.NetworkSecurityPolicy.getInstance().isCleartextTrafficPermitted(host)
            if (!permitted) {
                fail("CLEARTEXT 不允许明文访问 $host。这不是播放结论被推翻。")
            }
        }
        Endpoints.overrideRoot(upstream)
        check(Endpoints.siteRoot().trimEnd('/') == upstream.trimEnd('/')) {
            "没能把站点根改到伪上游"
        }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val target = instrumentation.targetContext
        target.getSharedPreferences(Settings.PREFS_NAME, android.content.Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        val settings = Settings(target)
        settings.ageConfirmed = true
        settings.hintSeen = true
        java.io.File(target.filesDir, "Vela").deleteRecursively()

        val scenario = ActivityScenario.launch(MainActivity::class.java)
        val activityRef = AtomicReference<MainActivity>(null)
        val stageRef = AtomicReference<StageViewModel>(null)
        try {
            scenario.onActivity { activity ->
                activityRef.set(activity)
                Endpoints.overrideRoot(upstream)
                stageRef.set(activity.stage)
            }

            val stage = stageRef.get()
            val activity = activityRef.get()
            assertTrue(
                "没有进入质询页 surface=${stage.state.value.surface} error=${stage.state.value.catalogError}",
                waitUntil(20_000) { stage.state.value.surface == StageSurface.Challenge },
            )
            val page = challengeEntryUrl()
            assertTrue("质询地址不是伪上游：$page", page.startsWith(upstream.trimEnd('/')))

            assertTrue("正式界面没有挂上 WebView", waitUntil(90_000) {
                onMainOrNull(activity) { findWeb(activity.findViewById(android.R.id.content)) } != null
            })
            val web = onMainOrNull(activity) { findWeb(activity.findViewById(android.R.id.content)) }
                ?: error("没有 WebView")
            val passed = page.trimEnd('/') + "/__test/challenge-ok"
            onMainOrNull(activity) { web.loadUrl(passed) }
            assertTrue("WebView 没有打开通关地址", waitUntil(40_000) {
                onMainOrNull(activity) { web.url?.contains("challenge-ok") == true } == true
            })

            // WebView.url 在 loadUrl 返回时就已是新地址，不代表伪上游已收到通关请求。
            // 此时立刻重试会和通关请求抢先后，抢先到的重试拿到 403，之后没有人再重试。
            // 改为等伪上游自己说已通关（站点根由 403 变 200），再重试。
            assertTrue("伪上游没有记下通关", waitUntil(40_000) { upstreamCleared(page) })
            onMainOrNull(activity) { stage.dispatch(StageIntent.RetryAfterChallenge) }
            assertTrue(
                "通关后没有起播 username=${stage.state.value.currentUsername} note=${stage.state.value.lane.playbackNote} " +
                    "error=${stage.state.value.catalogError}",
                waitUntil(45_000) {
                    val lane = activity.session.lane.value
                    lane != null && lane.firstFrameElapsedMs >= 0L
                },
            )
            assertTrue(stage.state.value.surface != StageSurface.Challenge)
            assertTrue(stage.state.value.currentUsername != null)
        } finally {
            Endpoints.clearOverride()
            scenario.close()
        }
    }

    private fun upstream(): String {
        val args = InstrumentationRegistry.getArguments()
        val raw = args.getString("upstream").orEmpty().ifEmpty { vela.app.BuildConfig.VELA_UPSTREAM }
        assertTrue("没有伪上游地址", raw.startsWith("http://") || raw.startsWith("https://"))
        return raw.trimEnd('/')
    }

    private fun upstreamCleared(page: String): Boolean {
        val result = AtomicReference(false)
        val probe = Thread {
            val connection = java.net.URL(page.trimEnd('/') + "/").openConnection() as java.net.HttpURLConnection
            try {
                connection.connectTimeout = 3_000
                connection.readTimeout = 3_000
                result.set(connection.responseCode == 200)
            } catch (_: java.io.IOException) {
                result.set(false)
            } finally {
                connection.disconnect()
            }
        }
        probe.start()
        probe.join(8_000)
        return result.get()
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

    private fun <T> onMainOrNull(activity: android.app.Activity, block: () -> T): T? {
        val latch = CountDownLatch(1)
        val ref = AtomicReference<T>(null)
        val error = AtomicReference<Throwable>(null)
        val ran = AtomicReference(false)
        activity.runOnUiThread {
            try {
                ref.set(block())
                ran.set(true)
            } catch (thrown: Throwable) {
                error.set(thrown)
            } finally {
                latch.countDown()
            }
        }
        if (!latch.await(15, TimeUnit.SECONDS)) return null
        error.get()?.let { throw it }
        if (!ran.get()) return null
        return ref.get()
    }

    private fun waitUntil(timeoutMs: Long, predicate: () -> Boolean): Boolean {
        val deadline = android.os.SystemClock.elapsedRealtime() + timeoutMs
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            if (predicate()) return true
            Thread.sleep(400)
        }
        return predicate()
    }
}
