package vela.core.audio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import vela.core.base.lane.PlaybackLaneRole

/**
 * 对应 Tests/VelaCoreTests/AudioTransitionTests.swift。
 * 方法名逐条保留。断言改成音频焦点：不检查 AVAudioSession 的类别、模式和混音选项。
 * 静音不请求焦点；取消静音只请求 GAIN_TRANSIENT；失败、丢焦点、拔耳机都不自动恢复出声。
 */
class AudioTransitionTests {
    private fun plan(
        intent: AudioIntent,
        audible: PlaybackLaneRole? = PlaybackLaneRole.SECONDARY,
        lanes: List<PlaybackLaneRole> = listOf(PlaybackLaneRole.PRIMARY, PlaybackLaneRole.SECONDARY),
        focus: PlaybackLaneRole = PlaybackLaneRole.PRIMARY,
        pictureInPictureActive: Boolean = false,
    ): AudioFocusPlan = AudioFocusPlan.plan(
        intent = intent,
        focus = focus,
        currentlyAudible = audible,
        lanes = lanes,
        pictureInPictureActive = pictureInPictureActive,
    )

    private fun unmutes(steps: List<AudioFocusStep>): Boolean =
        steps.any { it is AudioFocusStep.UnmuteLane }

    /**
     * 出声转换的顺序：先静音旧路，再请求短暂焦点，授予之后才取消静音。
     * 请求的是 GAIN_TRANSIENT，不是永久 GAIN。成功则原样返回。
     */
    @Test
    fun testUnmuteMutesOldLaneBeforeCategoryActivationAndUnmute() {
        val planned = plan(AudioIntent.Unmute(PlaybackLaneRole.PRIMARY), audible = PlaybackLaneRole.SECONDARY)
        assertEquals(
            listOf(
                AudioFocusStep.MuteLane(PlaybackLaneRole.SECONDARY),
                AudioFocusStep.Request(FocusGain.TRANSIENT),
                AudioFocusStep.UnmuteLane(PlaybackLaneRole.PRIMARY),
            ),
            planned.steps,
        )
        assertEquals(PlaybackLaneRole.PRIMARY, planned.audibleLane)
        assertEquals(listOf(PlaybackLaneRole.SECONDARY), planned.detachAudio)
        assertTrue(planned.writesSettings)
        assertNull(planned.notice)
        assertFalse(planned.pausesPlayback)
        assertTrue(planned.attemptsAudible)
        assertEquals(FocusGain.TRANSIENT, planned.requestedGain)
        assertFalse(planned.resumesAudible)
        assertTrue(AudioFocusPlan.peakAudible(planned.steps, PlaybackLaneRole.SECONDARY) <= 1)
        val muteAt = planned.steps.indexOf(AudioFocusStep.MuteLane(PlaybackLaneRole.SECONDARY))
        val requestAt = planned.steps.indexOf(AudioFocusStep.Request(FocusGain.TRANSIENT))
        val unmuteAt = planned.steps.indexOf(AudioFocusStep.UnmuteLane(PlaybackLaneRole.PRIMARY))
        assertTrue(muteAt < requestAt && requestAt < unmuteAt)
        assertEquals(planned, AudioFocusPlan.finished(planned, focusGranted = true))
    }

    /**
     * 焦点没授予：全部保持静音，不写设置，提示暂时无法出声。
     * Swift 把类别失败和激活失败分成三次；安卓只有一次请求结果，三次都是未授予。
     */
    @Test
    fun testSessionFailureStaysMutedWithoutWritingSettings() {
        val planned = plan(AudioIntent.Unmute(PlaybackLaneRole.PRIMARY), audible = PlaybackLaneRole.SECONDARY)
        for ((categoryOk, activationOk) in listOf(false to true, true to false, false to false)) {
            val granted = categoryOk && activationOk
            val failed = AudioFocusPlan.finished(planned, focusGranted = granted)
            assertNull(failed.audibleLane, "category $categoryOk activation $activationOk")
            assertFalse(failed.writesSettings)
            assertEquals("暂时无法出声", failed.notice)
            assertEquals(AudioFocusPlan.FAILURE_NOTICE, failed.notice)
            assertFalse(unmutes(failed.steps))
            assertFalse(failed.requestsFocus())
            assertTrue(failed.steps.contains(AudioFocusStep.MuteLane(PlaybackLaneRole.SECONDARY)))
            assertEquals(listOf(PlaybackLaneRole.SECONDARY), failed.detachAudio)
            assertFalse(failed.pausesPlayback)
            assertFalse(failed.attemptsAudible)
            assertFalse(failed.resumesAudible)
            assertNull(failed.requestedGain)
        }
    }

    /** 挂起引起的打断忽略。真正丢掉焦点只改运行时静音，不写设置，也不自动恢复。 */
    @Test
    fun testIgnoresInterruptionBeganWhenWasSuspended() {
        val ignored = plan(AudioIntent.InterruptionBegan(wasSuspended = true), audible = PlaybackLaneRole.PRIMARY)
        assertTrue(ignored.ignored)
        assertTrue(ignored.preservesAudibleLane)
        assertTrue(ignored.steps.isEmpty())
        assertFalse(ignored.writesSettings)
        assertFalse(ignored.requestsFocus())
        assertFalse(ignored.abandonsFocus())

        val began = plan(AudioIntent.InterruptionBegan(wasSuspended = false), audible = PlaybackLaneRole.PRIMARY)
        assertFalse(began.ignored)
        assertEquals(
            listOf(AudioFocusStep.MuteAllLanes, AudioFocusStep.Abandon),
            began.steps,
        )
        assertNull(began.audibleLane)
        assertFalse(began.preservesAudibleLane)
        assertFalse(began.writesSettings)
        assertFalse(began.pausesPlayback)
        assertNull(began.notice)
        assertFalse(began.resumesAudible)
        assertFalse(began.requestsFocus())
        assertTrue(began.abandonsFocus())
    }

    /** 打断结束（以及之后的焦点归还）不自动出声。 */
    @Test
    fun testInterruptionEndedDoesNotResumeAudio() {
        val ended = plan(
            AudioIntent.InterruptionEnded,
            audible = PlaybackLaneRole.PRIMARY,
            lanes = listOf(PlaybackLaneRole.PRIMARY),
        )
        assertFalse(ended.resumesAudible)
        assertTrue(ended.preservesAudibleLane)
        assertTrue(ended.steps.isEmpty())
        assertFalse(unmutes(ended.steps))
        assertFalse(ended.writesSettings)
        assertNull(ended.notice)
        assertFalse(ended.pausesPlayback)
        assertFalse(ended.requestsFocus())
        assertFalse(ended.abandonsFocus())
    }

    /** 拔耳机转为静音并放弃焦点，不暂停，不自动恢复。无关路由不动。 */
    @Test
    fun testHeadphoneUnplugMutesWithoutPausing() {
        val unplug = plan(AudioIntent.Route(RouteReason.OLD_DEVICE_UNAVAILABLE), audible = PlaybackLaneRole.PRIMARY)
        assertNull(unplug.audibleLane)
        assertFalse(unplug.pausesPlayback)
        assertFalse(unplug.preservesAudibleLane)
        assertEquals(
            listOf(AudioFocusStep.MuteAllLanes, AudioFocusStep.Abandon),
            unplug.steps,
        )
        assertTrue(unplug.writesSettings)
        assertFalse(unplug.requestsFocus())
        assertTrue(unplug.abandonsFocus())
        assertFalse(unplug.resumesAudible)
        assertFalse(unplug.keepsPlayback)
        assertEquals(unplug, AudioFocusPlan.finished(unplug, focusGranted = false))
        val other = plan(
            AudioIntent.Route(RouteReason.OTHER),
            audible = PlaybackLaneRole.PRIMARY,
            lanes = listOf(PlaybackLaneRole.PRIMARY),
        )
        assertTrue(other.preservesAudibleLane)
        assertTrue(other.steps.isEmpty())
        assertFalse(other.pausesPlayback)
        assertFalse(other.writesSettings)
        assertFalse(other.requestsFocus())
    }

    /**
     * 画中画里拔耳机：各路转静音，焦点仍要放弃（静音不得继续占着焦点），播放不释放、不暂停。
     * iOS 这里不 setActive(false)。安卓放弃的是短暂焦点，不是播放器。
     */
    @Test
    fun testHeadphoneUnplugKeepsSessionActiveDuringPictureInPicture() {
        val unplug = AudioFocusPlan.plan(
            intent = AudioIntent.Route(RouteReason.OLD_DEVICE_UNAVAILABLE),
            focus = PlaybackLaneRole.PRIMARY,
            currentlyAudible = PlaybackLaneRole.PRIMARY,
            lanes = listOf(PlaybackLaneRole.PRIMARY, PlaybackLaneRole.SECONDARY),
            pictureInPictureActive = true,
        )
        assertEquals(
            listOf(AudioFocusStep.MuteAllLanes, AudioFocusStep.Abandon),
            unplug.steps,
        )
        assertTrue(unplug.keepsPlayback)
        assertTrue(unplug.abandonsFocus())
        assertFalse(unplug.requestsFocus())
        assertNull(unplug.audibleLane)
        assertFalse(unplug.pausesPlayback)
        assertTrue(unplug.writesSettings)
        assertFalse(unplug.resumesAudible)
        assertEquals(listOf(PlaybackLaneRole.SECONDARY), unplug.detachAudio)

        val withoutPiP = plan(AudioIntent.Route(RouteReason.OLD_DEVICE_UNAVAILABLE), audible = PlaybackLaneRole.PRIMARY)
        assertFalse(withoutPiP.keepsPlayback)
        assertEquals(AudioFocusStep.Abandon, withoutPiP.steps.last())
        assertTrue(withoutPiP.abandonsFocus())
    }

    /** 再静音、进后台、最后一路停播都放弃焦点，让对方恢复。非焦点路不取消静音。 */
    @Test
    fun testDeactivationNotifiesOthersAndNonFocusStaysDetached() {
        for (reason in DeactivationReason.entries) {
            val planned = plan(AudioIntent.Deactivate(reason), audible = PlaybackLaneRole.PRIMARY)
            assertEquals(
                listOf(AudioFocusStep.MuteAllLanes, AudioFocusStep.Abandon),
                planned.steps,
                "$reason",
            )
            assertNull(planned.audibleLane)
            assertFalse(planned.pausesPlayback)
            assertEquals(reason == DeactivationReason.MUTE, planned.writesSettings, "$reason")
            assertFalse(planned.requestsFocus(), "$reason")
            assertTrue(planned.abandonsFocus(), "$reason")
            assertFalse(planned.keepsPlayback, "$reason")
            assertFalse(planned.resumesAudible, "$reason")
        }
        val nonFocus = plan(
            AudioIntent.Unmute(PlaybackLaneRole.SECONDARY),
            audible = PlaybackLaneRole.PRIMARY,
            focus = PlaybackLaneRole.PRIMARY,
        )
        assertEquals(listOf(PlaybackLaneRole.SECONDARY), nonFocus.detachAudio)
        assertEquals(PlaybackLaneRole.PRIMARY, nonFocus.audibleLane)
        assertFalse(nonFocus.steps.any { it == AudioFocusStep.UnmuteLane(PlaybackLaneRole.SECONDARY) })
        assertFalse(nonFocus.attemptsAudible)
        assertFalse(nonFocus.requestsFocus())
        assertFalse(nonFocus.writesSettings)
    }
}
