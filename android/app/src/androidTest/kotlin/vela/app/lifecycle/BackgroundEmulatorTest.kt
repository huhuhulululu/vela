package vela.app.lifecycle

import android.content.Intent
import android.os.Process
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import vela.app.MainActivity
import vela.app.infra.Endpoints
import vela.app.infra.Settings
import vela.core.base.model.FeedSource
import vela.core.lifecycle.PhoneScenePhase

/**
 * 模拟器上验 S-A5：暂停后媒体请求停止；按 HOME（没有画中画）5 秒内释放，
 * 随后 30 秒零请求、CPU 几乎不涨、没有播放线程和 WebView 进程；
 * 回前台重新解析并起播。
 * 请求窗口由伪上游日志里的 /marker/ 边界统计，脚本只数边界之间的行。
 */
@RunWith(AndroidJUnit4::class)
class BackgroundEmulatorTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private var scenario: ActivityScenario<MainActivity>? = null

    @After
    fun tearDown() {
        scenario?.close()
        scenario = null
        val target = instrumentation.targetContext
        target.getSharedPreferences(Settings.PREFS_NAME, android.content.Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        File(target.filesDir, "Vela").deleteRecursively()
    }

    @Test(timeout = 120_000)
    fun testPauseStopsMedia() {
        val upstream = upstream()
        val activity = launchReady(upstream)
        val stage = activity.stage
        onMain(activity) { stage.dispatch(vela.app.stage.StageIntent.TogglePause(null)) }
        assertTrue(waitUntil { stage.state.value.lane.manuallyPaused })
        Thread.sleep(2_000)
        hit(upstream, "/marker/pause-begin")
        Thread.sleep(5_000)
        hit(upstream, "/marker/pause-end")
        assertTrue(stage.state.value.lane.manuallyPaused)
    }

    @Test(timeout = 180_000)
    fun testBackgroundThenForegroundRestores() {
        val upstream = upstream()
        val activity = launchReady(upstream)
        val stage = activity.stage
        val name = stage.state.value.currentUsername
        assertTrue("没有房间", name != null)
        UiDevice.getInstance(instrumentation).pressHome()
        assertTrue(
            "没有进后台 phase=${activity.wiring.phase}",
            waitUntil { activity.wiring.phase == PhoneScenePhase.BACKGROUND },
        )
        assertTrue(waitUntil { activity.session.lane.value == null })
        Thread.sleep(5_000)
        assertTrue("5 秒后仍有播放线程 ${exoThreads()}", exoThreads().isEmpty())
        assertTrue("5 秒后仍有 WebView 进程 ${webViewProcesses()}", webViewProcesses().isEmpty())
        hit(upstream, "/marker/background-begin")
        val before = cpuTicks()
        Thread.sleep(30_000)
        val delta = cpuTicks() - before
        hit(upstream, "/marker/background-end")
        // 播放中一核就有上百 tick。30 tick 是 30 秒里不到 1% 的一核，算空闲噪声。
        assertTrue("后台 30 秒 CPU 增量 $delta tick，线程 ${threadNames()}", delta <= 30L)
        assertTrue(exoThreads().isEmpty())
        assertTrue(webViewProcesses().isEmpty())

        val intent = Intent(instrumentation.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        instrumentation.targetContext.startActivity(intent)
        assertTrue(
            "没有回到前台",
            waitUntil(8_000) { activity.wiring.phase == PhoneScenePhase.ACTIVE },
        )
        assertTrue(
            "回前台没有重新起播 username=${stage.state.value.currentUsername} " +
                "playback=${stage.state.value.lane.playback} note=${stage.state.value.lane.playbackNote}",
            waitUntil(25_000) {
                val lane = activity.session.lane.value
                lane != null && lane.firstFrameElapsedMs >= 0L && !lane.failed
            },
        )
        assertEquals(name, stage.state.value.currentUsername)
        assertFalsePaused(stage)
    }

    private fun assertFalsePaused(stage: vela.app.stage.StageViewModel) {
        assertTrue(!stage.state.value.lane.manuallyPaused)
    }

    private fun launchReady(upstream: String): MainActivity {
        if (upstream.startsWith("http://")) {
            val host = runCatching { URI(upstream).host }.getOrNull().orEmpty()
            val permitted = android.security.NetworkSecurityPolicy.getInstance().isCleartextTrafficPermitted(host)
            if (!permitted) fail("CLEARTEXT 不允许明文访问 $host")
        }
        val target = instrumentation.targetContext
        val prefs = target.getSharedPreferences(Settings.PREFS_NAME, android.content.Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        File(target.filesDir, "Vela").deleteRecursively()
        val settings = Settings(target)
        settings.ageConfirmed = true
        settings.hintSeen = true
        settings.muted = true
        settings.autoEnterPip = false
        settings.source = FeedSource.popular
        if (upstream.isNotEmpty()) Endpoints.overrideRoot(upstream)
        val launched = ActivityScenario.launch(MainActivity::class.java)
        scenario = launched
        val holder = arrayOfNulls<MainActivity>(1)
        launched.onActivity { holder[0] = it }
        val activity = holder[0] ?: error("Activity 没有起来")
        assertTrue(
            "目录没有房间 error=${activity.stage.state.value.catalogError} root=${Endpoints.siteRoot()}",
            waitUntil(20_000) { activity.stage.state.value.currentUsername != null },
        )
        assertTrue(
            "没有首帧 lane=${activity.session.lane.value?.lastError}",
            waitUntil(20_000) {
                val lane = activity.session.lane.value
                lane != null && lane.firstFrameElapsedMs >= 0L
            },
        )
        return activity
    }

    private fun upstream(): String {
        val args = InstrumentationRegistry.getArguments()
        val raw = args.getString("upstream").orEmpty().ifEmpty { vela.app.BuildConfig.VELA_UPSTREAM }
        assertTrue("没有伪上游地址", raw.startsWith("http://") || raw.startsWith("https://"))
        return raw.trimEnd('/')
    }

    private fun hit(upstream: String, path: String) {
        val connection = URL(upstream + path).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 5_000
            connection.readTimeout = 5_000
            connection.requestMethod = "GET"
            connection.inputStream.use { it.readBytes() }
        } catch (_: Exception) {
            runCatching { connection.errorStream?.use { it.readBytes() } }
        } finally {
            connection.disconnect()
        }
    }

    private fun exoThreads(): List<String> = threadNames().filter { name ->
        // comm 只有 15 字符。ExoPlayer:BackgroundExecutor 是进程级空闲池，
        // 播放器 release 后仍在，但不解码也不拉流。
        name.startsWith("ExoPlayer") && name != "ExoPlayer:Backg"
    }

    private fun threadNames(): List<String> {
        val dir = File("/proc/${Process.myPid()}/task")
        return dir.list()?.mapNotNull { tid ->
            runCatching { File(dir, "$tid/comm").readText().trim() }.getOrNull()
        }.orEmpty()
    }

    private fun webViewProcesses(): List<String> {
        val pkg = instrumentation.targetContext.packageName
        val root = File("/proc")
        val found = ArrayList<String>()
        for (dir in root.listFiles() ?: return found) {
            if (dir.name.any { !it.isDigit() }) continue
            val cmdline = runCatching {
                File(dir, "cmdline").readText().replace('\u0000', ' ').trim()
            }.getOrNull().orEmpty()
            if (!cmdline.contains(pkg)) continue
            if (cmdline.contains("sandboxed") || cmdline.contains("webview", ignoreCase = true)) {
                found.add(cmdline)
            }
        }
        return found
    }

    private fun cpuTicks(): Long {
        val stat = File("/proc/self/stat").readText()
        val end = stat.lastIndexOf(')')
        val fields = stat.substring(end + 2).split(' ')
        return fields[11].toLong() + fields[12].toLong()
    }

    private fun onMain(activity: android.app.Activity, block: () -> Unit) {
        val latch = java.util.concurrent.CountDownLatch(1)
        val error = java.util.concurrent.atomic.AtomicReference<Throwable>(null)
        activity.runOnUiThread {
            try {
                block()
            } catch (thrown: Throwable) {
                error.set(thrown)
            } finally {
                latch.countDown()
            }
        }
        assertTrue(latch.await(10, java.util.concurrent.TimeUnit.SECONDS))
        error.get()?.let { throw it }
    }

    private fun waitUntil(limitMs: Long = 5_000, predicate: () -> Boolean): Boolean {
        val start = android.os.SystemClock.elapsedRealtime()
        while (android.os.SystemClock.elapsedRealtime() - start < limitMs) {
            if (predicate()) return true
            Thread.sleep(50)
        }
        return predicate()
    }
}
