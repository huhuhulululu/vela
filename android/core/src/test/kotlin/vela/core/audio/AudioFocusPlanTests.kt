package vela.core.audio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import vela.core.base.lane.PlaybackLaneRole
import vela.core.hls.AudioPolicy
import vela.core.lifecycle.PipAudioOutput

/** 裁决 2、裁决 8，以及画中画音频输出怎么收成焦点动作。 */
class AudioFocusPlanTests {
    private val lanes = listOf(PlaybackLaneRole.PRIMARY, PlaybackLaneRole.SECONDARY)

    @Test
    fun testMissingStoredMuteDoesNotRequestFocus() {
        assertTrue(AudioPolicy.initialMuted(null))
        val missing = AudioFocusPlan.forStoredMute(null, PlaybackLaneRole.PRIMARY, lanes)
        assertEquals(listOf(AudioFocusStep.MuteAllLanes), missing.steps)
        assertFalse(missing.requestsFocus())
        assertFalse(missing.abandonsFocus())
        assertFalse(missing.writesSettings)
        assertNull(missing.audibleLane)
        assertNull(missing.requestedGain)
        assertFalse(missing.attemptsAudible)

        val storedMuted = AudioFocusPlan.forStoredMute(true, PlaybackLaneRole.PRIMARY, lanes)
        assertEquals(missing.steps, storedMuted.steps)
        assertFalse(storedMuted.requestsFocus())
        assertFalse(storedMuted.writesSettings)
    }

    @Test
    fun testStoredUnmuteRequestsTransientWithoutRewritingSettings() {
        val audible = AudioFocusPlan.forStoredMute(false, PlaybackLaneRole.PRIMARY, lanes)
        assertEquals(FocusGain.TRANSIENT, audible.requestedGain)
        assertTrue(audible.attemptsAudible)
        assertTrue(audible.requestsFocus())
        assertFalse(audible.writesSettings)
        assertEquals(PlaybackLaneRole.PRIMARY, audible.audibleLane)
        assertEquals(listOf(PlaybackLaneRole.SECONDARY), audible.detachAudio)
        assertFalse(audible.steps.any { it is AudioFocusStep.Request && it.gain != FocusGain.TRANSIENT })
    }

    @Test
    fun testFocusChangesMuteAtRuntimeAndNeverResume() {
        for (change in listOf(
            PlatformFocusChange.LOSS,
            PlatformFocusChange.LOSS_TRANSIENT,
            PlatformFocusChange.LOSS_TRANSIENT_CAN_DUCK,
        )) {
            val loss = AudioFocusPlan.plan(
                intent = AudioIntent.FocusChanged(change),
                focus = PlaybackLaneRole.PRIMARY,
                currentlyAudible = PlaybackLaneRole.PRIMARY,
                lanes = lanes,
            )
            assertEquals(
                listOf(AudioFocusStep.MuteAllLanes, AudioFocusStep.Abandon),
                loss.steps,
                "$change",
            )
            assertFalse(loss.writesSettings, "$change")
            assertFalse(loss.resumesAudible, "$change")
            assertFalse(loss.requestsFocus(), "$change")
            assertNull(loss.audibleLane, "$change")
            assertFalse(loss.pausesPlayback, "$change")
        }
        val gain = AudioFocusPlan.plan(
            intent = AudioIntent.FocusChanged(PlatformFocusChange.GAIN),
            focus = PlaybackLaneRole.PRIMARY,
            currentlyAudible = null,
            lanes = lanes,
        )
        assertTrue(gain.preservesAudibleLane)
        assertTrue(gain.steps.isEmpty())
        assertFalse(gain.resumesAudible)
        assertFalse(unmutes(gain.steps))
        assertFalse(gain.writesSettings)
        assertFalse(gain.requestsFocus())

        val lossInPip = AudioFocusPlan.plan(
            intent = AudioIntent.FocusChanged(PlatformFocusChange.LOSS),
            focus = PlaybackLaneRole.PRIMARY,
            currentlyAudible = PlaybackLaneRole.PRIMARY,
            lanes = lanes,
            pictureInPictureActive = true,
        )
        assertTrue(lossInPip.keepsPlayback)
        assertTrue(lossInPip.abandonsFocus())
        assertFalse(lossInPip.writesSettings)
        assertFalse(lossInPip.resumesAudible)
    }

    @Test
    fun testPictureInPictureOutputNeverHoldsFocusWhileMuted() {
        val mutedPip = PipAudioOutput(holdsFocus = false, mixWithOthers = true, releaseNotifyingOthers = false)
        val resting = AudioFocusPlan.fromPip(
            output = mutedPip,
            holdingFocus = false,
            focus = PlaybackLaneRole.PRIMARY,
            currentlyAudible = null,
            lanes = lanes,
        )
        assertFalse(resting.requestsFocus())
        assertFalse(resting.abandonsFocus())
        assertTrue(resting.keepsPlayback)
        assertFalse(resting.writesSettings)

        val wasAudible = AudioFocusPlan.fromPip(
            output = mutedPip,
            holdingFocus = true,
            focus = PlaybackLaneRole.PRIMARY,
            currentlyAudible = PlaybackLaneRole.PRIMARY,
            lanes = lanes,
        )
        assertFalse(wasAudible.requestsFocus())
        assertTrue(wasAudible.abandonsFocus())
        assertTrue(wasAudible.keepsPlayback)
        assertNull(wasAudible.audibleLane)
        assertFalse(wasAudible.writesSettings)
        assertEquals(
            listOf(AudioFocusStep.MuteAllLanes, AudioFocusStep.Abandon),
            wasAudible.steps,
        )

        val audiblePip = PipAudioOutput(holdsFocus = true, mixWithOthers = false, releaseNotifyingOthers = false)
        val taking = AudioFocusPlan.fromPip(
            output = audiblePip,
            holdingFocus = false,
            focus = PlaybackLaneRole.PRIMARY,
            currentlyAudible = null,
            lanes = lanes,
        )
        assertEquals(FocusGain.TRANSIENT, taking.requestedGain)
        assertTrue(taking.requestsFocus())
        assertFalse(taking.abandonsFocus())
        assertFalse(taking.writesSettings)
        assertTrue(taking.attemptsAudible)

        val already = AudioFocusPlan.fromPip(
            output = audiblePip,
            holdingFocus = true,
            focus = PlaybackLaneRole.PRIMARY,
            currentlyAudible = PlaybackLaneRole.PRIMARY,
            lanes = lanes,
        )
        assertFalse(already.requestsFocus())
        assertFalse(already.abandonsFocus())
        assertTrue(already.preservesAudibleLane)

        val background = PipAudioOutput(holdsFocus = false, mixWithOthers = true, releaseNotifyingOthers = true)
        val released = AudioFocusPlan.fromPip(
            output = background,
            holdingFocus = false,
            focus = PlaybackLaneRole.PRIMARY,
            currentlyAudible = null,
            lanes = lanes,
        )
        assertFalse(released.requestsFocus())
        assertTrue(released.abandonsFocus())
        assertFalse(released.keepsPlayback)
        assertFalse(released.writesSettings)
    }

    @Test
    fun testTransitionsNeverSoundTwoLanesAtOnce() {
        val intents = listOf(
            AudioIntent.Unmute(PlaybackLaneRole.PRIMARY),
            AudioIntent.Unmute(PlaybackLaneRole.SECONDARY),
            AudioIntent.Deactivate(DeactivationReason.MUTE),
            AudioIntent.Deactivate(DeactivationReason.BACKGROUND),
            AudioIntent.Deactivate(DeactivationReason.LAST_LANE_STOPPED),
            AudioIntent.InterruptionBegan(wasSuspended = true),
            AudioIntent.InterruptionBegan(wasSuspended = false),
            AudioIntent.InterruptionEnded,
            AudioIntent.Route(RouteReason.OLD_DEVICE_UNAVAILABLE),
            AudioIntent.Route(RouteReason.OTHER),
            AudioIntent.FocusChanged(PlatformFocusChange.GAIN),
            AudioIntent.FocusChanged(PlatformFocusChange.LOSS),
            AudioIntent.FocusChanged(PlatformFocusChange.LOSS_TRANSIENT),
            AudioIntent.FocusChanged(PlatformFocusChange.LOSS_TRANSIENT_CAN_DUCK),
        )
        for (focus in PlaybackLaneRole.entries) {
            for (audible in listOf(null, PlaybackLaneRole.PRIMARY, PlaybackLaneRole.SECONDARY)) {
                for (pip in listOf(false, true)) {
                    for (intent in intents) {
                        val planned = AudioFocusPlan.plan(intent, focus, audible, lanes, pip)
                        val peak = AudioFocusPlan.peakAudible(planned.steps, audible)
                        assertTrue(peak <= 1, "peak=$peak intent=$intent focus=$focus audible=$audible pip=$pip steps=${planned.steps}")
                        assertFalse(planned.resumesAudible, "$intent")
                        if (planned.requestsFocus()) {
                            assertEquals(FocusGain.TRANSIENT, planned.requestedGain, "$intent")
                            assertTrue(intent is AudioIntent.Unmute && intent.lane == focus, "$intent")
                        } else {
                            assertNull(planned.requestedGain, "$intent")
                        }
                        val unmuteAt = planned.steps.indexOfFirst { it is AudioFocusStep.UnmuteLane }
                        if (unmuteAt >= 0) {
                            val requestAt = planned.steps.indexOfFirst { it is AudioFocusStep.Request }
                            assertTrue(requestAt in 0 until unmuteAt, "$intent $planned")
                        }
                    }
                }
            }
        }
    }

    @Test
    fun testRemuteDuringPictureInPictureAbandonsAndKeepsPlayback() {
        val remute = AudioFocusPlan.plan(
            intent = AudioIntent.Deactivate(DeactivationReason.MUTE),
            focus = PlaybackLaneRole.PRIMARY,
            currentlyAudible = PlaybackLaneRole.PRIMARY,
            lanes = lanes,
            pictureInPictureActive = true,
        )
        assertEquals(
            listOf(AudioFocusStep.MuteAllLanes, AudioFocusStep.Abandon),
            remute.steps,
        )
        assertTrue(remute.keepsPlayback)
        assertTrue(remute.writesSettings)
        assertFalse(remute.requestsFocus())
        assertFalse(remute.pausesPlayback)
        assertFalse(remute.resumesAudible)
    }

    private fun unmutes(steps: List<AudioFocusStep>): Boolean =
        steps.any { it is AudioFocusStep.UnmuteLane }
}
