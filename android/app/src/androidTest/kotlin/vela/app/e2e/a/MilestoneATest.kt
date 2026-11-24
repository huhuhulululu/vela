package vela.app.e2e.a

import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Process
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiSelector
import androidx.test.uiautomator.Until
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import vela.app.MainActivity
import vela.app.infra.Endpoints
import vela.app.infra.Settings
import vela.app.lane.DisplayCheck
import vela.app.lane.LaneFrame
import vela.app.lane.LaneLog
import vela.app.stage.StageIntent
import vela.app.stage.StageSurface
import vela.app.ui.primaryTarget
import vela.core.base.model.FeedSource
import vela.core.base.model.TasteKeys
import vela.core.lifecycle.PhoneScenePhase

/**
 * 里程碑 A 在真实单窗上走一遍：未确认年龄不拉目录，确认后出画，
 * 默认静音，横屏流不裁切，划到下一间，收藏和口味写进本机。
 * 暂停不再拉媒体；按 HOME 后释放，回前台重新起播。
 * 杀进程后的断言在下一次仪器进程里跑（[journeyTouchedThisProcess] 还在就说明没杀掉）。
 * 请求窗口由宿主机按 /marker/ 统计。不访问真实站点。
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class MilestoneATest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private var scenario: ActivityScenario<MainActivity>? = null

    @After
    fun tearDown() {
        scenario?.close()
        scenario = null
    }

    @Test(timeout = 300_000)
    fun test01Journey() {
        journeyTouchedThisProcess = true
        val upstream = upstream()
        resetLocalData()
        val activity = launch()
        val device = UiDevice.getInstance(instrumentation)
        val stage = activity.stage
        assertFalse(stage.state.value.ageConfirmed)
        assertTrue(stage.state.value.rooms.isEmpty())
        assertFalse(
            "首次启动不该先写下静音键",
            activity.getSharedPreferences(Settings.PREFS_NAME, android.content.Context.MODE_PRIVATE)
                .contains(Settings.KEY_MUTED),
        )

        hit(upstream, "/marker/age-begin")
        Thread.sleep(2_000)
        hit(upstream, "/marker/age-end")
        assertFalse(stage.state.value.ageConfirmed)

        val enter = device.findObject(UiSelector().text("已满 18 岁"))
        assertTrue("没有年龄确认按钮\n${hierarchy(device)}", enter.waitForExists(8_000))
        enter.click()
        assertTrue("年龄确认没有写上", waitUntil { stage.state.value.ageConfirmed })
        onMain(activity) { stage.dispatch(StageIntent.DismissHint) }

        val popular = device.findObject(UiSelector().description("切换到「热门」"))
        assertTrue("没有热门来源\n${hierarchy(device)}", popular.waitForExists(8_000))
        popular.click()

        assertTrue(
            "没有停在热门 source=${stage.state.value.source} error=${stage.state.value.catalogError} " +
                "root=${Endpoints.siteRoot()} rooms=${stage.state.value.rooms.size}",
            waitUntil(20_000) {
                stage.state.value.source == FeedSource.popular &&
                    stage.state.value.currentUsername != null &&
                    stage.state.value.rooms.size >= 2
            },
        )
        val firstRoom = stage.state.value.currentUsername
        assertTrue(
            "首帧没有在起播后 8 秒内出现 username=$firstRoom note=${stage.state.value.lane.playbackNote}",
            waitUntil(20_000) {
                val lane = activity.session.lane.value
                lane != null && lane.firstFrameElapsedMs in 0..8_000 && !lane.failed
            },
        )
        val lane = activity.session.lane.value ?: error("没有播放器")
        assertEquals(firstRoom, lane.playingUsername ?: stage.state.value.currentUsername)
        assertTrue(stage.state.value.muted)
        assertFalse(
            activity.getSharedPreferences(Settings.PREFS_NAME, android.content.Context.MODE_PRIVATE)
                .contains(Settings.KEY_MUTED),
        )
        assertTrue(
            "静音后音量不是 0 或音轨还开着",
            waitUntil(5_000) {
                onMain(activity) { playerVolume(lane) == 0f && audioEnabled(lane) == 0 }
            },
        )

        assertTrue(
            "没有 uncropped=true。log=${LaneLog.snapshot().filter { it.contains("display-check") }.takeLast(4)}",
            waitUntil(8_000) {
                LaneLog.snapshot().any { line ->
                    line.contains("display-check") &&
                        line.contains("gravity=fit") &&
                        line.contains("uncropped=true") &&
                        !line.contains("presentation=0.00x0.00")
                }
            },
        )
        val frame = waitFrame(activity)
        val rect = frame.appliedVideoRect
        assertTrue(
            "横屏流没有留边 rect=$rect pane=${frame.width}x${frame.height}",
            rect.height > 0 && (rect.height < frame.height * 0.9 || rect.width < frame.width * 0.9),
        )
        instrumentation.waitForIdleSync()
        val shot = instrumentation.uiAutomation.takeScreenshot()
        check(shot != null) { "截图失败" }
        val origin = IntArray(2)
        onMain(activity) { frame.getLocationOnScreen(origin) }
        val black = DisplayCheck.letterboxPureBlackFraction(
            shot,
            origin[0],
            origin[1],
            frame.width,
            frame.height,
            rect,
        )
        shot.recycle()
        assertTrue("留边纯黑占比 $black", black < 0.95)

        val renderedAtMute = onMain(activity) { lane.renderedOutputBufferCount() }
        hit(upstream, "/marker/mute-begin")
        val muteEnd = android.os.SystemClock.elapsedRealtime() + 60_000
        while (android.os.SystemClock.elapsedRealtime() < muteEnd) {
            assertFalse("静音稳态里播放失败 ${lane.lastError}", lane.failed)
            Thread.sleep(1_000)
        }
        hit(upstream, "/marker/mute-end")
        val renderedAfterMute = onMain(activity) { lane.renderedOutputBufferCount() }
        assertTrue(
            "静音的 60 秒里画面没有继续渲染 $renderedAtMute -> $renderedAfterMute",
            renderedAfterMute > renderedAtMute,
        )

        val beforeSwipe = stage.state.value.currentUsername
        val framesBefore = LaneLog.snapshot().count { it.startsWith("first-frame") }
        val width = device.displayWidth
        val height = device.displayHeight
        // 手指上移是下一间。已经在最后一间时改成下移，回到上一间。
        val forward = stage.state.value.index < stage.state.value.rooms.lastIndex
        if (forward) {
            device.swipe(width / 2, height * 2 / 3, width / 2, height / 5, 25)
        } else {
            device.swipe(width / 2, height / 5, width / 2, height * 2 / 3, 25)
        }
        if (!waitUntil(4_000) { stage.state.value.currentUsername != null && stage.state.value.currentUsername != beforeSwipe }) {
            if (forward) {
                device.swipe(width / 2, height / 5, width / 2, height * 2 / 3, 25)
            } else {
                device.swipe(width / 2, height * 2 / 3, width / 2, height / 5, 25)
            }
        }
        assertTrue(
            "划动没有换房，仍是 $beforeSwipe index=${stage.state.value.index} rooms=${stage.state.value.rooms.map { it.username }}",
            waitUntil(6_000) { stage.state.value.currentUsername != null && stage.state.value.currentUsername != beforeSwipe },
        )
        val secondRoom = stage.state.value.currentUsername
        assertTrue(
            "换房后没有首帧 username=$secondRoom note=${stage.state.value.lane.playbackNote} failed=${activity.session.lane.value?.failed}",
            waitUntil(20_000) {
                LaneLog.snapshot().count { it.startsWith("first-frame") } > framesBefore &&
                    activity.session.lane.value?.failed != true
            },
        )

        revealChrome(device, activity)
        if (!clickInChrome(device, activity, "收藏当前房间")) {
            onMain(activity) { stage.dispatch(StageIntent.ToggleFavorite(stage.state.value.primaryTarget())) }
        }
        assertTrue(
            "点心形后没有收藏 $secondRoom",
            waitUntil { stage.state.value.currentIsFavorite },
        )

        revealChrome(device, activity)
        if (!clickInChrome(device, activity, "我的口味")) {
            onMain(activity) { stage.dispatch(StageIntent.ShowSurface(StageSurface.Taste)) }
        }
        assertTrue(waitUntil { stage.state.value.surface == StageSurface.Taste })
        val tasteKey = TasteKeys.tagKey("synth")
        onMain(activity) { stage.dispatch(StageIntent.BoostTaste(tasteKey)) }
        assertTrue(
            "口味里没有 $tasteKey liked=${stage.state.value.taste.liked.map { it.key }}",
            waitUntil { stage.state.value.taste.liked.any { it.key == tasteKey } },
        )
        onMain(activity) { stage.dispatch(StageIntent.DismissSurfaces) }

        onMain(activity) {
            stage.dispatch(StageIntent.TogglePause(stage.state.value.primaryTarget()))
        }
        assertTrue(waitUntil { stage.state.value.lane.manuallyPaused })
        hit(upstream, "/marker/pause-begin")
        Thread.sleep(5_000)
        hit(upstream, "/marker/pause-end")
        assertTrue(stage.state.value.lane.manuallyPaused)
        onMain(activity) {
            stage.dispatch(StageIntent.TogglePause(stage.state.value.primaryTarget()))
        }
        assertTrue(
            "取消暂停后没有重新出画",
            waitUntil(20_000) {
                !stage.state.value.lane.manuallyPaused &&
                    activity.session.lane.value?.let { it.firstFrameElapsedMs >= 0L && !it.failed } == true
            },
        )

        val resumedName = stage.state.value.currentUsername
        device.pressHome()
        assertTrue(
            "没有进后台 phase=${activity.wiring.phase}",
            waitUntil { activity.wiring.phase == PhoneScenePhase.BACKGROUND },
        )
        assertTrue(waitUntil { activity.session.lane.value == null })
        Thread.sleep(5_000)
        assertTrue("5 秒后仍有播放线程 ${exoThreads()}", exoThreads().isEmpty())
        assertTrue("5 秒后仍有 WebView 进程 ${webViewProcesses()}", webViewProcesses().isEmpty())
        hit(upstream, "/marker/background-begin")
        val ticks = cpuTicks()
        Thread.sleep(30_000)
        val delta = cpuTicks() - ticks
        hit(upstream, "/marker/background-end")
        // 与后台模式同一口径：30 秒里 30 tick 不到一核的 1%，当空闲噪声。计划写的 1 tick 在模拟器上站不住。
        assertTrue("后台 30 秒 CPU 增量 $delta tick，线程 ${threadNames()}", delta <= 30L)
        assertTrue(exoThreads().isEmpty())
        assertTrue(webViewProcesses().isEmpty())

        val back = Intent(instrumentation.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        instrumentation.targetContext.startActivity(back)
        assertTrue(
            "没有回到前台",
            waitUntil(8_000) { activity.wiring.phase == PhoneScenePhase.ACTIVE },
        )
        assertTrue(
            "回前台没有重新起播 username=${stage.state.value.currentUsername} note=${stage.state.value.lane.playbackNote}",
            waitUntil(25_000) {
                val playing = activity.session.lane.value
                playing != null && playing.firstFrameElapsedMs >= 0L && !playing.failed
            },
        )
        assertEquals(resumedName, stage.state.value.currentUsername)
        assertFalse(stage.state.value.lane.manuallyPaused)
        assertTrue(stage.state.value.curation.isFavorite(secondRoom!!))
        assertTrue(stage.state.value.taste.liked.any { it.key == tasteKey })
    }

    @Test(timeout = 60_000)
    fun test02FavoriteAndTasteSurviveProcessDeath() {
        assertFalse(
            "还在上一次旅程的进程里，收藏可能只在内存中",
            journeyTouchedThisProcess,
        )
        val target = instrumentation.targetContext
        val directory = File(target.filesDir, "Vela")
        val curation = File(directory, "feed-curation.json")
        val taste = File(directory, "feed-taste.json")
        assertTrue("收藏文件不在 ${curation.path}", curation.isFile)
        assertTrue("口味文件不在 ${taste.path}", taste.isFile)
        val tasteKey = TasteKeys.tagKey("synth")
        assertTrue(taste.readText().contains(tasteKey))
        val prefs = target.getSharedPreferences(Settings.PREFS_NAME, android.content.Context.MODE_PRIVATE)
        assertTrue(prefs.getBoolean(Settings.KEY_AGE, false))
        assertFalse(prefs.contains(Settings.KEY_MUTED))

        val upstream = upstream()
        val activity = launch()
        val stage = activity.stage
        assertTrue(stage.state.value.ageConfirmed)
        assertTrue(stage.state.value.muted)
        assertTrue(
            "杀进程后收藏丢了 favorites=${stage.state.value.curation.favorites}",
            waitUntil(15_000) { stage.state.value.curation.favorites.isNotEmpty() },
        )
        assertTrue(
            "杀进程后口味里没有 $tasteKey liked=${stage.state.value.taste.liked.map { it.key }}",
            stage.state.value.taste.liked.any { it.key == tasteKey },
        )
        val kept = stage.state.value.curation.favorites
        assertTrue(kept.any { it.startsWith("synth_") })
        if (upstream.startsWith("http://")) {
            assertTrue(Endpoints.siteRoot().startsWith("http://"))
        }
    }

    private fun launch(): MainActivity {
        val upstream = upstream()
        // 先指到伪上游再启动，避免已确认年龄的进程在 hooks 之前去碰真站。
        Endpoints.overrideRoot(upstream)
        val launched = ActivityScenario.launch(MainActivity::class.java)
        scenario = launched
        val holder = arrayOfNulls<MainActivity>(1)
        launched.onActivity { activity ->
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            holder[0] = activity
        }
        val activity = holder[0] ?: error("Activity 没有起来")
        if (Endpoints.siteRoot().trimEnd('/') != upstream) {
            Endpoints.overrideRoot(upstream)
        }
        check(Endpoints.siteRoot().trimEnd('/') == upstream) { "站点根不是伪上游：${Endpoints.siteRoot()}" }
        return activity
    }

    private fun resetLocalData() {
        val target = instrumentation.targetContext
        target.getSharedPreferences(Settings.PREFS_NAME, android.content.Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        File(target.filesDir, "Vela").deleteRecursively()
    }

    private fun upstream(): String {
        val raw = InstrumentationRegistry.getArguments().getString("upstream").orEmpty()
            .ifEmpty { vela.app.BuildConfig.VELA_UPSTREAM }
        assertTrue("没有伪上游地址", raw.startsWith("http://") || raw.startsWith("https://"))
        val trimmed = raw.trimEnd('/')
        val host = runCatching { URI(trimmed).host }.getOrNull().orEmpty()
        assertTrue("伪上游主机不是回环或模拟器宿主机：$host", host == "10.0.2.2" || host == "127.0.0.1" || host.equals("localhost", ignoreCase = true))
        if (trimmed.startsWith("http://")) {
            val permitted = android.security.NetworkSecurityPolicy.getInstance().isCleartextTrafficPermitted(host)
            if (!permitted) fail("CLEARTEXT 不允许明文访问 $host")
        }
        return trimmed
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

    private fun waitFrame(activity: MainActivity): LaneFrame {
        val found = arrayOfNulls<LaneFrame>(1)
        assertTrue("没有 LaneFrame", waitUntil(8_000) {
            onMain(activity) {
                val content = activity.findViewById<android.view.View>(android.R.id.content)
                found[0] = content?.let { findFrame(it) }
                val frame = found[0]
                frame != null && frame.width > 0 && frame.height > 0 && frame.appliedVideoRect.height > 0
            }
        })
        return found[0] ?: error("没有 LaneFrame")
    }

    private fun findFrame(root: android.view.View): LaneFrame? {
        if (root is LaneFrame) return root
        if (root is android.view.ViewGroup) {
            for (index in 0 until root.childCount) {
                findFrame(root.getChildAt(index))?.let { return it }
            }
        }
        return null
    }

    private fun playerVolume(lane: vela.app.lane.PlaybackLane): Float = lane.playerVolumeForTest()

    private fun audioEnabled(lane: vela.app.lane.PlaybackLane): Int = lane.audioEnabledCountForTest()

    private fun exoThreads(): List<String> = threadNames().filter { name ->
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
        val found = ArrayList<String>()
        for (dir in File("/proc").listFiles() ?: return found) {
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

    /** 控制栏会自己收起，点一下空白再把整栏叫出来。 */
    private fun revealChrome(device: UiDevice, activity: MainActivity) {
        onMain(activity) { activity.stage.dispatch(StageIntent.NoteActivity) }
        assertTrue(waitUntil { activity.stage.state.value.chromeShown })
        device.wait(Until.findObject(By.text("热门")), 2_000)
    }

    /**
     * 来源芯片横排，720 宽时心形和口味在右缘外面。
     * 先在栏上向左滑，再按 contentDescription 点。
     */
    private fun clickInChrome(device: UiDevice, activity: MainActivity, description: String): Boolean {
        val width = device.displayWidth
        val top = 110
        var node = device.findObject(UiSelector().description(description))
        var tries = 0
        while (!node.waitForExists(700) && tries < 5) {
            device.swipe(width - 24, top, 24, top, 12)
            tries += 1
            node = device.findObject(UiSelector().description(description))
        }
        if (!node.waitForExists(500)) return false
        node.click()
        return true
    }

    private fun hierarchy(device: UiDevice): String = runCatching {
        val file = File(instrumentation.targetContext.cacheDir, "e2e-a-window.xml")
        device.dumpWindowHierarchy(file)
        file.readText().take(4_000)
    }.getOrDefault("")

    private fun <T> onMain(activity: android.app.Activity, block: () -> T): T {
        val box = java.util.concurrent.atomic.AtomicReference<T>()
        val error = java.util.concurrent.atomic.AtomicReference<Throwable>()
        val latch = java.util.concurrent.CountDownLatch(1)
        activity.runOnUiThread {
            try {
                box.set(block())
            } catch (thrown: Throwable) {
                error.set(thrown)
            } finally {
                latch.countDown()
            }
        }
        assertTrue(latch.await(15, java.util.concurrent.TimeUnit.SECONDS))
        error.get()?.let { throw it }
        return box.get()
    }

    private fun waitUntil(limitMs: Long = 5_000, predicate: () -> Boolean): Boolean {
        val start = android.os.SystemClock.elapsedRealtime()
        while (android.os.SystemClock.elapsedRealtime() - start < limitMs) {
            if (predicate()) return true
            Thread.sleep(50)
        }
        return predicate()
    }

    companion object {
        @Volatile
        var journeyTouchedThisProcess: Boolean = false
    }
}
