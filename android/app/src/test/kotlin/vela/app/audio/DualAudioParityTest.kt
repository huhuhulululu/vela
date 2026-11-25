package vela.app.audio

import android.content.Context
import android.media.AudioManager
import androidx.activity.ComponentActivity
import androidx.test.core.app.ApplicationProvider
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import vela.app.infra.Settings
import vela.app.stage.AppPlaybackSession
import vela.app.stage.StageViewModel
import vela.app.stage.answerHappy
import vela.app.stage.scriptHttp
import vela.app.stage.setDual
import vela.app.stage.setFocus
import vela.core.audio.AudioFocusPlan
import vela.core.audio.AudioIntent
import vela.core.audio.DeactivationReason
import vela.core.audio.RouteReason
import vela.core.base.lane.LaneID
import vela.core.base.lane.PlaybackLaneRole
import vela.core.base.model.FeedSource
import vela.core.base.model.Room
import vela.core.base.model.ShowState
import vela.core.base.site.CatalogPage
import vela.core.selection.LaneSelection

/**
 * 双路音频切换执行顺序与焦点互斥 parity 测试
 * （对应 AudioTransitionRunnerTests 与 DualAudioFocusTests 中安卓已实现的行为）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DualAudioParityTest {
    private lateinit var context: Context
    private lateinit var audioManager: AudioManager
    private lateinit var sinks: Recorder
    private lateinit var controller: AudioFocusController

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences(Settings.PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        audioManager = context.getSystemService(AudioManager::class.java)
        shadowOf(audioManager).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        sinks = Recorder()
        controller = AudioFocusController(context, audioManager, sinks)
        controller.focus = PlaybackLaneRole.PRIMARY
        controller.lanes = listOf(PlaybackLaneRole.PRIMARY, PlaybackLaneRole.SECONDARY)
    }

    // MARK: AudioTransitionRunnerTests (10)

    @Test
    fun testUnmuteRunsInOrderAndUnmutesOnlyAfterActivation() {
        controller.focus = PlaybackLaneRole.SECONDARY
        controller.execute(AudioIntent.Unmute(PlaybackLaneRole.SECONDARY))
        sinks.reset()

        controller.focus = PlaybackLaneRole.PRIMARY
        val done = controller.execute(AudioIntent.Unmute(PlaybackLaneRole.PRIMARY))
        val muteIdx = sinks.events.indexOf("mute:${PlaybackLaneRole.SECONDARY}")
        val unmuteIdx = sinks.events.indexOf("unmute:${PlaybackLaneRole.PRIMARY}")
        assertTrue("应先静音旧路再取消新路静音 events=${sinks.events}", muteIdx >= 0 && unmuteIdx > muteIdx)
        assertTrue(controller.holdingFocus)
        assertEquals(PlaybackLaneRole.PRIMARY, done.audibleLane)
        assertEquals(PlaybackLaneRole.PRIMARY, controller.audibleLane)
        assertTrue(done.writesSettings)
        assertNull(done.notice)
        assertEquals(listOf(false), sinks.writes)
    }

    @Test
    fun testActivationFailureNeverUnmutes() {
        shadowOf(audioManager).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
        val done = controller.execute(AudioIntent.Unmute(PlaybackLaneRole.PRIMARY))
        assertFalse(sinks.events.any { it.startsWith("unmute:") })
        assertNull(done.audibleLane)
        assertNull(controller.audibleLane)
        assertFalse(controller.holdingFocus)
        assertFalse(done.writesSettings)
        assertTrue(sinks.writes.isEmpty())
        assertEquals(AudioFocusPlan.FAILURE_NOTICE, done.notice)
        assertEquals(listOf(AudioFocusPlan.FAILURE_NOTICE), sinks.notices)
        assertEquals(listOf(true), sinks.runtimeMuted)
    }

    @Test
    fun testMuteDeactivatesAbandoningFocusAndWritesSettings() {
        controller.execute(AudioIntent.Unmute(PlaybackLaneRole.PRIMARY))
        val granted = shadowOf(audioManager).lastAudioFocusRequest.audioFocusRequest
        sinks.reset()

        val done = controller.execute(AudioIntent.Deactivate(DeactivationReason.MUTE))
        assertTrue(sinks.events.contains("muteAll"))
        assertTrue(done.abandonsFocus())
        assertEquals(granted, shadowOf(audioManager).lastAbandonedAudioFocusRequest)
        assertNull(done.audibleLane)
        assertNull(controller.audibleLane)
        assertTrue(done.writesSettings)
        assertEquals(listOf(true), sinks.writes)
        assertNull(done.notice)
    }

    @Test
    fun testMuteWithPictureInPictureKeepsPlaybackAndAbandonsFocus() {
        controller.execute(AudioIntent.Unmute(PlaybackLaneRole.PRIMARY))
        controller.pictureInPictureActive = true
        sinks.reset()

        val done = controller.execute(AudioIntent.Deactivate(DeactivationReason.MUTE))
        assertTrue(done.keepsPlayback)
        assertFalse(done.pausesPlayback)
        assertTrue(done.abandonsFocus())
        assertFalse(controller.holdingFocus)
        assertNull(controller.audibleLane)
        assertEquals(listOf(true), sinks.writes)
        assertEquals(0, sinks.pauses)
    }

    @Test
    fun testMuteFailureStillReportsMuted() {
        val planned = AudioFocusPlan.plan(
            intent = AudioIntent.Deactivate(DeactivationReason.MUTE),
            focus = PlaybackLaneRole.PRIMARY,
            currentlyAudible = PlaybackLaneRole.PRIMARY,
            lanes = listOf(PlaybackLaneRole.PRIMARY, PlaybackLaneRole.SECONDARY),
        )
        val done = AudioFocusPlan.finished(planned, focusGranted = false)
        assertNull(done.audibleLane)
        assertNull(done.notice)
        assertTrue(done.writesSettings)
        assertFalse(done.attemptsAudible)
    }

    @Test
    fun testInterruptionBeganMutesAllWithoutWritingSettings() {
        controller.execute(AudioIntent.Unmute(PlaybackLaneRole.PRIMARY))
        sinks.reset()

        val done = controller.execute(AudioIntent.InterruptionBegan(wasSuspended = false))
        assertTrue(sinks.events.contains("muteAll"))
        assertNull(done.audibleLane)
        assertNull(controller.audibleLane)
        assertFalse(done.writesSettings)
        assertTrue(sinks.writes.isEmpty())
        assertEquals(listOf(true), sinks.runtimeMuted)
    }

    @Test
    fun testSuspendedInterruptionAndInterruptionEndedLeaveStateAlone() {
        controller.execute(AudioIntent.Unmute(PlaybackLaneRole.PRIMARY))
        sinks.reset()
        val ignored = controller.execute(AudioIntent.InterruptionBegan(wasSuspended = true))
        assertTrue(ignored.ignored)
        assertTrue(ignored.preservesAudibleLane)
        assertEquals(PlaybackLaneRole.PRIMARY, controller.audibleLane)
        assertFalse(sinks.events.any { it == "muteAll" || it.startsWith("mute:") })

        controller.execute(AudioIntent.Deactivate(DeactivationReason.MUTE))
        sinks.reset()
        val ended = controller.execute(AudioIntent.InterruptionEnded)
        assertTrue(ended.preservesAudibleLane)
        assertNull(controller.audibleLane)
        assertFalse(sinks.events.any { it.startsWith("unmute:") })
    }

    @Test
    fun testOldDeviceUnavailableMutesWithoutPause() {
        controller.execute(AudioIntent.Unmute(PlaybackLaneRole.PRIMARY))
        val granted = shadowOf(audioManager).lastAudioFocusRequest.audioFocusRequest
        sinks.reset()

        val done = controller.execute(AudioIntent.Route(RouteReason.OLD_DEVICE_UNAVAILABLE))
        assertNull(done.audibleLane)
        assertNull(controller.audibleLane)
        assertFalse(done.pausesPlayback)
        assertEquals(0, sinks.pauses)
        assertEquals(granted, shadowOf(audioManager).lastAbandonedAudioFocusRequest)
        assertEquals(listOf(true), sinks.writes)
    }

    @Test
    fun testOldDeviceUnavailableKeepsPlaybackWhenPictureInPictureActive() {
        controller.execute(AudioIntent.Unmute(PlaybackLaneRole.PRIMARY))
        controller.pictureInPictureActive = true
        sinks.reset()

        val done = controller.execute(AudioIntent.Route(RouteReason.OLD_DEVICE_UNAVAILABLE))
        assertTrue(done.keepsPlayback)
        assertFalse(done.pausesPlayback)
        assertEquals(0, sinks.pauses)
        assertNull(controller.audibleLane)
        assertEquals(listOf(true), sinks.writes)
    }

    @Test
    fun testOtherRouteChangeDoesNothing() {
        controller.execute(AudioIntent.Unmute(PlaybackLaneRole.PRIMARY))
        sinks.reset()

        val done = controller.execute(AudioIntent.Route(RouteReason.OTHER))
        assertTrue(done.preservesAudibleLane)
        assertEquals(PlaybackLaneRole.PRIMARY, controller.audibleLane)
        assertTrue(controller.holdingFocus)
        assertTrue(sinks.writes.isEmpty())
        assertFalse(sinks.events.any { it == "muteAll" || it.startsWith("mute:") || it.startsWith("unmute:") })
    }

    // MARK: DualAudioFocusTests (3)

    @Test
    fun testFocusSwitchWhileMutedDoesNotTouchSession() {
        val env = openDualSession(muted = true)
        try {
            assertNull(shadowOf(env.audio).lastAudioFocusRequest)
            assertNull(shadowOf(env.audio).lastAbandonedAudioFocusRequest)

            env.stage.setFocus(LaneID.SECONDARY)

            assertNull(shadowOf(env.audio).lastAudioFocusRequest)
            assertNull(shadowOf(env.audio).lastAbandonedAudioFocusRequest)
            assertTrue(env.stage.muted)
            assertEquals(LaneID.SECONDARY, env.stage.focusLane)
        } finally {
            env.close()
        }
    }

    @Test
    fun testActivationFailureKeepsAllLanesMuted() {
        val env = openDualSession(muted = true)
        try {
            env.stage.settings.muted = false
            env.stage.muted = false
            shadowOf(env.audio).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_FAILED)

            env.stage.setFocus(LaneID.SECONDARY)

            assertTrue("申请焦点失败后运行时必须保持静音", env.stage.muted)
            assertFalse("失败不改写静音设置", env.stage.settings.muted)
            assertNull(env.session.audioController().audibleLane)
            assertEquals(AudioFocusPlan.FAILURE_NOTICE, env.stage.toast)
            assertEquals(LaneID.SECONDARY, env.stage.focusLane)
        } finally {
            env.close()
        }
    }

    @Test
    fun testNonFocusLaneIsMutedWhenNewLaneJoinsWhileUnmuted() {
        val env = openDualSession(muted = false)
        try {
            env.stage.setDual(false)
            env.session.ensureLane()
            env.session.onMuteChanged(false)
            assertFalse(env.stage.muted)

            env.stage.setDual(true)
            env.session.ensureSecondary()
            val primary = env.session.lane.value
            val secondary = env.session.secondaryLaneForTest
            assertNotNull(primary)
            assertNotNull(secondary)
            assertEquals(LaneID.PRIMARY, env.stage.focusLane)
            assertEquals(0f, secondary!!.playerVolumeForTest(), 0.0001f)
            assertEquals(0, secondary.audioEnabledCountForTest())
        } finally {
            env.close()
        }
    }

    @Test
    fun testClosingDualWithSecondaryFocusReturnsSoundToPrimary() {
        val env = openDualSession(muted = false)
        try {
            env.session.ensureLane()
            env.session.ensureSecondary()
            env.session.onMuteChanged(false)
            env.stage.setFocus(LaneID.SECONDARY)
            val primary = env.session.lane.value!!
            val secondary = env.session.secondaryLaneForTest!!
            assertEquals("副路聚焦时主路应静音", 0f, primary.playerVolumeForTest(), 0.0001f)
            assertTrue("副路聚焦时副路应出声", secondary.playerVolumeForTest() > 0f)

            env.stage.setDual(false)
            assertEquals(1, env.stage.laneCount)
            assertEquals(LaneID.PRIMARY, env.stage.focusLane)
            assertFalse("关双窗后运行时应维持未静音", env.stage.muted)
            assertTrue("关双窗后声音应回到主路", primary.playerVolumeForTest() > 0f)
            assertEquals(PlaybackLaneRole.PRIMARY, env.session.audioController().audibleLane)
            assertNull("关双窗后副路应已释放", env.session.secondaryLaneForTest)
        } finally {
            env.close()
        }
    }

    private fun openDualSession(muted: Boolean): DualSessionEnv {
        val prefs = context.getSharedPreferences(Settings.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val settings = Settings(context)
        settings.ageConfirmed = true
        settings.hintSeen = true
        settings.muted = muted
        settings.source = FeedSource.popular
        val directory = Files.createTempDirectory("vela-dual-audio-parity")
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val audio = activity.getSystemService(AudioManager::class.java)
        shadowOf(audio).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        val stage = StageViewModel(
            settings = settings,
            http = scriptHttp { answerHappy(it) },
            directory = directory,
            context = activity,
        )
        val session = AppPlaybackSession(
            activity = activity,
            stage = stage,
            disposeComposition = {},
            restoreComposition = {},
        )
        stage.bindLifecycleHooks(
            ensurePlayback = { session.ensureLane() },
            onMuteChanged = { next -> session.onMuteChanged(next) },
        )
        stage.updateFeed(installing = FeedSource.popular, intent = LaneSelection.Intent.Reload) { store ->
            store.replace(
                CatalogPage(
                    rooms = listOf("alice", "bob", "carol").map {
                        Room(username = it, viewers = 5, show = ShowState.publicShow)
                    },
                    total = 3,
                    nextOffset = 3,
                ),
            )
            store.setQuery(stage.currentQuery())
        }
        stage.setDual(true)
        return DualSessionEnv(audio, stage, session, directory)
    }

    private class DualSessionEnv(
        val audio: AudioManager,
        val stage: StageViewModel,
        val session: AppPlaybackSession,
        private val directory: java.nio.file.Path,
    ) {
        fun close() {
            session.releaseLeftovers()
            stage.close()
            directory.toFile().deleteRecursively()
        }
    }

    private class Recorder : AudioFocusController.Sinks {
        val events = mutableListOf<String>()
        val writes = mutableListOf<Boolean>()
        val runtimeMuted = mutableListOf<Boolean>()
        val notices = mutableListOf<String>()
        var pauses: Int = 0

        fun reset() {
            events.clear()
            writes.clear()
            runtimeMuted.clear()
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
            runtimeMuted.add(muted)
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
