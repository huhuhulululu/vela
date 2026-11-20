package vela.app.e2e.b

import android.content.pm.ActivityInfo
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
import java.time.Instant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import vela.app.MainActivity
import vela.app.infra.Endpoints
import vela.app.infra.Settings
import vela.app.lane.LanePlayer
import vela.app.lane.PlaybackLane
import vela.app.stage.LanePlayback
import vela.app.stage.StageIntent
import vela.app.stage.StageRules
import vela.app.stage.degradeLane
import vela.app.stage.laneCap
import vela.app.stage.noteLaneDisplay
import vela.app.stage.noteThermal
import vela.app.stage.outcomeFor
import vela.app.ui.laneTarget
import vela.core.base.lane.LaneID
import vela.core.base.model.FeedSource
import vela.core.hls.FailureCode
import vela.core.hls.LaneQuality
import vela.core.hls.PhoneThermalState
import vela.core.hls.PlaybackFailureClassifier

/**
 * 里程碑 B 双窗端到端：覆盖 S-B1、S-B2。
 * 两路不同房、副路操作只动副路、只有焦点路开音轨、两路最高档、双窗开关记住；
 * 注入热状态第 59 秒不回升、第 60 秒回升；副路解码资源降级；两路同出画面 ≥ 窗口 90%。
 * 不访问真实站点。请求窗口由宿主机按 /marker/ 统计。
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class MilestoneBTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private var scenario: ActivityScenario<MainActivity>? = null

    @After
    fun tearDown() {
        scenario?.close()
        scenario = null
    }

    @Test(timeout = 300_000)
    fun test01DualDistinctFocusAndRemembered() {
        val upstream = upstream()
        resetLocalData()
        val activity = launch()
        val device = UiDevice.getInstance(instrumentation)
        val stage = activity.stage

        confirmAgeAndLoadPopular(device, activity)
        hit(upstream, "/marker/dual-begin")

        openDual(device, activity)
        assertTrue(
            "没有开到双窗 laneCount=${stage.state.value.laneCount}",
            waitUntil(8_000) { stage.state.value.laneCount >= 2 && stage.state.value.dualWindow },
        )
        assertTrue(
            "双窗开关没有写入设置",
            activity.getSharedPreferences(Settings.PREFS_NAME, android.content.Context.MODE_PRIVATE)
                .getBoolean(Settings.KEY_DUAL, false),
        )

        assertTrue(
            "两路没有各自选到房间 primary=${stage.state.value.lane.username} " +
                "secondary=${stage.state.value.secondary?.username}",
            waitUntil(12_000) {
                val primary = stage.state.value.lane.username
                val secondary = stage.state.value.secondary?.username
                !primary.isNullOrBlank() && !secondary.isNullOrBlank() &&
                    !primary.equals(secondary, ignoreCase = true)
            },
        )
        val primaryName = stage.state.value.lane.username!!
        val secondaryName = stage.state.value.secondary!!.username!!
        assertNotEquals(primaryName.lowercase(), secondaryName.lowercase())

        assertTrue(
            "主路没有首帧 note=${stage.state.value.lane.playbackNote}",
            waitUntil(20_000) {
                val lane = stage.primaryLane ?: activity.session.lane.value
                lane != null && lane.firstFrameElapsedMs in 0..8_000 && !lane.failed
            },
        )
        assertTrue(
            "副路没有首帧 note=${stage.state.value.secondary?.playbackNote}",
            waitUntil(20_000) {
                val lane = stage.secondaryLane
                lane != null && lane.firstFrameElapsedMs in 0..12_000 && !lane.failed
            },
        )

        // 默认静音：两路音轨都关。取消静音后只有焦点路开音轨。
        assertTrue(stage.state.value.muted)
        val primary = stage.primaryLane ?: activity.session.lane.value ?: error("没有主路播放器")
        val secondary = stage.secondaryLane ?: error("没有副路播放器")
        assertTrue(
            "静音时主路音轨还开着",
            waitUntil(5_000) { onMain(activity) { audioEnabled(primary) == 0 } },
        )
        assertTrue(
            "静音时副路音轨还开着",
            waitUntil(5_000) { onMain(activity) { audioEnabled(secondary) == 0 } },
        )
        onMain(activity) { stage.dispatch(StageIntent.ToggleMute) }
        assertTrue(waitUntil { !stage.state.value.muted })
        assertTrue(
            "取消静音后焦点主路没有开音轨",
            waitUntil(5_000) { onMain(activity) { audioEnabled(primary) > 0 } },
        )
        assertTrue(
            "取消静音后副路（非焦点）仍开着音轨",
            waitUntil(5_000) { onMain(activity) { audioEnabled(secondary) == 0 } },
        )
        onMain(activity) { stage.dispatch(StageIntent.SetFocus(LaneID.SECONDARY)) }
        assertTrue(waitUntil { stage.state.value.focusLane == LaneID.SECONDARY })
        assertTrue(
            "切到副路焦点后副路没有开音轨",
            waitUntil(5_000) { onMain(activity) { audioEnabled(secondary) > 0 } },
        )
        assertTrue(
            "切到副路焦点后主路仍开着音轨",
            waitUntil(5_000) { onMain(activity) { audioEnabled(primary) == 0 } },
        )
        onMain(activity) { stage.dispatch(StageIntent.ToggleMute) }
        assertTrue(waitUntil { stage.state.value.muted })

        // 副路操作只动副路：暂停副路，主路房间与暂停态不变。
        onMain(activity) {
            stage.dispatch(StageIntent.SetFocus(LaneID.SECONDARY))
            stage.dispatch(StageIntent.TogglePause(stage.state.value.laneTarget(LaneID.SECONDARY)!!))
        }
        assertTrue(waitUntil { stage.state.value.secondary?.manuallyPaused == true })
        assertFalse(stage.state.value.lane.manuallyPaused)
        assertEquals(primaryName, stage.state.value.lane.username)
        assertEquals(secondaryName, stage.state.value.secondary?.username)
        onMain(activity) {
            stage.dispatch(StageIntent.TogglePause(stage.state.value.laneTarget(LaneID.SECONDARY)!!))
        }
        assertTrue(waitUntil { stage.state.value.secondary?.manuallyPaused != true })

        // 屏蔽副路：主路不变，副路换到下一间，仍不与主路重复。
        onMain(activity) {
            stage.dispatch(StageIntent.SetFocus(LaneID.SECONDARY))
            stage.dispatch(StageIntent.BlockCurrent(stage.state.value.laneTarget(LaneID.SECONDARY)))
        }
        assertTrue(
            "屏蔽副路后没有换房或与主路撞了 primary=${stage.state.value.lane.username} " +
                "secondary=${stage.state.value.secondary?.username}",
            waitUntil(8_000) {
                val next = stage.state.value.secondary?.username
                next != null &&
                    next != secondaryName &&
                    !next.equals(stage.state.value.lane.username, ignoreCase = true) &&
                    stage.state.value.lane.username == primaryName
            },
        )

        // 两路未降档时都是最高档。
        onMain(activity) {
            stage.noteLaneDisplay(LaneID.PRIMARY, height = 221.0, scale = 3.0)
            stage.noteLaneDisplay(LaneID.SECONDARY, height = 221.0, scale = 3.0)
        }
        assertEquals(Double.POSITIVE_INFINITY, stage.laneCap(LaneID.PRIMARY), 0.0)
        assertEquals(Double.POSITIVE_INFINITY, stage.laneCap(LaneID.SECONDARY), 0.0)

        // 只剩一间时：焦点路保留，另一路显示「没有更多房间」。
        onMain(activity) {
            stage.dispatch(StageIntent.SetFocus(LaneID.PRIMARY))
            stage.dispatch(StageIntent.SetLaneCount(1))
        }
        assertTrue(waitUntil { stage.state.value.laneCount == 1 })
        // 再开双窗，用设置里记住的 2 路验证杀进程前写入已落盘。
        onMain(activity) { stage.dispatch(StageIntent.SetLaneCount(2)) }
        assertTrue(waitUntil { stage.state.value.laneCount >= 2 })
        assertTrue(
            activity.getSharedPreferences(Settings.PREFS_NAME, android.content.Context.MODE_PRIVATE)
                .getBoolean(Settings.KEY_DUAL, false),
        )

        hit(upstream, "/marker/dual-end")
    }

    @Test(timeout = 240_000)
    fun test02ThermalDegradeAndSimultaneousFrames() {
        val upstream = upstream()
        // 沿用上一测留下的年龄与双窗偏好，但清掉收藏以免干扰目录。
        val activity = launch()
        val device = UiDevice.getInstance(instrumentation)
        val stage = activity.stage

        if (!stage.state.value.ageConfirmed) {
            confirmAgeAndLoadPopular(device, activity)
        } else {
            ensurePopular(device, activity)
        }
        if (stage.state.value.laneCount < 2) {
            openDual(device, activity)
        }
        assertTrue(
            waitUntil(12_000) {
                stage.state.value.laneCount >= 2 &&
                    !stage.state.value.lane.username.isNullOrBlank() &&
                    !stage.state.value.secondary?.username.isNullOrBlank()
            },
        )
        assertTrue(
            waitUntil(20_000) {
                (stage.primaryLane ?: activity.session.lane.value)?.let {
                    it.firstFrameElapsedMs >= 0 && !it.failed
                } == true
            },
        )
        assertTrue(
            waitUntil(20_000) {
                stage.secondaryLane?.let { it.firstFrameElapsedMs >= 0 && !it.failed } == true
            },
        )

        hit(upstream, "/marker/thermal-begin")
        onMain(activity) {
            stage.noteLaneDisplay(LaneID.PRIMARY, height = 221.0, scale = 3.0)
            stage.noteLaneDisplay(LaneID.SECONDARY, height = 221.0, scale = 3.0)
        }
        val t0 = Instant.parse("2020-06-01T00:00:00Z")
        onMain(activity) { stage.noteThermal(PhoneThermalState.SERIOUS, t0, scheduleRecovery = false) }
        val expected = LaneQuality().also { it.observe(PhoneThermalState.SERIOUS, t0) }
            .peakBitrate(221.0, 3.0)
        assertTrue(expected >= LaneQuality.minimumBitrate)
        assertEquals(expected, stage.laneCap(LaneID.PRIMARY), 0.0)
        assertEquals(expected, stage.laneCap(LaneID.SECONDARY), 0.0)
        assertEquals(PhoneThermalState.SERIOUS, stage.state.value.thermal)

        // 第 59 秒仍不回升。
        onMain(activity) {
            stage.noteThermal(PhoneThermalState.FAIR, t0, scheduleRecovery = false)
            stage.noteThermal(PhoneThermalState.FAIR, t0.plusSeconds(59), scheduleRecovery = false)
        }
        assertEquals(expected, stage.laneCap(LaneID.PRIMARY), 0.0)
        assertEquals(expected, stage.laneCap(LaneID.SECONDARY), 0.0)
        assertTrue(stage.laneQuality.isLimited)

        // 第 60 秒回升。
        onMain(activity) {
            stage.noteThermal(PhoneThermalState.NOMINAL, t0.plusSeconds(60), scheduleRecovery = false)
        }
        assertEquals(Double.POSITIVE_INFINITY, stage.laneCap(LaneID.PRIMARY), 0.0)
        assertEquals(Double.POSITIVE_INFINITY, stage.laneCap(LaneID.SECONDARY), 0.0)
        assertFalse(stage.laneQuality.isLimited)
        hit(upstream, "/marker/thermal-end")

        // 副路解码资源不足 → 降级；损坏码流仍按房间失败。
        hit(upstream, "/marker/degrade-begin")
        val resourceChain = listOf(
            FailureCode(
                PlaybackFailureClassifier.media3ErrorDomain,
                PlaybackFailureClassifier.decoderInitFailedCode,
            ),
        )
        assertEquals(
            PlaybackFailureClassifier.Outcome.RESOURCE_DEGRADE,
            stage.outcomeFor(LaneID.SECONDARY, resourceChain),
        )
        assertEquals(
            PlaybackFailureClassifier.Outcome.ROOM_RETRY,
            stage.outcomeFor(LaneID.PRIMARY, resourceChain),
        )
        val corrupt = listOf(
            FailureCode(
                PlaybackFailureClassifier.media3ErrorDomain,
                PlaybackFailureClassifier.decodingFailedCode,
            ),
        )
        assertEquals(
            PlaybackFailureClassifier.Outcome.ROOM_RETRY,
            stage.outcomeFor(LaneID.SECONDARY, corrupt),
        )
        val keptPrimary = stage.state.value.lane.username
        onMain(activity) { stage.degradeLane(LaneID.SECONDARY) }
        assertEquals(LanePlayback.Degraded, stage.state.value.secondary?.playback)
        assertEquals(StageRules.DUAL_UNAVAILABLE, stage.state.value.secondary?.playbackNote)
        assertEquals(keptPrimary, stage.state.value.lane.username)
        assertTrue(
            "降级副路后主路也被弄坏了 playback=${stage.state.value.lane.playback}",
            stage.state.value.lane.playback != LanePlayback.Failed &&
                stage.state.value.lane.playback != LanePlayback.Degraded,
        )
        hit(upstream, "/marker/degrade-end")

        // 重新开双窗并等两路出画，采样同出画面占比。
        onMain(activity) {
            stage.dispatch(StageIntent.SetLaneCount(1))
        }
        assertTrue(waitUntil { stage.state.value.laneCount == 1 })
        onMain(activity) { stage.dispatch(StageIntent.SetLaneCount(2)) }
        assertTrue(
            waitUntil(20_000) {
                stage.state.value.laneCount >= 2 &&
                    (stage.primaryLane ?: activity.session.lane.value)?.let {
                        it.firstFrameElapsedMs >= 0 && !it.failed
                    } == true &&
                    stage.secondaryLane?.let { it.firstFrameElapsedMs >= 0 && !it.failed } == true
            },
        )

        hit(upstream, "/marker/both-begin")
        val windowMs = 20_000L
        val stepMs = 500L
        var ticks = 0
        var both = 0
        val deadline = android.os.SystemClock.elapsedRealtime() + windowMs
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            val primaryLane = stage.primaryLane ?: activity.session.lane.value
            val secondaryLane = stage.secondaryLane
            val primaryOk = primaryLane != null &&
                primaryLane.firstFrameElapsedMs >= 0 &&
                !primaryLane.failed &&
                onMain(activity) { primaryLane.renderedOutputBufferCount() } > 0
            val secondaryOk = secondaryLane != null &&
                secondaryLane.firstFrameElapsedMs >= 0 &&
                !secondaryLane.failed &&
                onMain(activity) { secondaryLane.renderedOutputBufferCount() } > 0
            ticks += 1
            if (primaryOk && secondaryOk) both += 1
            Thread.sleep(stepMs)
        }
        hit(upstream, "/marker/both-end")
        assertTrue("采样次数为 0", ticks > 0)
        val ratio = both.toDouble() / ticks.toDouble()
        assertTrue(
            "两路同出画面占比 $ratio（$both/$ticks）低于 0.90",
            ratio >= 0.90,
        )
    }

    @Test(timeout = 60_000)
    fun test03DualPreferenceSurvivesRelaunch() {
        val prefs = instrumentation.targetContext
            .getSharedPreferences(Settings.PREFS_NAME, android.content.Context.MODE_PRIVATE)
        assertTrue("上一测没有把双窗写进设置", prefs.getBoolean(Settings.KEY_DUAL, false))
        val activity = launch()
        val stage = activity.stage
        assertTrue(stage.state.value.ageConfirmed)
        // 记住的双窗：启动时 laneCount≥2，或设置 dualWindow 为真后列表到达即补副路。
        assertTrue(
            "杀进程后双窗偏好丢了 dual=${stage.settings.dualWindow} " +
                "laneCount=${stage.state.value.laneCount} remembered=${stage.state.value.rememberedLaneCount}",
            stage.settings.dualWindow || stage.state.value.rememberedLaneCount >= 2,
        )
        if (stage.state.value.rooms.isNotEmpty()) {
            assertTrue(
                waitUntil(15_000) {
                    stage.state.value.laneCount >= 2 &&
                        !stage.state.value.lane.username.isNullOrBlank()
                },
            )
            val secondary = stage.state.value.secondary?.username
            if (secondary != null) {
                assertFalse(
                    stage.state.value.lane.username.equals(secondary, ignoreCase = true),
                )
            }
        }
    }

    private fun confirmAgeAndLoadPopular(device: UiDevice, activity: MainActivity) {
        val stage = activity.stage
        assertFalse(stage.state.value.ageConfirmed)
        val enter = device.findObject(UiSelector().text("已满 18 岁"))
        assertTrue("没有年龄确认按钮\n${hierarchy(device)}", enter.waitForExists(8_000))
        enter.click()
        assertTrue(waitUntil { stage.state.value.ageConfirmed })
        onMain(activity) { stage.dispatch(StageIntent.DismissHint) }
        ensurePopular(device, activity)
    }

    private fun ensurePopular(device: UiDevice, activity: MainActivity) {
        val stage = activity.stage
        revealChrome(device, activity)
        val popular = device.findObject(UiSelector().description("切换到「热门」"))
        if (popular.waitForExists(3_000)) {
            popular.click()
        } else {
            onMain(activity) { stage.dispatch(StageIntent.SelectSource(FeedSource.popular)) }
        }
        assertTrue(
            "没有停在热门 source=${stage.state.value.source} rooms=${stage.state.value.rooms.size}",
            waitUntil(20_000) {
                stage.state.value.source == FeedSource.popular &&
                    stage.state.value.rooms.size >= 2 &&
                    stage.state.value.currentUsername != null
            },
        )
    }

    private fun openDual(device: UiDevice, activity: MainActivity) {
        val stage = activity.stage
        revealChrome(device, activity)
        val picker = device.findObject(UiSelector().descriptionContains("路数"))
        if (picker.waitForExists(2_000)) {
            picker.click()
            val two = device.findObject(UiSelector().textMatches(".*2 路.*"))
            if (two.waitForExists(2_000)) {
                two.click()
            } else {
                onMain(activity) { stage.dispatch(StageIntent.SetLaneCount(2)) }
            }
        } else {
            onMain(activity) { stage.dispatch(StageIntent.SetLaneCount(2)) }
        }
    }

    private fun launch(): MainActivity {
        val upstream = upstream()
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
        check(Endpoints.siteRoot().trimEnd('/') == upstream) {
            "站点根不是伪上游：${Endpoints.siteRoot()}"
        }
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
        assertTrue(
            "伪上游主机不是回环或模拟器宿主机：$host",
            host == "10.0.2.2" || host == "127.0.0.1" || host.equals("localhost", ignoreCase = true),
        )
        if (trimmed.startsWith("http://")) {
            val permitted = android.security.NetworkSecurityPolicy.getInstance()
                .isCleartextTrafficPermitted(host)
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

    private fun audioEnabled(lane: PlaybackLane): Int {
        val outer = lane.javaClass.getDeclaredField("player")
        outer.isAccessible = true
        val delegate = outer.get(lane) as LanePlayer
        return delegate.audioEnabledCount()
    }

    private fun revealChrome(device: UiDevice, activity: MainActivity) {
        onMain(activity) { activity.stage.dispatch(StageIntent.NoteActivity) }
        assertTrue(waitUntil { activity.stage.state.value.chromeShown })
        device.wait(Until.findObject(By.text("热门")), 2_000)
    }

    private fun hierarchy(device: UiDevice): String = runCatching {
        val file = File(instrumentation.targetContext.cacheDir, "e2e-b-window.xml")
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
}
