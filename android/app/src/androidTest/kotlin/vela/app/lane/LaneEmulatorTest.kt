package vela.app.lane

import android.graphics.Bitmap
import android.graphics.Color
import android.view.Gravity
import android.widget.FrameLayout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import vela.app.MainActivity
import vela.app.playback.LaneHub
import vela.core.base.play.StreamRendition
import vela.core.hls.DisplayFit

/**
 * 模拟器上验首帧、未裁切、留边不是大块纯黑，以及 R1 到 R3。
 * 伪上游由 emulator-run 经 `-e upstream` 传入。位图只在设备上判，不取回。
 * 明文被目标 SDK 拦住时失败信息以 CLEARTEXT 开头，那不是 P1 结论被推翻。
 */
@RunWith(AndroidJUnit4::class)
class LaneEmulatorTest {
    @Test(timeout = 180_000)
    fun testFirstFrameFitLetterboxAndAdoption() {
        val args = InstrumentationRegistry.getArguments()
        val upstream = args.getString("upstream").orEmpty().ifEmpty { vela.app.BuildConfig.VELA_UPSTREAM }
        assertTrue("没有伪上游地址", upstream.startsWith("http://") || upstream.startsWith("https://"))
        if (upstream.startsWith("http://")) {
            val host = runCatching { URI(upstream).host }.getOrNull().orEmpty()
            val permitted = android.security.NetworkSecurityPolicy.getInstance().isCleartextTrafficPermitted(host)
            if (!permitted) {
                fail(
                    "CLEARTEXT 应用目标 SDK 不允许明文访问 $host。" +
                        "模拟器伪上游是 http，清单只有 00 和 PiP1 能改。这不是 P1 播放结论被推翻。",
                )
            }
        }

        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        val activityRef = AtomicReference<android.app.Activity>(null)
        val frameRef = AtomicReference<LaneFrame>(null)
        val laneRef = AtomicReference<PlaybackLane>(null)
        val hubRef = AtomicReference<LaneHub>(null)
        val renews = AtomicInteger()
        try {
            scenario.onActivity { activity ->
                activityRef.set(activity)
                val hub = LaneHub()
                check(hub.start())
                hubRef.set(hub)
                val lane = PlaybackLane.create(activity, hub = hub)
                laneRef.set(lane)
                val thumb = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
                thumb.eraseColor(Color.rgb(180, 90, 48))
                val host = FrameLayout(activity)
                host.setBackgroundColor(DisplayCheck.FALLBACK_TOP)
                val compose = ComposeView(activity)
                compose.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
                host.addView(
                    compose,
                    FrameLayout.LayoutParams(PANE_WIDTH, PANE_HEIGHT, Gravity.CENTER),
                )
                activity.setContentView(host)
                compose.setContent {
                    LaneSurface(lane = lane, thumbnail = thumb, modifier = Modifier.fillMaxSize())
                }
            }

            val activity = activityRef.get()
            assertTrue("LaneFrame 没有按 270×480 排出来", waitUntil(5_000) {
                onMain(activity) {
                    val host = activity.findViewById<FrameLayout>(android.R.id.content)
                    val frame = host?.let { findFrame(it) }
                    if (frame != null) frameRef.set(frame)
                    frame != null && frame.width == PANE_WIDTH && frame.height == PANE_HEIGHT
                }
            })

            val lane = laneRef.get()
            lane.renewRendition = { _, cap ->
                renews.incrementAndGet()
                val body = httpText("$upstream/master.m3u8")
                StreamRendition.select(body, URI("$upstream/master.m3u8"), cap)
            }
            onMain(activity) {
                lane.play(
                    StreamRendition.unselected(URI("$upstream/master.m3u8")),
                    username = "synth",
                    peakBitrate = Double.POSITIVE_INFINITY,
                    allowed = true,
                )
            }

            val started = android.os.SystemClock.elapsedRealtime()
            assertTrue(
                "首帧超过 8 秒。log=${LaneLog.snapshot().takeLast(12)}",
                waitUntil(8_000) { lane.firstFrameElapsedMs in 0..8_000 },
            )
            val firstFrameMs = android.os.SystemClock.elapsedRealtime() - started
            assertTrue("首帧墙钟 ${firstFrameMs}ms", firstFrameMs <= 8_000)

            assertTrue(
                "没有 uncropped=true。log=${LaneLog.snapshot().filter { it.contains("display-check") }}",
                waitUntil(5_000) {
                    LaneLog.snapshot().any { it.contains("display-check") && it.contains("uncropped=true") }
                },
            )
            val rect = onMain(activity) { frameRef.get().appliedVideoRect }
            assertTrue("留边不存在 rect=$rect", rect.height > 0 && rect.height < PANE_HEIGHT * 0.9)

            instrumentation.waitForIdleSync()
            val shot = instrumentation.uiAutomation.takeScreenshot()
            check(shot != null) { "截图失败" }
            val origin = IntArray(2)
            onMain(activity) { frameRef.get().getLocationOnScreen(origin) }
            val black = DisplayCheck.letterboxPureBlackFraction(
                shot,
                origin[0],
                origin[1],
                PANE_WIDTH,
                PANE_HEIGHT,
                rect,
            )
            shot.recycle()
            assertTrue("留边纯黑占比 $black", black < 0.95)

            val renderedAtStart = onMain(activity) { lane.renderedOutputBufferCount() }
            assertTrue("还没有渲染缓冲 rendered=$renderedAtStart", renderedAtStart > 0)

            httpText("$upstream/__test/rewind")
            val rewindAt = android.os.SystemClock.elapsedRealtime()
            val sawReset = waitUntil(10_000) { lane.reprepareCount in 1..2 && !lane.failed }
            if (!sawReset) {
                fail(
                    "R1-NOT-OBSERVED 10 秒内没有原地重新 prepare。" +
                        "reprepare=${lane.reprepareCount} failed=${lane.failed} other=${lane.otherErrorCount} " +
                        "log=${LaneLog.snapshot().takeLast(15)}",
                )
            }
            val recovered = waitUntil(10_000) {
                onMain(activity) { lane.renderedOutputBufferCount() } > renderedAtStart && !lane.failed
            }
            val recoverMs = android.os.SystemClock.elapsedRealtime() - rewindAt
            if (!recovered || recoverMs > 10_000 || lane.reprepareCount > 2 || lane.failed || lane.silentRetryUsed) {
                fail(
                    "P1-OVERTURN R1 reprepare=${lane.reprepareCount} failed=${lane.failed} " +
                        "retryUsed=${lane.silentRetryUsed} recoverMs=$recoverMs other=${lane.otherErrorCount}",
                )
            }

            val beforeRotate = onMain(activity) { lane.renderedOutputBufferCount() }
            val errorsBeforeRotate = lane.otherErrorCount
            val resetsBeforeRotate = lane.reprepareCount
            httpText("$upstream/__test/rotate")
            Thread.sleep(10_000)
            val afterRotate = onMain(activity) { lane.renderedOutputBufferCount() }
            if (lane.otherErrorCount != errorsBeforeRotate || lane.failed || lane.reprepareCount != resetsBeforeRotate) {
                fail(
                    "P1-OVERTURN R3 other=${lane.otherErrorCount} reprepare=${lane.reprepareCount} " +
                        "failed=${lane.failed} rendered=$beforeRotate->$afterRotate",
                )
            }
            assertTrue("R3 之后画面没有继续 rendered=$beforeRotate->$afterRotate", afterRotate > beforeRotate)

            val beforeExpire = onMain(activity) { lane.renderedOutputBufferCount() }
            val errorsBeforeExpire = lane.otherErrorCount
            httpText("$upstream/__test/expire")
            val expireAt = android.os.SystemClock.elapsedRealtime()
            val renewed = waitUntil(8_000) { renews.get() >= 1 && !lane.failed && lane.otherErrorCount == errorsBeforeExpire }
            val expireMs = android.os.SystemClock.elapsedRealtime() - expireAt
            if (!renewed || expireMs > 8_000 || lane.failed || lane.otherErrorCount != errorsBeforeExpire) {
                fail(
                    "P1-OVERTURN R2 renews=${renews.get()} failed=${lane.failed} " +
                        "other=${lane.otherErrorCount} elapsed=$expireMs",
                )
            }
            val afterExpire = onMain(activity) { lane.renderedOutputBufferCount() }
            assertTrue("R2 之后画面中断 rendered=$beforeExpire->$afterExpire", afterExpire >= beforeExpire)
        } finally {
            val activity = activityRef.get()
            val lane = laneRef.get()
            val hub = hubRef.get()
            if (activity != null && lane != null) {
                onMain(activity) { lane.release() }
            }
            hub?.stopListener()
            scenario.close()
        }
    }

    private fun findFrame(root: android.view.View): LaneFrame? {
        if (root is LaneFrame) return root
        if (root is android.view.ViewGroup) {
            for (index in 0 until root.childCount) {
                val found = findFrame(root.getChildAt(index))
                if (found != null) return found
            }
        }
        return null
    }

    private fun <T> onMain(activity: android.app.Activity, block: () -> T): T {
        val box = AtomicReference<T>()
        val error = AtomicReference<Throwable>()
        val latch = CountDownLatch(1)
        activity.runOnUiThread {
            try {
                box.set(block())
            } catch (thrown: Throwable) {
                error.set(thrown)
            } finally {
                latch.countDown()
            }
        }
        check(latch.await(15, TimeUnit.SECONDS)) { "主线程没有回来" }
        error.get()?.let { throw it }
        return box.get()
    }

    private fun waitUntil(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val end = android.os.SystemClock.elapsedRealtime() + timeoutMs
        while (android.os.SystemClock.elapsedRealtime() < end) {
            if (condition()) return true
            Thread.sleep(100)
        }
        return condition()
    }

    private fun httpText(url: String): String {
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        connection.connectTimeout = 4_000
        connection.readTimeout = 4_000
        connection.instanceFollowRedirects = false
        return try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            check(code in 200..299) { "$url -> $code $body" }
            body
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val PANE_WIDTH: Int = 270
        const val PANE_HEIGHT: Int = 480
    }
}
