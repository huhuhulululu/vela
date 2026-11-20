package vela.app.pip

import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import vela.app.MainActivity
import vela.app.infra.Settings
import vela.app.stage.LanePlayback
import vela.app.stage.StageIntent
import vela.core.base.lane.LaneID
import vela.core.base.model.FeedSource
import vela.core.lifecycle.PhoneScenePhase
import vela.core.lifecycle.PiPPolicy

/**
 * 模拟器画中画：自动进入或 3 秒回退全停；只放行焦点路；比例夹紧；退出恢复。
 * 读取播放器与舞台状态在应用线程；等待用带超时的条件等待。不用反射。
 */
@RunWith(AndroidJUnit4::class)
class PipEmulatorTest {
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
    fun testHomeWithoutAutoEnterStopsAllWithinThreeSeconds() {
        val activity = launchReady(autoEnter = false)
        val stage = activity.stage
        val name = stage.state.value.currentUsername
        assertTrue("没有房间", name != null)
        UiDevice.getInstance(instrumentation).pressHome()
        assertTrue(
            "没有进后台",
            waitUntil { activity.wiring.phase == PhoneScenePhase.BACKGROUND },
        )
        assertTrue(
            "3 秒内应全停",
            waitUntil(4_000) {
                !stage.playbackGateOpen &&
                    stage.lifecycleEpoch.allowedLane == null &&
                    activity.session.lane.value == null
            },
        )
        assertFalse(activity.isInPictureInPictureMode)
    }

    @Test(timeout = 180_000)
    fun testHomeWithAutoEnterEitherEntersPipOrStopsAll() {
        val activity = launchReady(autoEnter = true)
        val stage = activity.stage
        assertTrue(stage.state.value.currentUsername != null)
        val supported = activity.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
        UiDevice.getInstance(instrumentation).pressHome()
        assertTrue(waitUntil { activity.wiring.phase == PhoneScenePhase.BACKGROUND })
        if (!supported) {
            assertTrue(
                waitUntil(4_000) { !stage.playbackGateOpen && stage.lifecycleEpoch.allowedLane == null },
            )
            return
        }
        val entered = waitUntil(4_000) {
            activity.isInPictureInPictureMode ||
                activity.wiring.pipCoordinator?.pictureInPictureState == PiPPolicy.State.ACTIVE
        }
        if (entered) {
            onMain(activity) {
                assertTrue(stage.focusLocked || stage.laneCountLocked || stage.pictureInPicture)
                assertEquals(
                    stage.focusLane.role,
                    stage.lifecycleEpoch.allowedLane,
                )
            }
            // 画中画里只有焦点路：副路播放器应已释放。
            if (stage.laneCount >= 2) {
                assertTrue(
                    waitUntil(3_000) {
                        activity.session.secondaryLaneForTest == null ||
                            stage.secondaryFace?.visible == false
                    },
                )
            }
        } else {
            assertTrue(
                "既没进画中画也没全停",
                waitUntil(1_000) {
                    !stage.playbackGateOpen && stage.lifecycleEpoch.allowedLane == null
                },
            )
        }
    }

    @Test(timeout = 120_000)
    fun testAspectClampRejectsExtremeRatios() {
        val wide = PiPAspect.clamp(100.0, 1.0)
        assertTrue(wide.toDouble() <= PiPAspect.MAX + 0.01)
        val tall = PiPAspect.clamp(1.0, 100.0)
        assertTrue(tall.toDouble() >= PiPAspect.MIN - 0.01)
    }

    @Test(timeout = 120_000)
    fun testMuteRemoteActionIntentExists() {
        assertEquals("vela.app.pip.TOGGLE_MUTE", ActivityPictureInPicture.ACTION_TOGGLE_MUTE)
    }

    private fun launchReady(autoEnter: Boolean, dual: Boolean = false): MainActivity {
        val upstream = upstream()
        val target = instrumentation.targetContext
        target.getSharedPreferences(Settings.PREFS_NAME, android.content.Context.MODE_PRIVATE)
            .edit()
            .clear()
            .putBoolean(Settings.KEY_AGE, true)
            .putBoolean(Settings.KEY_HINT, true)
            .putBoolean(Settings.KEY_MUTED, true)
            .putBoolean(Settings.KEY_PIP, autoEnter)
            .putBoolean(Settings.KEY_DUAL, dual)
            .putString(Settings.KEY_SOURCE, FeedSource.popular.name)
            .commit()
        val intent = Intent(target, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val opened = ActivityScenario.launch<MainActivity>(intent)
        scenario = opened
        val holder = arrayOfNulls<MainActivity>(1)
        opened.onActivity { holder[0] = it }
        val activity = holder[0]!!
        assertTrue(
            "目录没有装上",
            waitUntil(20_000) {
                val state = activity.stage.state.value
                state.rooms.isNotEmpty() && state.currentUsername != null
            },
        )
        assertTrue(
            "焦点路没有出画",
            waitUntil(25_000) {
                val lane = activity.session.lane.value
                lane != null && lane.firstFrameElapsedMs >= 0L && !lane.failed &&
                    activity.stage.state.value.lane.playback == LanePlayback.Playing
            },
        )
        if (dual) {
            onMain(activity) { activity.stage.dispatch(StageIntent.SetDual(true)) }
            assertTrue(waitUntil { activity.stage.laneCount >= 2 })
        }
        return activity
    }

    private fun upstream(): String {
        val raw = InstrumentationRegistry.getArguments().getString("upstream").orEmpty()
            .ifEmpty { vela.app.BuildConfig.VELA_UPSTREAM }
        if (raw.isBlank()) fail("没有伪上游地址")
        return raw
    }

    private fun onMain(activity: MainActivity, block: () -> Unit) {
        val latch = java.util.concurrent.CountDownLatch(1)
        activity.runOnUiThread {
            try {
                block()
            } finally {
                latch.countDown()
            }
        }
        assertTrue(latch.await(5, java.util.concurrent.TimeUnit.SECONDS))
    }

    private fun waitUntil(limitMs: Long = 8_000, predicate: () -> Boolean): Boolean {
        val start = SystemClock.elapsedRealtime()
        while (SystemClock.elapsedRealtime() - start < limitMs) {
            if (predicate()) return true
            Thread.sleep(50)
        }
        return false
    }
}
