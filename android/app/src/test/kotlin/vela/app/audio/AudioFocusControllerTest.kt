package vela.app.audio

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import vela.core.audio.AudioFocusPlan
import vela.core.audio.AudioIntent
import vela.core.audio.DeactivationReason
import vela.core.base.lane.PlaybackLaneRole
import vela.core.lifecycle.PipAudioOutput

/**
 * 用 Robolectric 的 ShadowAudioManager 看真实的 AudioFocusRequest。
 * 静音不进焦点栈；取消静音是 GAIN_TRANSIENT；再静音放弃；失败和丢焦点都不写设置。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AudioFocusControllerTest {
    private lateinit var context: Context
    private lateinit var audioManager: AudioManager
    private lateinit var sinks: RecordingSinks
    private lateinit var controller: AudioFocusController

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        audioManager = context.getSystemService(AudioManager::class.java)
        shadowOf(audioManager).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        sinks = RecordingSinks()
        controller = AudioFocusController(context, audioManager, sinks)
        controller.focus = PlaybackLaneRole.PRIMARY
        controller.lanes = listOf(PlaybackLaneRole.PRIMARY, PlaybackLaneRole.SECONDARY)
    }

    @Test
    fun testMissingMutedDoesNotRequestFocus() {
        val done = controller.applyStored(null)
        assertFalse(done.requestsFocus())
        assertFalse(done.writesSettings)
        assertNull(shadowOf(audioManager).lastAudioFocusRequest)
        assertNull(shadowOf(audioManager).lastAbandonedAudioFocusRequest)
        assertFalse(controller.holdingFocus)
        assertNull(controller.audibleLane)
        assertTrue(sinks.writes.isEmpty())
        assertEquals(0, sinks.pauses)
        assertTrue(sinks.events.contains("muteAll"))
    }

    @Test
    fun testUnmuteRequestsTransientAndRemuteAbandons() {
        controller.applyStored(null)
        sinks.reset()

        val unmuted = controller.execute(AudioIntent.Unmute(PlaybackLaneRole.PRIMARY))
        val recorded = shadowOf(audioManager).lastAudioFocusRequest
        val request = recorded.audioFocusRequest
        assertEquals(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT, request.focusGain)
        assertEquals(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT, recorded.durationHint)
        assertFalse(request.acceptsDelayedFocusGain())
        assertTrue(request.willPauseWhenDucked())
        assertEquals(AudioAttributes.USAGE_MEDIA, request.audioAttributes.usage)
        assertEquals(AudioAttributes.CONTENT_TYPE_MOVIE, request.audioAttributes.contentType)
        assertTrue(controller.holdingFocus)
        assertEquals(PlaybackLaneRole.PRIMARY, controller.audibleLane)
        assertEquals(PlaybackLaneRole.PRIMARY, unmuted.audibleLane)
        assertEquals(listOf(false), sinks.writes)
        assertTrue(sinks.events.contains("unmute:${PlaybackLaneRole.PRIMARY}"))
        assertEquals(0, sinks.pauses)

        val granted = request
        sinks.reset()
        val muted = controller.execute(AudioIntent.Deactivate(DeactivationReason.MUTE))
        assertFalse(muted.requestsFocus())
        assertTrue(muted.abandonsFocus())
        assertFalse(controller.holdingFocus)
        assertNull(controller.audibleLane)
        assertSame(granted, shadowOf(audioManager).lastAbandonedAudioFocusRequest)
        assertSame(granted, shadowOf(audioManager).lastAudioFocusRequest.audioFocusRequest)
        assertEquals(listOf(true), sinks.writes)
        assertFalse(sinks.events.any { it.startsWith("unmute:") })
        assertEquals(0, sinks.pauses)
    }

    @Test
    fun testSecondUnmuteWhileHoldingDoesNotRequestAgain() {
        controller.execute(AudioIntent.Unmute(PlaybackLaneRole.PRIMARY))
        val first = shadowOf(audioManager).lastAudioFocusRequest.audioFocusRequest
        sinks.reset()
        controller.execute(AudioIntent.Unmute(PlaybackLaneRole.PRIMARY))
        assertSame(first, shadowOf(audioManager).lastAudioFocusRequest.audioFocusRequest)
        assertNull(shadowOf(audioManager).lastAbandonedAudioFocusRequest)
        assertTrue(controller.holdingFocus)
        assertTrue(sinks.events.contains("unmute:${PlaybackLaneRole.PRIMARY}"))
    }

    @Test
    fun testFocusRequestFailureStaysMutedWithoutWritingSettings() {
        controller.applyStored(null)
        assertFalse(controller.holdingFocus)
        sinks.reset()
        shadowOf(audioManager).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_FAILED)

        val failed = controller.execute(AudioIntent.Unmute(PlaybackLaneRole.PRIMARY))
        assertEquals(AudioFocusPlan.FAILURE_NOTICE, failed.notice)
        assertEquals(listOf(AudioFocusPlan.FAILURE_NOTICE), sinks.notices)
        assertTrue(sinks.writes.isEmpty())
        assertNull(failed.audibleLane)
        assertNull(controller.audibleLane)
        assertFalse(controller.holdingFocus)
        assertFalse(failed.writesSettings)
        assertFalse(failed.requestsFocus())
        assertFalse(sinks.events.any { it.startsWith("unmute:") })
        assertEquals(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT, shadowOf(audioManager).lastAudioFocusRequest.durationHint)
        assertEquals(0, sinks.pauses)
    }

    @Test
    fun testFocusLossMutesWithoutWritingAndGainDoesNotResume() {
        controller.execute(AudioIntent.Unmute(PlaybackLaneRole.PRIMARY))
        val listener = shadowOf(audioManager).lastAudioFocusRequest.listener
        sinks.reset()

        listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)
        assertFalse(controller.holdingFocus)
        assertNull(controller.audibleLane)
        assertTrue(sinks.writes.isEmpty())
        assertTrue(sinks.events.contains("muteAll"))
        assertFalse(sinks.events.any { it.startsWith("unmute:") })
        assertEquals(0, sinks.pauses)

        sinks.reset()
        listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        assertNull(controller.audibleLane)
        assertFalse(sinks.events.any { it.startsWith("unmute:") })
        assertTrue(sinks.writes.isEmpty())
        assertFalse(controller.holdingFocus)
        assertEquals(0, sinks.pauses)
    }

    @Test
    fun testPermanentLossAndTransientLossStayMuted() {
        for (change in intArrayOf(AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)) {
            sinks.reset()
            controller.execute(AudioIntent.Unmute(PlaybackLaneRole.PRIMARY))
            val listener = shadowOf(audioManager).lastAudioFocusRequest.listener
            sinks.reset()
            listener.onAudioFocusChange(change)
            assertNull(controller.audibleLane)
            assertFalse(controller.holdingFocus)
            assertTrue(sinks.writes.isEmpty())
            assertEquals(0, sinks.pauses)
            shadowOf(audioManager).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        }
    }

    @Test
    fun testBecomingNoisyMutesAndDoesNotPause() {
        controller.start()
        controller.execute(AudioIntent.Unmute(PlaybackLaneRole.PRIMARY))
        sinks.reset()
        context.sendBroadcast(Intent(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
        shadowOf(Looper.getMainLooper()).idle()

        assertNull(controller.audibleLane)
        assertFalse(controller.holdingFocus)
        assertEquals(listOf(true), sinks.writes)
        assertTrue(sinks.events.contains("muteAll"))
        assertFalse(sinks.events.any { it.startsWith("unmute:") })
        assertEquals(0, sinks.pauses)
        assertNotNullAbandoned()
    }

    @Test
    fun testBecomingNoisyDuringPictureInPictureKeepsPlanPlayback() {
        controller.start()
        controller.execute(AudioIntent.Unmute(PlaybackLaneRole.PRIMARY))
        controller.pictureInPictureActive = true
        sinks.reset()
        context.sendBroadcast(Intent(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
        shadowOf(Looper.getMainLooper()).idle()

        assertFalse(controller.holdingFocus)
        assertNull(controller.audibleLane)
        assertEquals(listOf(true), sinks.writes)
        assertEquals(0, sinks.pauses)
    }

    @Test
    fun testLaneSwitchMutesOldBeforeUnmute() {
        controller.focus = PlaybackLaneRole.SECONDARY
        controller.execute(AudioIntent.Unmute(PlaybackLaneRole.SECONDARY))
        sinks.reset()
        controller.focus = PlaybackLaneRole.PRIMARY
        controller.execute(AudioIntent.Unmute(PlaybackLaneRole.PRIMARY))

        val audible = mutableSetOf(PlaybackLaneRole.SECONDARY)
        var peak = audible.size
        for (event in sinks.events) {
            when {
                event == "muteAll" -> audible.clear()
                event.startsWith("mute:") -> audible.remove(roleOf(event))
                event.startsWith("unmute:") -> audible.add(roleOf(event))
            }
            if (audible.size > peak) peak = audible.size
        }
        assertTrue("peak=$peak events=${sinks.events}", peak <= 1)
        val muteAt = sinks.events.indexOf("mute:${PlaybackLaneRole.SECONDARY}")
        val unmuteAt = sinks.events.indexOf("unmute:${PlaybackLaneRole.PRIMARY}")
        assertTrue(muteAt >= 0 && unmuteAt > muteAt)
        assertFalse(sinks.events.contains("unmute:${PlaybackLaneRole.SECONDARY}"))
    }

    @Test
    fun testMutedPictureInPictureAbandonsWithoutNewRequest() {
        controller.execute(AudioIntent.Unmute(PlaybackLaneRole.PRIMARY))
        val granted = shadowOf(audioManager).lastAudioFocusRequest.audioFocusRequest
        sinks.reset()
        val output = PipAudioOutput(holdsFocus = false, mixWithOthers = true, releaseNotifyingOthers = false)
        val done = controller.applyPip(output)
        assertTrue(done.keepsPlayback)
        assertTrue(done.abandonsFocus())
        assertFalse(done.requestsFocus())
        assertFalse(controller.holdingFocus)
        assertTrue(sinks.writes.isEmpty())
        assertSame(granted, shadowOf(audioManager).lastAbandonedAudioFocusRequest)
        assertEquals(0, sinks.pauses)
    }

    @Test
    fun testAudiblePictureInPictureRequestsTransient() {
        val output = PipAudioOutput(holdsFocus = true, mixWithOthers = false, releaseNotifyingOthers = false)
        val done = controller.applyPip(output)
        assertEquals(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT, shadowOf(audioManager).lastAudioFocusRequest.durationHint)
        assertTrue(done.attemptsAudible)
        assertTrue(controller.holdingFocus)
        assertTrue(sinks.writes.isEmpty())
        assertEquals(PlaybackLaneRole.PRIMARY, controller.audibleLane)
    }

    @Test
    fun testSuspendedInterruptionIsIgnored() {
        controller.execute(AudioIntent.Unmute(PlaybackLaneRole.PRIMARY))
        sinks.reset()
        val done = controller.execute(AudioIntent.InterruptionBegan(wasSuspended = true))
        assertTrue(done.ignored)
        assertTrue(done.preservesAudibleLane)
        assertEquals(PlaybackLaneRole.PRIMARY, controller.audibleLane)
        assertTrue(controller.holdingFocus)
        assertTrue(sinks.events.all { it.startsWith("detach:") })
        assertTrue(sinks.writes.isEmpty())
        assertEquals(0, sinks.pauses)
    }

    @Test
    fun testStopOnBackgroundAbandonsWithoutWritingSettings() {
        controller.start()
        controller.execute(AudioIntent.Unmute(PlaybackLaneRole.PRIMARY))
        sinks.reset()
        controller.stop()
        assertFalse(controller.holdingFocus)
        assertNull(controller.audibleLane)
        assertTrue(sinks.writes.isEmpty())
        assertEquals(0, sinks.pauses)
        assertNotNullAbandoned()

        sinks.reset()
        context.sendBroadcast(Intent(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(sinks.events.isEmpty())
    }

    @Test
    fun testNonFocusUnmuteDoesNotRequestOrWrite() {
        controller.execute(AudioIntent.Unmute(PlaybackLaneRole.PRIMARY))
        val granted = shadowOf(audioManager).lastAudioFocusRequest.audioFocusRequest
        sinks.reset()
        val done = controller.execute(AudioIntent.Unmute(PlaybackLaneRole.SECONDARY))
        assertEquals(PlaybackLaneRole.PRIMARY, done.audibleLane)
        assertEquals(PlaybackLaneRole.PRIMARY, controller.audibleLane)
        assertFalse(done.requestsFocus())
        assertSame(granted, shadowOf(audioManager).lastAudioFocusRequest.audioFocusRequest)
        assertTrue(controller.holdingFocus)
        assertNull(shadowOf(audioManager).lastAbandonedAudioFocusRequest)
        assertTrue(sinks.writes.isEmpty())
        assertTrue(sinks.events.contains("mute:${PlaybackLaneRole.SECONDARY}"))
        assertFalse(sinks.events.contains("unmute:${PlaybackLaneRole.SECONDARY}"))
    }

    private fun roleOf(event: String): PlaybackLaneRole =
        PlaybackLaneRole.valueOf(event.substringAfter(':'))

    private fun assertNotNullAbandoned() {
        assertTrue(shadowOf(audioManager).lastAbandonedAudioFocusRequest != null)
    }

    private class RecordingSinks : AudioFocusController.Sinks {
        val events = mutableListOf<String>()
        val writes = mutableListOf<Boolean>()
        val notices = mutableListOf<String>()
        var pauses: Int = 0

        fun reset() {
            events.clear()
            writes.clear()
            notices.clear()
            pauses = 0
        }

        override fun muteLane(role: PlaybackLaneRole) {
            events.add("mute:$role")
        }

        override fun muteAll() {
            events.add("muteAll")
        }

        override fun unmuteLane(role: PlaybackLaneRole) {
            events.add("unmute:$role")
        }

        override fun detachLane(role: PlaybackLaneRole) {
            events.add("detach:$role")
        }

        override fun writeMuted(muted: Boolean) {
            writes.add(muted)
            events.add("write:$muted")
        }

        override fun syncRuntimeMuted(muted: Boolean) {
            events.add("runtime:$muted")
        }

        override fun showNotice(text: String) {
            notices.add(text)
            events.add("notice:$text")
        }

        override fun pausePlayback() {
            pauses += 1
            events.add("pause")
        }
    }
}
