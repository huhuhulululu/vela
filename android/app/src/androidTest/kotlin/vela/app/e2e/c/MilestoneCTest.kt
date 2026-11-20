package vela.app.e2e.c

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import vela.app.stage.StageIntent
import vela.core.lifecycle.PhoneScenePhase
import vela.core.lifecycle.PiPPolicy

/**
 * 里程碑 C：画中画 S-C1、S-C2、S-C3。
 * 按 HOME 3 秒内进画中画或全停；画中画里只留焦点路；比例不裁切；
 * 静音不进焦点栈，取消静音 GAIN_TRANSIENT，再静音放弃；
 * 关自动进入则全停；关画中画后满足后台零开销。
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class MilestoneCTest {
    private var scenario: androidx.test.core.app.ActivityScenario<vela.app.MainActivity>? = null

    @After
    fun tearDown() {
        scenario?.close()
        scenario = null
    }

    private fun launch(
        autoEnterPip: Boolean = true,
        dual: Boolean = false,
        muted: Boolean = true,
    ): vela.app.MainActivity {
        val handle = E2eCSupport.launchPortrait(autoEnterPip, dual, muted)
        scenario = handle.scenario
        return handle.activity
    }

    @Test(timeout = 360_000)
    fun test01PipAutoEnterFocusLaneOnlyAndUncropped() {
        val upstream = E2eCSupport.upstream()
        val activity = launch(autoEnterPip = true, dual = true)
        val stage = activity.stage
        val lanesBefore = stage.laneCount
        assertTrue("应开双窗", lanesBefore >= 2)
        assertTrue(stage.state.value.lanesDistinct())
        E2eCSupport.onMain(stage) { stage.dispatch(StageIntent.NoteActivity) }

        E2eCSupport.hit(upstream, "/marker/pip-s1-begin")
        E2eCSupport.pressHome()
        assertTrue(
            "没有进后台",
            E2eCSupport.waitUntil { activity.wiring.phase == PhoneScenePhase.BACKGROUND },
        )

        val supported = E2eCSupport.pipSupported(activity)
        val entered = supported && E2eCSupport.waitEnterPipOrStop(activity, 4_000)

        if (supported && entered) {
            assertTrue(
                "画中画应锁定焦点路",
                E2eCSupport.waitUntil(3_000) {
                    E2eCSupport.onMain(activity) {
                        stage.focusLocked || stage.laneCountLocked || stage.pictureInPicture
                    }
                },
            )
            assertEquals(stage.focusLane.role, stage.lifecycleEpoch.allowedLane)
            assertTrue(
                "副路应已释放",
                E2eCSupport.waitUntil(5_000) {
                    E2eCSupport.secondaryLane(activity.session) == null ||
                        stage.secondaryFace?.visible == false
                },
            )
            val primary = activity.session.lane.value
            assertTrue("焦点路仍在播放", primary != null && !primary.failed)
            val secondary = E2eCSupport.secondaryLane(activity.session)
            if (secondary != null) {
                val rendered = E2eCSupport.renderedOutputBufferCount(secondary)
                assertTrue("非焦点路不应继续出画 rendered=$rendered", rendered == 0)
            }
            assertTrue(
                "应有 uncropped 日志",
                E2eCSupport.waitUntil(8_000) { E2eCSupport.uncroppedLogged() },
            )
            val coordinator = activity.wiring.pipCoordinator
            assertTrue(
                coordinator?.pictureInPictureState == PiPPolicy.State.ACTIVE ||
                    activity.isInPictureInPictureMode,
            )

            E2eCSupport.bringToForeground(activity)
            assertTrue(
                "回前台应恢复路数 lanes=${stage.laneCount}",
                E2eCSupport.waitUntil(15_000) { stage.laneCount >= lanesBefore },
            )
        } else if (supported) {
            assertTrue(
                "3 秒内没进画中画应全停",
                E2eCSupport.waitUntil(1_000) {
                    !stage.playbackGateOpen &&
                        stage.lifecycleEpoch.allowedLane == null &&
                        activity.session.lane.value == null
                },
            )
        } else {
            assertTrue(
                E2eCSupport.waitUntil(4_000) {
                    !stage.playbackGateOpen && stage.lifecycleEpoch.allowedLane == null
                },
            )
        }
        E2eCSupport.hit(upstream, "/marker/pip-s1-end")
    }

    @Test(timeout = 300_000)
    fun test02MutedAudioFocusTransientInPip() {
        val activity = launch(autoEnterPip = true, dual = false)
        val stage = activity.stage
        E2eCSupport.pressHome()
        assertTrue(E2eCSupport.waitUntil { activity.wiring.phase == PhoneScenePhase.BACKGROUND })

        val inPip = E2eCSupport.pipSupported(activity) &&
            E2eCSupport.waitEnterPipOrStop(activity, 4_000)

        if (!inPip) {
            val dumpMuted = E2eCSupport.dumpsysAudio()
            assertFalse(
                "默认静音焦点栈不应有本包",
                E2eCSupport.packageInAudioFocusStack(dumpMuted),
            )
            assertFalse(E2eCSupport.holdingAudioFocus(activity))
            return
        }

        assertTrue(stage.muted)
        val dumpQuiet = E2eCSupport.dumpsysAudio()
        assertFalse(
            "画中画静音时焦点栈不应有本包",
            E2eCSupport.packageInAudioFocusStack(dumpQuiet),
        )
        assertFalse(E2eCSupport.holdingAudioFocus(activity))

        E2eCSupport.onMain(stage) { stage.dispatch(StageIntent.ToggleMute) }
        assertTrue(
            "取消静音",
            E2eCSupport.waitUntil(8_000) { !stage.state.value.muted },
        )
        val focusLane = activity.session.lane.value
        assertTrue(
            "焦点路应开音轨",
            E2eCSupport.waitUntil(5_000) {
                focusLane != null && E2eCSupport.audioEnabled(focusLane) > 0
            },
        )
        assertTrue(
            E2eCSupport.waitUntil(8_000) {
                val dump = E2eCSupport.dumpsysAudio()
                E2eCSupport.packageInAudioFocusStack(dump) &&
                    (E2eCSupport.dumpShowsGainTransient(dump) || E2eCSupport.holdingAudioFocus(activity))
            },
        )
        assertTrue(E2eCSupport.holdingAudioFocus(activity))

        E2eCSupport.onMain(stage) { stage.dispatch(StageIntent.ToggleMute) }
        assertTrue(
            "再静音",
            E2eCSupport.waitUntil(5_000) { stage.state.value.muted },
        )
        assertTrue(
            E2eCSupport.waitUntil(5_000) {
                !E2eCSupport.holdingAudioFocus(activity) &&
                    !E2eCSupport.packageInAudioFocusStack(E2eCSupport.dumpsysAudio())
            },
        )
    }

    @Test(timeout = 300_000)
    fun test03AutoEnterOffAndClosePipBackgroundIdle() {
        val upstream = E2eCSupport.upstream()

        val stopped = launch(autoEnterPip = false, dual = false)
        E2eCSupport.pressHome()
        assertTrue(E2eCSupport.waitUntil { stopped.wiring.phase == PhoneScenePhase.BACKGROUND })
        assertTrue(
            "关掉自动进入后应 3 秒内全停",
            E2eCSupport.waitUntil(4_000) {
                !stopped.stage.playbackGateOpen &&
                    stopped.stage.lifecycleEpoch.allowedLane == null &&
                    stopped.session.lane.value == null
            },
        )
        assertFalse(stopped.isInPictureInPictureMode)
        scenario?.close()
        scenario = null

        val active = launch(autoEnterPip = true, dual = false)
        E2eCSupport.pressHome()
        assertTrue(E2eCSupport.waitUntil { active.wiring.phase == PhoneScenePhase.BACKGROUND })
        val entered = E2eCSupport.pipSupported(active) &&
            E2eCSupport.waitEnterPipOrStop(active, 4_000)
        if (!entered) {
            E2eCSupport.hit(upstream, "/marker/pip-s3-begin")
            E2eCSupport.hit(upstream, "/marker/pip-s3-end")
            return
        }

        E2eCSupport.closePipInBackground(active)
        assertTrue(
            E2eCSupport.waitUntil(8_000) {
                active.wiring.pipCoordinator?.pictureInPictureState == PiPPolicy.State.NONE
            },
        )
        assertTrue(
            E2eCSupport.waitUntil(8_000) {
                !active.stage.playbackGateOpen && active.session.lane.value == null
            },
        )
        assertTrue(
            E2eCSupport.waitUntil(8_000) { E2eCSupport.exoThreads().isEmpty() },
        )

        E2eCSupport.hit(upstream, "/marker/pip-s3-begin")
        val before = E2eCSupport.cpuTicks()
        Thread.sleep(5_000)
        val delta = E2eCSupport.cpuTicks() - before
        E2eCSupport.hit(upstream, "/marker/pip-s3-end")
        assertTrue("关闭画中画后 CPU 增量 $delta", delta <= 30L)
        assertTrue(E2eCSupport.exoThreads().isEmpty())
    }
}