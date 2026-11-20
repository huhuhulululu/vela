package vela.app.e2e.b

import android.content.pm.ActivityInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiSelector
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import vela.app.infra.Settings
import vela.app.stage.StageIntent
import vela.app.stage.StageRules
import vela.app.stage.LanePlayback
import vela.core.base.lane.LaneID
import vela.core.base.lane.RoomTarget
import vela.core.hls.LaneQuality
import vela.core.hls.PhoneThermalState

/**
 * 里程碑 B：双窗 S-B1、S-B2。
 * 两路不同房、焦点路出声、双窗记住、热降档与 60 秒回升、副路资源降级、双路同时出画。
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class MilestoneBTest {
    private var scenario: androidx.test.core.app.ActivityScenario<vela.app.MainActivity>? = null

    @After
    fun tearDown() {
        scenario?.close()
        scenario = null
    }

    @Test(timeout = 360_000)
    fun test01DualDistinctRoomsFocusAudioAndRemembered() {
        val upstream = E2eBSupport.upstream()
        E2eBSupport.resetLocalData()
        val activity = launchPortrait()
        val stage = activity.stage
        val device = UiDevice.getInstance(E2eBSupport.instrumentation)

        hit(upstream, "/marker/dual-begin")
        E2eBSupport.confirmAgeAndLoadPopular(stage)
        assertFalse(
            activity.getSharedPreferences(Settings.PREFS_NAME, android.content.Context.MODE_PRIVATE)
                .getBoolean(Settings.KEY_DUAL, false),
        )
        E2eBSupport.enableDualWindow(stage)
        assertTrue(stage.settings.dualWindow)
        E2eBSupport.waitBothFirstFrames(activity, stage)

        val primary = stage.feed.currentUsername
        val secondary = stage.secondaryCursor?.username
        assertNotEquals(primary?.lowercase(), secondary?.lowercase())

        E2eBSupport.onMain(stage) { stage.dispatch(StageIntent.ToggleMute) }
        assertTrue(E2eBSupport.waitUntil(8_000) { !stage.state.value.muted })
        val primaryLane = activity.session.lane.value ?: error("无主路")
        val secondaryLane = E2eBSupport.secondaryLane(activity.session) ?: error("无副路")
        assertTrue(
            "焦点路应开着音轨 focus=${stage.focusLane}",
            E2eBSupport.waitUntil(5_000) {
                val focus = activity.session.lane.value
                val other = E2eBSupport.secondaryLane(activity.session)
                when (stage.focusLane) {
                    LaneID.PRIMARY -> {
                        focus != null && E2eBSupport.audioEnabled(focus) > 0 &&
                            (other == null || E2eBSupport.audioEnabled(other) == 0)
                    }
                    LaneID.SECONDARY -> {
                        other != null && E2eBSupport.audioEnabled(other) > 0 &&
                            E2eBSupport.audioEnabled(focus!!) == 0
                    }
                    else -> false
                }
            },
        )

        val width = device.displayWidth
        val height = device.displayHeight
        device.swipe(width / 2, height * 4 / 5, width / 2, height / 5, 30)
        assertTrue(
            E2eBSupport.waitUntil(6_000) {
                stage.focusLane == LaneID.SECONDARY || stage.secondaryCursor?.username != secondary
            },
        )
        E2eBSupport.onMain(stage) { stage.dispatch(StageIntent.SetFocus(LaneID.SECONDARY)) }
        assertEquals(LaneID.SECONDARY, stage.focusLane)
        assertTrue(
            E2eBSupport.waitUntil(5_000) {
                val other = E2eBSupport.secondaryLane(activity.session)
                other != null && E2eBSupport.audioEnabled(other) > 0 &&
                    E2eBSupport.audioEnabled(primaryLane) == 0
            },
        )

        val secondaryFace = stage.state.value.secondary ?: error("没有副路状态")
        val secondaryTarget = RoomTarget(
            laneID = LaneID.SECONDARY,
            username = secondaryFace.username ?: error("副路无房间"),
            generation = secondaryFace.generation,
        )
        E2eBSupport.onMain(stage) { stage.dispatch(StageIntent.TogglePause(secondaryTarget)) }
        assertTrue(stage.state.value.secondary?.manuallyPaused == true)
        assertFalse(stage.state.value.lane.manuallyPaused)

        E2eBSupport.onMain(stage) { stage.dispatch(StageIntent.SetLaneCount(1)) }
        assertTrue(E2eBSupport.waitUntil(5_000) { stage.state.value.laneCount == 1 })
        assertNull(E2eBSupport.secondaryLane(activity.session))

        E2eBSupport.onMain(stage) { stage.dispatch(StageIntent.SetLaneCount(2)) }
        assertTrue(E2eBSupport.waitUntil(8_000) { stage.state.value.laneCount >= 2 })
        assertTrue(stage.state.value.lanesDistinct())
        assertTrue(stage.settings.dualWindow)

        dualRememberedThisProcess = true
        hit(upstream, "/marker/dual-end")
    }

    @Test(timeout = 420_000)
    fun test02ThermalDualRenderAndSecondaryDegrade() {
        val upstream = E2eBSupport.upstream()
        val activity = launchPortrait()
        val stage = activity.stage
        E2eBSupport.confirmAgeAndLoadPopular(stage)
        if (!stage.settings.dualWindow) E2eBSupport.enableDualWindow(stage)
        E2eBSupport.waitBothFirstFrames(activity, stage)

        hit(upstream, "/marker/thermal-begin")
        // 可控时钟：拨快 Instant，不必在慢模拟器上真等 60 秒墙钟。
        val t0 = Instant.parse("2020-06-01T00:00:00Z")
        E2eBSupport.injectThermal(
            stage,
            PhoneThermalState.SERIOUS,
            scheduleRecovery = false,
            now = t0,
        )
        assertTrue(
            "热注入后未进入 serious",
            E2eBSupport.waitUntil(5_000) {
                stage.state.value.thermal == PhoneThermalState.SERIOUS &&
                    E2eBSupport.laneQualityLimited(stage)
            },
        )
        val cap = E2eBSupport.laneCapBps(stage, LaneID.PRIMARY)
        assertTrue(cap.isFinite())
        assertTrue(cap >= LaneQuality.minimumBitrate)

        val windowStart = android.os.SystemClock.elapsedRealtime()
        val windowMs = 30_000L
        var samples = 0
        var bothRendering = 0
        var lastPrimary = -1
        var lastSecondary = -1
        while (android.os.SystemClock.elapsedRealtime() - windowStart < windowMs) {
            samples += 1
            // 播放器计数必须在应用线程读（renderedOutputBufferCount / secondaryLane 内部已 runOnMainSync）。
            val primary = E2eBSupport.onMain(activity) { activity.session.lane.value }
            val secondary = E2eBSupport.secondaryLane(activity.session)
            val primaryRendered = primary?.let { E2eBSupport.renderedOutputBufferCount(it) } ?: 0
            val secondaryRendered = secondary?.let { E2eBSupport.renderedOutputBufferCount(it) } ?: 0
            val primaryFailed = primary?.failed == true
            val secondaryFailed = secondary?.failed == true
            val advancing = (primaryRendered > lastPrimary || lastPrimary < 0) &&
                (secondaryRendered > lastSecondary || lastSecondary < 0) &&
                primaryRendered > 0 && secondaryRendered > 0
            if (advancing && !primaryFailed && !secondaryFailed) {
                bothRendering += 1
            }
            lastPrimary = primaryRendered
            lastSecondary = secondaryRendered
            // 采样步进：短轮询凑满约 1 秒，窗口内可提前结束，避免固定 sleep 卡住。
            val stepDeadline = android.os.SystemClock.elapsedRealtime() + 1_000L
            while (android.os.SystemClock.elapsedRealtime() < stepDeadline &&
                android.os.SystemClock.elapsedRealtime() - windowStart < windowMs
            ) {
                Thread.sleep(50)
            }
        }
        assertTrue("采样次数为 0", samples > 0)
        val ratio = bothRendering.toDouble() / samples.toDouble()
        assertTrue(
            "双路同时出画占比 $ratio（$bothRendering/$samples）",
            ratio >= 0.85,
        )

        // 第 59 秒仍限档；第 60 秒回升（断言不放宽，只改时间来源）。
        E2eBSupport.injectThermal(
            stage,
            PhoneThermalState.FAIR,
            scheduleRecovery = false,
            now = t0,
        )
        E2eBSupport.injectThermal(
            stage,
            PhoneThermalState.FAIR,
            scheduleRecovery = false,
            now = t0.plusSeconds(59),
        )
        assertTrue(
            "第 59 秒仍应限档",
            E2eBSupport.waitUntil(5_000) { E2eBSupport.laneQualityLimited(stage) },
        )
        E2eBSupport.injectThermal(
            stage,
            PhoneThermalState.FAIR,
            scheduleRecovery = false,
            now = t0.plusSeconds(60),
        )
        assertTrue(
            "满 60 秒冷却后应回升",
            E2eBSupport.waitUntil(5_000) { !E2eBSupport.laneQualityLimited(stage) },
        )

        E2eBSupport.degradeSecondary(stage)
        assertTrue(
            "副路未降级",
            E2eBSupport.waitUntil(5_000) {
                stage.state.value.secondary?.playback == LanePlayback.Degraded &&
                    stage.state.value.secondary?.playbackNote == StageRules.DUAL_UNAVAILABLE
            },
        )
        assertEquals(LaneID.PRIMARY, stage.focusLane)
        assertEquals(primaryRoom(stage), stage.feed.currentUsername)

        hit(upstream, "/marker/thermal-end")
    }

    @Test(timeout = 120_000)
    fun test03RememberedDualAfterStop() {
        assertTrue("应先跑 test01", dualRememberedThisProcess)
        E2eBSupport.resetLocalData()
        val prefs = E2eBSupport.instrumentation.targetContext
            .getSharedPreferences(Settings.PREFS_NAME, android.content.Context.MODE_PRIVATE)
        prefs.edit().putBoolean(Settings.KEY_DUAL, true).putBoolean(Settings.KEY_AGE, true).commit()
        val activity = launchPortrait()
        val stage = activity.stage
        assertTrue(stage.settings.dualWindow)
        assertTrue(E2eBSupport.waitUntil(5_000) { stage.state.value.laneCount >= 2 })
        E2eBSupport.confirmAgeAndLoadPopular(stage)
        assertTrue(stage.state.value.lanesDistinct())
    }

    private fun launchPortrait(): vela.app.MainActivity {
        val upstream = E2eBSupport.upstream()
        vela.app.infra.Endpoints.overrideRoot(upstream)
        val launched = androidx.test.core.app.ActivityScenario.launch(vela.app.MainActivity::class.java)
        scenario = launched
        val holder = arrayOfNulls<vela.app.MainActivity>(1)
        launched.onActivity {
            it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            holder[0] = it
        }
        return holder[0] ?: error("Activity 没起来")
    }

    private fun primaryRoom(stage: vela.app.stage.StageViewModel): String? = stage.feed.currentUsername

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

    companion object {
        @Volatile
        var dualRememberedThisProcess: Boolean = false
    }
}