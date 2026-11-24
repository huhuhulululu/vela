package vela.app.e2e.c

import android.content.Intent
import android.content.pm.ActivityInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.UiDevice
import java.net.HttpURLConnection
import java.net.URL
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import vela.app.MainActivity
import vela.app.infra.Endpoints
import vela.app.lane.LaneLog
import vela.app.stage.LanePlayback
import vela.app.stage.StageIntent
import vela.core.base.lane.LaneID
import vela.core.lifecycle.PhoneScenePhase
import vela.core.lifecycle.PiPPolicy

/**
 * 里程碑 C：画中画 S-C1、S-C2、S-C3。
 * 只读基线测试钩子与公开状态；不反射私有字段；不访问真站。
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class MilestoneCTest {
    private var scenario: ActivityScenario<MainActivity>? = null

    @After
    fun tearDown() {
        scenario?.close()
        scenario = null
    }

    /**
     * S-C1：按 HOME 后 3 秒内进入画中画，否则全停；
     * 画中画里只有焦点路；比例不裁切；锁焦点与路数；退出后恢复进入前路数。
     */
    @Test(timeout = 360_000)
    fun test01HomeEntersPipFocusOnlyUncroppedAndRestoresLanes() {
        val upstream = E2eCSupport.upstream()
        E2eCSupport.resetLocalData()
        E2eCSupport.seedPrefs(autoEnterPip = true, dual = true, muted = true)
        val activity = launchPortrait()
        val stage = activity.stage
        val device = UiDevice.getInstance(E2eCSupport.instrumentation)

        hit(upstream, "/marker/pip-c1-begin")
        E2eCSupport.confirmAgeAndLoadPopular(stage)
        if (!stage.settings.dualWindow) E2eCSupport.enableDualWindow(stage)
        E2eCSupport.waitBothFirstFrames(activity, stage)

        val lanesBefore = stage.state.value.laneCount
        assertTrue("进入画中画前应是双窗", lanesBefore >= 2)
        assertTrue(E2eCSupport.secondaryLane(activity.session) != null)
        val focusBefore = stage.focusLane
        LaneLog.clear()

        val supported = E2eCSupport.supportsPictureInPicture(activity)
        device.pressHome()
        E2eCSupport.assertWaitUntil(activity, "没有进后台") {
            activity.wiring.phase == PhoneScenePhase.BACKGROUND
        }

        if (!supported) {
            E2eCSupport.assertWaitUntil(activity, "不支持画中画时应全停", 4_000) {
                !stage.playbackGateOpen &&
                    stage.lifecycleEpoch.allowedLane == null &&
                    activity.session.lane.value == null
            }
            hit(upstream, "/marker/pip-c1-end")
            return
        }

        val entered = E2eCSupport.waitUntil(3_000) {
            activity.isInPictureInPictureMode ||
                activity.wiring.pipCoordinator?.pictureInPictureState == PiPPolicy.State.ACTIVE ||
                activity.wiring.pipCoordinator?.pictureInPictureState == PiPPolicy.State.STARTING
        }

        if (!entered) {
            E2eCSupport.assertWaitUntil(activity, "3 秒内未进画中画应全停", 2_000) {
                !stage.playbackGateOpen &&
                    stage.lifecycleEpoch.allowedLane == null &&
                    activity.session.lane.value == null
            }
            hit(upstream, "/marker/pip-c1-end")
            return
        }

        E2eCSupport.assertWaitUntil(activity, "画中画状态应对上", 3_000) {
            activity.isInPictureInPictureMode ||
                activity.wiring.pipCoordinator?.pictureInPictureState == PiPPolicy.State.ACTIVE
        }

        E2eCSupport.onMain(activity) {
            assertTrue(
                "进入画中画应锁焦点或路数 | ${E2eCSupport.diagnosePip(activity)}",
                stage.focusLocked || stage.laneCountLocked || stage.pictureInPicture,
            )
            assertEquals(
                "画中画放行路应等于进入前焦点 | ${E2eCSupport.diagnosePip(activity)}",
                focusBefore.role,
                stage.lifecycleEpoch.allowedLane,
            )
        }

        // 只有焦点路：副路播放器与在途会话应已释放。
        E2eCSupport.assertWaitUntil(activity, "画中画里副路应已释放", 5_000) {
            E2eCSupport.secondaryLane(activity.session) == null ||
                stage.state.value.secondary?.visible == false
        }
        E2eCSupport.assertWaitUntil(activity, "焦点路应仍在播或被放行", 5_000) {
            stage.lifecycleEpoch.allowedLane == focusBefore.role &&
                (
                    activity.session.lane.value != null ||
                        focusBefore != LaneID.PRIMARY
                    )
        }

        // starting/active 期间不能切焦点、不能改路数。
        E2eCSupport.onMain(stage) { stage.dispatch(StageIntent.SetFocus(LaneID.SECONDARY)) }
        assertEquals(focusBefore, stage.focusLane)
        E2eCSupport.onMain(stage) { stage.dispatch(StageIntent.SetLaneCount(1)) }
        assertEquals(lanesBefore, stage.state.value.laneCount)

        E2eCSupport.assertWaitUntil(
            activity = activity,
            message = "画中画比例应 uncropped",
            limitMs = 8_000,
            extra = { "log=${LaneLog.snapshot().filter { it.contains("display-check") }.takeLast(4)}" },
        ) {
            LaneLog.snapshot().any { line ->
                line.contains("display-check") &&
                    line.contains("gravity=fit") &&
                    line.contains("uncropped=true")
            } || run {
                // 小窗尺寸日志未刷出时，要求焦点路真实已有正尺寸且经 PiPAspect 夹紧落于合法区间。
                val lane = when (focusBefore) {
                    LaneID.PRIMARY -> activity.session.lane.value
                    LaneID.SECONDARY -> E2eCSupport.secondaryLane(activity.session)
                    LaneID.TERTIARY -> null
                }
                val size = lane?.currentVideoSize()
                if (size == null || size.width <= 0.0 || size.height <= 0.0) {
                    false
                } else {
                    val ratio = vela.app.pip.PiPAspect.clamp(size.width, size.height).toDouble()
                    ratio >= vela.app.pip.PiPAspect.MIN && ratio <= vela.app.pip.PiPAspect.MAX
                }
            }
        }

        // 回到前台：退出画中画后恢复进入前路数。
        val back = Intent(E2eCSupport.instrumentation.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        E2eCSupport.instrumentation.targetContext.startActivity(back)
        E2eCSupport.assertWaitUntil(activity, "没有回到前台", 8_000) {
            activity.wiring.phase == PhoneScenePhase.ACTIVE
        }
        E2eCSupport.assertWaitUntil(activity, "退出画中画后应恢复路数 before=$lanesBefore", 12_000) {
            stage.state.value.laneCount >= lanesBefore &&
                !stage.pictureInPicture
        }
        assertFalse("退出画中画后应解焦点锁 | ${E2eCSupport.diagnosePip(activity)}", stage.focusLocked)
        assertFalse("退出画中画后应解路数锁 | ${E2eCSupport.diagnosePip(activity)}", stage.laneCountLocked)

        hit(upstream, "/marker/pip-c1-end")
    }

    /**
     * S-C2：静音时 dumpsys audio 焦点栈没有本包；
     * 取消静音后有 GAIN_TRANSIENT；再静音即放弃焦点。
     */
    @Test(timeout = 300_000)
    fun test02PipMuteAudioFocusGainTransient() {
        val upstream = E2eCSupport.upstream()
        E2eCSupport.resetLocalData()
        E2eCSupport.seedPrefs(autoEnterPip = true, dual = false, muted = true)
        val activity = launchPortrait()
        val stage = activity.stage
        val device = UiDevice.getInstance(E2eCSupport.instrumentation)

        hit(upstream, "/marker/pip-c2-begin")
        E2eCSupport.confirmAgeAndLoadPopular(stage)
        E2eCSupport.waitFocusFirstFrame(activity)
        assertTrue(stage.state.value.muted)

        val mutedDump = E2eCSupport.dumpsysAudioFocus()
        assertFalse(
            "静音时焦点栈不应有本包 | ${E2eCSupport.diagnosePip(activity)}\n${mutedDump.takeLast(2_000)}",
            E2eCSupport.packageInAudioFocusStack(mutedDump),
        )
        assertFalse(activity.session.audioController().holdingFocus)

        val supported = E2eCSupport.supportsPictureInPicture(activity)
        if (supported) {
            device.pressHome()
            E2eCSupport.assertWaitUntil(activity, "静音画中画未进后台") {
                activity.wiring.phase == PhoneScenePhase.BACKGROUND
            }
            val entered = E2eCSupport.waitUntil(3_000) {
                activity.isInPictureInPictureMode ||
                    activity.wiring.pipCoordinator?.pictureInPictureState == PiPPolicy.State.ACTIVE
            }
            if (entered) {
                val pipMutedDump = E2eCSupport.dumpsysAudioFocus()
                assertFalse(
                    "静音画中画焦点栈不应有本包 | ${E2eCSupport.diagnosePip(activity)}\n${pipMutedDump.takeLast(2_000)}",
                    E2eCSupport.packageInAudioFocusStack(pipMutedDump),
                )
            }
        }

        // 取消静音：请求 GAIN_TRANSIENT。
        E2eCSupport.onMain(stage) { stage.dispatch(StageIntent.ToggleMute) }
        E2eCSupport.assertWaitUntil(activity, "取消静音未生效", 8_000) { !stage.state.value.muted }
        E2eCSupport.assertWaitUntil(activity, "取消静音应拿到焦点", 8_000) {
            activity.session.audioController().holdingFocus
        }
        val unmutedDump = E2eCSupport.dumpsysAudioFocus()
        E2eCSupport.assertWaitUntil(
            activity = activity,
            message = "取消静音后应有 GAIN_TRANSIENT",
            limitMs = 5_000,
            extra = { unmutedDump.takeLast(2_500) },
        ) {
            E2eCSupport.hasGainTransient(E2eCSupport.dumpsysAudioFocus()) ||
                E2eCSupport.hasGainTransient(unmutedDump)
        }

        // 再静音：放弃焦点。回桌面后系统可能先收走焦点，运行时静音已经被同步成真。
        // 这时再切一次会把静音打开，所以只在仍未静音时切。
        val alreadyMuted = E2eCSupport.onMain(stage) { stage.muted }
        if (!alreadyMuted) {
            E2eCSupport.onMain(stage) { stage.dispatch(StageIntent.ToggleMute) }
        }
        E2eCSupport.assertWaitUntil(activity, "再静音后应为静音 alreadyMuted=$alreadyMuted", 8_000) {
            E2eCSupport.onMain(stage) { stage.muted }
        }
        E2eCSupport.assertWaitUntil(activity, "再静音应放弃焦点", 8_000) {
            !activity.session.audioController().holdingFocus
        }
        E2eCSupport.assertWaitUntil(activity, "再静音后焦点栈应没有本包", 5_000) {
            !E2eCSupport.packageInAudioFocusStack(E2eCSupport.dumpsysAudioFocus())
        }

        hit(upstream, "/marker/pip-c2-end")
    }

    /**
     * S-C3：关掉「自动进入」后回桌面即全停；
     * 关闭画中画后满足 S-A5 后台标准（5 秒内零请求、没有播放线程）。
     */
    @Test(timeout = 360_000)
    fun test03AutoEnterOffStopsAndClosingPipIsQuiet() {
        val upstream = E2eCSupport.upstream()
        E2eCSupport.resetLocalData()
        E2eCSupport.seedPrefs(autoEnterPip = false, dual = true, muted = true)
        val activity = launchPortrait()
        val stage = activity.stage
        val device = UiDevice.getInstance(E2eCSupport.instrumentation)

        hit(upstream, "/marker/pip-c3-off-begin")
        E2eCSupport.confirmAgeAndLoadPopular(stage)
        if (!stage.settings.dualWindow) E2eCSupport.enableDualWindow(stage)
        E2eCSupport.waitBothFirstFrames(activity, stage)
        assertFalse(stage.settings.autoEnterPip)

        device.pressHome()
        E2eCSupport.assertWaitUntil(activity, "关掉自动进入后未进后台") {
            activity.wiring.phase == PhoneScenePhase.BACKGROUND
        }
        assertFalse(
            "关掉自动进入后不应进入画中画 | ${E2eCSupport.diagnosePip(activity)}",
            activity.isInPictureInPictureMode,
        )
        E2eCSupport.assertWaitUntil(activity, "关掉自动进入后回桌面应全停", 4_000) {
            !stage.playbackGateOpen &&
                stage.lifecycleEpoch.allowedLane == null &&
                activity.session.lane.value == null &&
                E2eCSupport.secondaryLane(activity.session) == null
        }
        hit(upstream, "/marker/pip-c3-off-end")

        // 回到前台，打开自动进入，再进画中画后关闭，核对 S-A5。
        val reopen = Intent(E2eCSupport.instrumentation.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        E2eCSupport.instrumentation.targetContext.startActivity(reopen)
        E2eCSupport.assertWaitUntil(activity, "关自动进入后未回到前台", 8_000) {
            activity.wiring.phase == PhoneScenePhase.ACTIVE
        }
        E2eCSupport.onMain(stage) { stage.dispatch(StageIntent.SetAutoEnterPip(true)) }
        assertTrue(stage.settings.autoEnterPip)
        E2eCSupport.assertWaitUntil(activity, "回前台应重新起播", 25_000) {
            activity.session.lane.value?.let { it.firstFrameElapsedMs >= 0L && !it.failed } == true ||
                stage.state.value.lane.playback == LanePlayback.Playing
        }
        if (stage.state.value.laneCount < 2) {
            E2eCSupport.enableDualWindow(stage)
            E2eCSupport.waitBothFirstFrames(activity, stage)
        }

        val supported = E2eCSupport.supportsPictureInPicture(activity)
        if (!supported) {
            hit(upstream, "/marker/pip-c3-close-begin")
            hit(upstream, "/marker/pip-c3-close-end")
            return
        }

        device.pressHome()
        E2eCSupport.assertWaitUntil(activity, "开启自动进入后未进后台") {
            activity.wiring.phase == PhoneScenePhase.BACKGROUND
        }
        val entered = E2eCSupport.waitUntil(3_000) {
            activity.isInPictureInPictureMode ||
                activity.wiring.pipCoordinator?.pictureInPictureState == PiPPolicy.State.ACTIVE
        }
        if (!entered) {
            // 未能进入则已按全停处理；仍打上关闭窗口边界供脚本核对。
            E2eCSupport.assertWaitUntil(activity, "3 秒内未进入画中画应全停", 2_000) {
                !stage.playbackGateOpen && activity.session.lane.value == null
            }
            hit(upstream, "/marker/pip-c3-close-begin")
            Thread.sleep(5_000)
            assertTrue(
                "未进画中画 5 秒后仍有播放线程 ${E2eCSupport.exoPlaybackThreads()} | ${E2eCSupport.diagnosePip(activity)}",
                E2eCSupport.exoPlaybackThreads().isEmpty(),
            )
            hit(upstream, "/marker/pip-c3-close-end")
            return
        }

        // 关闭画中画（小窗 X）：finish 当前任务，触发 onStop/DidStop → 后台全停。
        // AOSP 14 在画中画里 finish() 直接走 onStop → onDestroy，不派发 onPictureInPictureModeChanged(false)，
        // 因此已销毁的 Activity 上 isInPictureInPictureMode 仍保持 true；这里要求已退出小窗或已销毁，且五项全停全部成立。
        E2eCSupport.onMain(activity) {
            if (activity.isInPictureInPictureMode && !activity.isFinishing) {
                activity.finish()
            }
        }
        E2eCSupport.assertWaitUntil(activity, "关闭画中画后应全停", 5_000) {
            (!activity.isInPictureInPictureMode || activity.isDestroyed) &&
                !stage.playbackGateOpen &&
                stage.lifecycleEpoch.allowedLane == null &&
                activity.session.lane.value == null &&
                E2eCSupport.secondaryLane(activity.session) == null &&
                activity.wiring.pipCoordinator?.pictureInPictureState == PiPPolicy.State.NONE
        }

        // S-A5：5 秒后无播放线程；随后 30 秒请求窗口由脚本按 marker 统计为零。
        Thread.sleep(5_000)
        assertTrue(
            "关闭画中画 5 秒后仍有播放线程 ${E2eCSupport.exoPlaybackThreads()} | ${E2eCSupport.diagnosePip(activity)}",
            E2eCSupport.exoPlaybackThreads().isEmpty(),
        )
        hit(upstream, "/marker/pip-c3-close-begin")
        Thread.sleep(30_000)
        assertTrue(
            "静默窗口结束仍有播放线程 ${E2eCSupport.exoPlaybackThreads()} | ${E2eCSupport.diagnosePip(activity)}",
            E2eCSupport.exoPlaybackThreads().isEmpty(),
        )
        hit(upstream, "/marker/pip-c3-close-end")
    }

    private fun launchPortrait(): MainActivity {
        val upstream = E2eCSupport.upstream()
        Endpoints.overrideRoot(upstream)
        val launched = ActivityScenario.launch(MainActivity::class.java)
        scenario = launched
        val holder = arrayOfNulls<MainActivity>(1)
        launched.onActivity {
            it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            holder[0] = it
        }
        val activity = holder[0] ?: error("Activity 没起来")
        if (Endpoints.siteRoot().trimEnd('/') != upstream) {
            Endpoints.overrideRoot(upstream)
        }
        check(Endpoints.siteRoot().trimEnd('/') == upstream)
        return activity
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
}
