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
        runCatching { UiDevice.getInstance(E2eCSupport.instrumentation).wakeUp() }
        scenario?.close()
        scenario = null
    }

    /**
     * S-C1：按 HOME 后 3 秒内进入画中画，否则全停；
     * 画中画里只有焦点路（非焦点播放器已释放）；比例不裁切；锁焦点与路数；退出后恢复进入前路数。
     */
    @Test(timeout = 360_000)
    fun test01HomeEntersPipFocusOnlyUncroppedAndRestoresLanes() {
        val upstream = E2eCSupport.upstream()
        E2eCSupport.resetLocalData()
        E2eCSupport.seedPrefs(autoEnterPip = true, dual = true, muted = true)
        val activity = launchPortrait()
        val stage = activity.stage
        val device = UiDevice.getInstance(E2eCSupport.instrumentation)

        E2eCSupport.confirmAgeAndLoadPopular(stage)
        if (!stage.settings.dualWindow) E2eCSupport.enableDualWindow(stage)
        E2eCSupport.waitBothFirstFrames(activity, stage)

        val lanesBefore = stage.state.value.laneCount
        assertTrue("进入画中画前应是双窗", lanesBefore >= 2)
        assertTrue(E2eCSupport.secondaryLane(activity.session) != null)
        val focusBefore = stage.focusLane
        LaneLog.clear()

        val supported = E2eCSupport.supportsPictureInPicture(activity)
        val homePressedAt = android.os.SystemClock.elapsedRealtime()
        device.pressHome()

        if (!supported) {
            E2eCSupport.assertWaitUntil(activity, "不支持画中画时应全停", 4_000) {
                activity.wiring.phase == PhoneScenePhase.BACKGROUND &&
                    !stage.playbackGateOpen &&
                    stage.lifecycleEpoch.allowedLane == null &&
                    activity.session.lane.value == null &&
                    E2eCSupport.secondaryLane(activity.session) == null &&
                    activity.wiring.pipCoordinator?.pictureInPictureState == PiPPolicy.State.NONE
            }
            return
        }

        // 统一从按 HOME 起计 3 秒预算：必须进入后台并激活画中画，不得通过 !entered 静默跳过。
        E2eCSupport.assertWaitUntil(
            activity = activity,
            message = "按 HOME 后 3 秒内应进入画中画",
            limitMs = (3_000L - (android.os.SystemClock.elapsedRealtime() - homePressedAt)).coerceAtLeast(100L),
        ) {
            activity.wiring.phase == PhoneScenePhase.BACKGROUND &&
                activity.isInPictureInPictureMode &&
                activity.wiring.pipCoordinator?.pictureInPictureState == PiPPolicy.State.ACTIVE
        }

        E2eCSupport.onMain(activity) {
            assertTrue("进入画中画必须锁焦点 | ${E2eCSupport.diagnosePip(activity)}", stage.focusLocked)
            assertTrue("进入画中画必须锁路数 | ${E2eCSupport.diagnosePip(activity)}", stage.laneCountLocked)
            assertTrue("进入画中画必须置位 pictureInPicture | ${E2eCSupport.diagnosePip(activity)}", stage.pictureInPicture)
            assertEquals(
                "画中画放行路应等于进入前焦点 | ${E2eCSupport.diagnosePip(activity)}",
                focusBefore.role,
                stage.lifecycleEpoch.allowedLane,
            )
        }

        // 只有焦点路：非焦点播放器实例必须真正释放（不得仅靠 visible == false 通过），焦点路仍在播放。
        E2eCSupport.assertWaitUntil(activity, "画中画里非焦点播放器必须已释放", 3_000) {
            when (focusBefore) {
                LaneID.PRIMARY -> E2eCSupport.secondaryLane(activity.session) == null
                LaneID.SECONDARY -> activity.session.lane.value == null
                LaneID.TERTIARY -> false
            }
        }
        E2eCSupport.assertWaitUntil(activity, "焦点路播放器应保持存活且放行", 3_000) {
            val focusPlayer = when (focusBefore) {
                LaneID.PRIMARY -> activity.session.lane.value
                LaneID.SECONDARY -> E2eCSupport.secondaryLane(activity.session)
                LaneID.TERTIARY -> null
            }
            stage.lifecycleEpoch.allowedLane == focusBefore.role &&
                focusPlayer != null &&
                !focusPlayer.failed &&
                focusPlayer.firstFrameElapsedMs >= 0L
        }

        // starting/active 期间不能切焦点、不能改路数。
        E2eCSupport.onMain(stage) { stage.dispatch(StageIntent.SetFocus(LaneID.SECONDARY)) }
        assertEquals(focusBefore, stage.focusLane)
        E2eCSupport.onMain(stage) { stage.dispatch(StageIntent.SetLaneCount(1)) }
        assertEquals(lanesBefore, stage.state.value.laneCount)

        // 只统计确认进入画中画且副路已释放之后的媒体请求窗口。
        hit(upstream, "/marker/pip-c1-begin")

        // 仅接受画中画期间焦点路最新的真实 display-check 几何日志，不以 PiPAspect.clamp 兜底。
        E2eCSupport.assertWaitUntil(
            activity = activity,
            message = "画中画期间焦点路最后一条 display-check 应为 gravity=fit 且 uncropped=true",
            limitMs = 8_000,
            extra = { "log=${LaneLog.snapshot().filter { it.contains("display-check") }.takeLast(6)}" },
        ) {
            E2eCSupport.latestPipDisplayUncropped(focusBefore.label)
        }

        // 画中画中持续播放 2.5 秒，确认焦点路继续拉取媒体且非焦点路始终保持释放。
        Thread.sleep(2_500)
        assertTrue(
            "画中画播放窗口结束时非焦点播放器仍应为 null | ${E2eCSupport.diagnosePip(activity)}",
            when (focusBefore) {
                LaneID.PRIMARY -> E2eCSupport.secondaryLane(activity.session) == null
                LaneID.SECONDARY -> activity.session.lane.value == null
                LaneID.TERTIARY -> false
            },
        )
        hit(upstream, "/marker/pip-c1-end")

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
            val homePressedAt = android.os.SystemClock.elapsedRealtime()
            device.pressHome()
            E2eCSupport.assertWaitUntil(
                activity = activity,
                message = "静音播放按 HOME 后 3 秒内应进入画中画",
                limitMs = (3_000L - (android.os.SystemClock.elapsedRealtime() - homePressedAt)).coerceAtLeast(100L),
            ) {
                activity.wiring.phase == PhoneScenePhase.BACKGROUND &&
                    activity.isInPictureInPictureMode &&
                    activity.wiring.pipCoordinator?.pictureInPictureState == PiPPolicy.State.ACTIVE
            }
            val pipMutedDump = E2eCSupport.dumpsysAudioFocus()
            assertFalse(
                "静音画中画焦点栈不应有本包 | ${E2eCSupport.diagnosePip(activity)}\n${pipMutedDump.takeLast(2_000)}",
                E2eCSupport.packageInAudioFocusStack(pipMutedDump),
            )
        }

        // 取消静音：请求 GAIN_TRANSIENT（仅核对当前音频焦点栈本包条目）。
        E2eCSupport.onMain(stage) { stage.dispatch(StageIntent.ToggleMute) }
        E2eCSupport.assertWaitUntil(activity, "取消静音未生效", 8_000) { !stage.state.value.muted }
        E2eCSupport.assertWaitUntil(activity, "取消静音应拿到焦点", 8_000) {
            activity.session.audioController().holdingFocus
        }
        E2eCSupport.assertWaitUntil(
            activity = activity,
            message = "取消静音后焦点栈本包条目应为精确 GAIN_TRANSIENT",
            limitMs = 5_000,
            extra = { E2eCSupport.audioFocusStackLines(E2eCSupport.dumpsysAudioFocus()).joinToString("\n") },
        ) {
            E2eCSupport.hasGainTransient(E2eCSupport.dumpsysAudioFocus())
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
     * S-C3：关掉「自动进入」后回桌面即全停（零线程、零请求）；
     * 非销毁方式关闭画中画后（!isFinishing && !isDestroyed）满足 S-A5 后台标准（5 秒后无播放线程、30 秒零请求）。
     */
    @Test(timeout = 360_000)
    fun test03AutoEnterOffStopsAndClosingPipIsQuiet() {
        val upstream = E2eCSupport.upstream()
        E2eCSupport.resetLocalData()
        E2eCSupport.seedPrefs(autoEnterPip = false, dual = true, muted = true)
        val activity = launchPortrait()
        val stage = activity.stage
        val device = UiDevice.getInstance(E2eCSupport.instrumentation)

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
                E2eCSupport.secondaryLane(activity.session) == null &&
                activity.wiring.pipCoordinator?.pictureInPictureState == PiPPolicy.State.NONE
        }
        Thread.sleep(5_000)
        assertTrue(
            "关自动进入回桌面 5 秒后仍有播放线程 ${E2eCSupport.exoPlaybackThreads()} | ${E2eCSupport.diagnosePip(activity)}",
            E2eCSupport.exoPlaybackThreads().isEmpty(),
        )
        hit(upstream, "/marker/pip-c3-off-begin")
        Thread.sleep(5_000)
        assertTrue(
            "关自动进入静默窗结束仍有播放线程 ${E2eCSupport.exoPlaybackThreads()} | ${E2eCSupport.diagnosePip(activity)}",
            E2eCSupport.exoPlaybackThreads().isEmpty(),
        )
        hit(upstream, "/marker/pip-c3-off-end")

        // 回到前台，打开自动进入，再进画中画后通过非销毁方式关闭，核对 S-A5。
        val reopen = Intent(E2eCSupport.instrumentation.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        E2eCSupport.instrumentation.targetContext.startActivity(reopen)
        E2eCSupport.assertWaitUntil(activity, "关自动进入后未回到前台", 8_000) {
            activity.wiring.phase == PhoneScenePhase.ACTIVE
        }
        E2eCSupport.onMain(stage) { stage.dispatch(StageIntent.SetAutoEnterPip(true)) }
        assertTrue(stage.settings.autoEnterPip)
        E2eCSupport.assertWaitUntil(activity, "回前台应重新起播", 25_000) {
            activity.session.lane.value?.let { it.firstFrameElapsedMs >= 0L && !it.failed } == true &&
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

        val homePressedAt = android.os.SystemClock.elapsedRealtime()
        device.pressHome()
        E2eCSupport.assertWaitUntil(
            activity = activity,
            message = "开启自动进入后 3 秒内应进入画中画",
            limitMs = (3_000L - (android.os.SystemClock.elapsedRealtime() - homePressedAt)).coerceAtLeast(100L),
        ) {
            activity.wiring.phase == PhoneScenePhase.BACKGROUND &&
                activity.isInPictureInPictureMode &&
                activity.wiring.pipCoordinator?.pictureInPictureState == PiPPolicy.State.ACTIVE
        }

        // 绝不调用 activity.finish()：走真实关闭小窗/灭屏触发非销毁 onStop，真正验证 onStop 全停路径。
        val closeWay = E2eCSupport.dismissPictureInPictureWithoutFinishing(activity, device)
        E2eCSupport.assertWaitUntil(
            activity = activity,
            message = "非销毁关闭画中画后应全停 way=$closeWay",
            limitMs = 5_000,
        ) {
            activity.lifecycle.currentState == androidx.lifecycle.Lifecycle.State.CREATED &&
                !activity.isFinishing &&
                !activity.isDestroyed &&
                !stage.playbackGateOpen &&
                stage.lifecycleEpoch.allowedLane == null &&
                activity.session.lane.value == null &&
                E2eCSupport.secondaryLane(activity.session) == null &&
                !stage.pictureInPicture &&
                activity.wiring.pipCoordinator?.pictureInPictureState == PiPPolicy.State.NONE
        }

        // S-A5：5 秒后无播放线程且 Activity 未销毁；随后 30 秒请求窗口由脚本按 marker 统计为零。
        Thread.sleep(5_000)
        assertFalse("本段不得靠 finish 销毁 Activity 绕过 onStop | ${E2eCSupport.diagnosePip(activity)}", activity.isFinishing)
        assertFalse("本段 Activity 不应已销毁 | ${E2eCSupport.diagnosePip(activity)}", activity.isDestroyed)
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
