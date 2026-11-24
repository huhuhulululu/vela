package vela.app.pip

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import vela.app.infra.Settings
import vela.app.stage.LanePlayback
import vela.app.stage.LifecycleSession
import vela.app.stage.LifecycleWiring
import vela.app.stage.StageIntent
import vela.app.stage.StageViewModel
import vela.core.base.lane.LaneID
import vela.core.base.lane.PlaybackLaneRole
import vela.core.base.model.FeedSource
import vela.core.base.model.Room
import vela.core.base.model.ShowState
import vela.core.base.net.HTTPClient
import vela.core.base.net.HTTPRequest
import vela.core.base.net.HTTPResult
import vela.core.base.site.CatalogPage
import vela.core.lifecycle.LifecycleEpoch
import vela.core.lifecycle.PhoneScenePhase
import vela.core.lifecycle.PiPPolicy
import vela.core.selection.LaneSelection

/**
 * 画中画接线：进后台按 PiPPolicy 判定、3 秒回退、焦点锁、只放行焦点路。
 * 年龄已确认；目录用合成数据；控制器用桩。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PictureInPictureWiringTest {
    private lateinit var directory: java.nio.file.Path
    private val opened = ArrayList<StageViewModel>()

    @Before
    fun setUp() {
        directory = Files.createTempDirectory("vela-pip-wiring")
    }

    @After
    fun tearDown() {
        opened.forEach { runCatching { it.close() } }
        opened.clear()
        directory.toFile().deleteRecursively()
    }

    @Test
    fun testAutoEnterSettingDefaultsOnAndPersists() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("pip-settings", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val settings = Settings(prefs)
        assertTrue(settings.autoEnterPip)
        settings.autoEnterPip = false
        assertFalse(settings.autoEnterPip)
    }

    @Test
    fun testBackgroundWithPlayingFocusContinuesThatLaneOnly() {
        val harness = open(withPip = true, playing = true)
        val actions = background(harness)
        assertFalse(actions.contains("gate-off"))
        assertTrue(harness.wiring.trace().contains("continue-lane"))
        assertTrue(harness.wiring.trace().contains("retain-relay"))
        assertTrue(harness.wiring.trace().contains("allow-lane"))
        assertEquals(PlaybackLaneRole.PRIMARY, harness.stage.lifecycleEpoch.allowedLane)
        assertTrue(harness.stage.lifecycleEpoch.suspended)
        assertTrue(harness.stage.playbackGateOpen)
        assertNull(harness.stage.lifecycleEpoch.begin())
        assertNotNull(harness.stage.lifecycleEpoch.begin(PlaybackLaneRole.PRIMARY))
    }

    @Test
    fun testBackgroundWithoutPictureInPictureSupportStopsAll() {
        val harness = open(withPip = false, playing = true)
        background(harness)
        assertEquals("gate-off", harness.wiring.trace().first())
        assertNull(harness.stage.lifecycleEpoch.allowedLane)
        assertFalse(harness.stage.playbackGateOpen)
    }

    @Test
    fun testBackgroundWithAutoEnterOffStopsAll() {
        val harness = open(withPip = true, playing = true)
        harness.fakePip!!.canAutoEnter = false
        harness.fakePip!!.autoEnter = false
        background(harness)
        assertEquals("gate-off", harness.wiring.trace().first())
        assertNull(harness.stage.lifecycleEpoch.allowedLane)
    }

    @Test
    fun testBackgroundWhenFocusNotAdvancingStopsAll() {
        val harness = open(withPip = true, playing = false)
        background(harness)
        assertEquals("gate-off", harness.wiring.trace().first())
    }

    @Test
    fun testNotEnteringWithinTimeoutFallsBackToStopAll() {
        val harness = open(withPip = true, playing = true)
        harness.coordinator!!.fallbackDelaySeconds = 0.05
        background(harness)
        assertEquals(PlaybackLaneRole.PRIMARY, harness.stage.lifecycleEpoch.allowedLane)
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
        assertNull(harness.stage.lifecycleEpoch.allowedLane)
        assertFalse(harness.stage.playbackGateOpen)
        assertEquals(0, harness.fakePip!!.stopCalls)
        assertEquals(0, harness.fakePip!!.abortCalls)
    }

    @Test
    fun testStuckStartingFallsBackAndAborts() {
        val harness = open(withPip = true, playing = true)
        harness.coordinator!!.fallbackDelaySeconds = 0.05
        harness.wiring.onPictureInPictureEvent(PiPEvent.WillStart)
        background(harness)
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
        assertNull(harness.stage.lifecycleEpoch.allowedLane)
        assertEquals(1, harness.fakePip!!.abortCalls)
    }

    @Test
    fun testStartedPictureInPictureCancelsFallback() {
        val harness = open(withPip = true, playing = true)
        harness.coordinator!!.fallbackDelaySeconds = 0.05
        harness.wiring.onPictureInPictureEvent(PiPEvent.WillStart)
        background(harness)
        harness.wiring.onPictureInPictureEvent(PiPEvent.DidStart)
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
        assertEquals(PlaybackLaneRole.PRIMARY, harness.stage.lifecycleEpoch.allowedLane)
        assertEquals(PiPPolicy.State.ACTIVE, harness.coordinator!!.pictureInPictureState)
        assertEquals(0, harness.fakePip!!.abortCalls)
    }

    @Test
    fun testFailureToStartFallsBackImmediately() {
        val harness = open(withPip = true, playing = true)
        background(harness)
        harness.wiring.onPictureInPictureEvent(PiPEvent.Failed("synthetic"))
        assertNull(harness.stage.lifecycleEpoch.allowedLane)
        assertFalse(harness.stage.playbackGateOpen)
    }

    @Test
    fun testFocusCannotChangeWhileSmallWindowIsUp() {
        val harness = open(withPip = true, playing = true, dual = true)
        harness.wiring.onPictureInPictureEvent(PiPEvent.WillStart)
        harness.wiring.onPictureInPictureEvent(PiPEvent.DidStart)
        assertTrue(harness.stage.focusLocked)
        assertTrue(harness.stage.laneCountLocked)
        harness.stage.dispatch(StageIntent.SetFocus(LaneID.SECONDARY))
        assertEquals(LaneID.PRIMARY, harness.stage.focusLane)
        harness.wiring.onPictureInPictureEvent(PiPEvent.WillStop)
        harness.wiring.onPictureInPictureEvent(PiPEvent.DidStop)
        harness.stage.dispatch(StageIntent.SetFocus(LaneID.SECONDARY))
        assertEquals(LaneID.SECONDARY, harness.stage.focusLane)
    }

    @Test
    fun testDualBackgroundReleasesSecondaryKeepsPrimary() {
        val harness = open(withPip = true, playing = true, dual = true)
        background(harness)
        assertTrue(harness.wiring.trace().contains("continue-lane"))
        assertTrue(harness.wiring.trace().contains("release-lane"))
        assertEquals(1, harness.session.released.count { it == PlaybackLaneRole.SECONDARY })
        assertEquals(0, harness.session.released.count { it == PlaybackLaneRole.PRIMARY })
        assertEquals(PlaybackLaneRole.PRIMARY, harness.stage.lifecycleEpoch.allowedLane)
    }

    @Test
    fun testAspectClampUsedByFallbackConstants() {
        assertTrue(PiPAspect.MIN < 1.0)
        assertTrue(PiPAspect.MAX > 1.0)
        assertTrue(PiPAspect.clamp(9.0, 16.0).toDouble() >= PiPAspect.MIN)
    }

    @Test
    fun testDirectPipEntryWithoutOnStopEntersBackgroundAndReleasesNonFocus() {
        val harness = open(withPip = true, playing = true, dual = true)
        harness.wiring.moveTo(PhoneScenePhase.ACTIVE)
        harness.wiring.moveTo(PhoneScenePhase.INACTIVE)
        // Android 12+ 自动画中画：按 HOME 后直接进 PiP，不经过 onStop。
        harness.wiring.onPictureInPictureEvent(PiPEvent.WillStart)
        harness.wiring.onPictureInPictureEvent(PiPEvent.DidStart)

        assertEquals(PhoneScenePhase.BACKGROUND, harness.wiring.phase)
        assertEquals(PlaybackLaneRole.PRIMARY, harness.stage.lifecycleEpoch.allowedLane)
        assertEquals(1, harness.session.released.count { it == PlaybackLaneRole.SECONDARY })
        assertEquals(0, harness.session.released.count { it == PlaybackLaneRole.PRIMARY })
        assertTrue(harness.wiring.wasSuspended)
        assertTrue(harness.stage.focusLocked)
        assertTrue(harness.stage.laneCountLocked)
    }

    @Test
    fun testLatePauseAfterPipDidStartDoesNotRevertBackgroundPhase() {
        val harness = open(withPip = true, playing = true, dual = true)
        harness.wiring.moveTo(PhoneScenePhase.ACTIVE)
        // 部分时序下 onPictureInPictureModeChanged(true) 先于 onPause 到达。
        harness.wiring.onPictureInPictureEvent(PiPEvent.WillStart)
        harness.wiring.onPictureInPictureEvent(PiPEvent.DidStart)
        assertEquals(PhoneScenePhase.BACKGROUND, harness.wiring.phase)

        harness.wiring.moveTo(PhoneScenePhase.INACTIVE)
        assertEquals(
            "画中画进行中收到迟到 onPause 不得把 phase 退回 INACTIVE",
            PhoneScenePhase.BACKGROUND,
            harness.wiring.phase,
        )
        assertEquals(PlaybackLaneRole.PRIMARY, harness.stage.lifecycleEpoch.allowedLane)
    }

    private fun background(harness: Harness): List<String> {
        harness.wiring.moveTo(PhoneScenePhase.ACTIVE)
        harness.wiring.moveTo(PhoneScenePhase.INACTIVE)
        harness.wiring.moveTo(PhoneScenePhase.BACKGROUND)
        return harness.wiring.trace()
    }

    private fun open(withPip: Boolean, playing: Boolean, dual: Boolean = false): Harness {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("pip-wiring-${opened.size}", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val settings = Settings(prefs)
        settings.ageConfirmed = true
        settings.hintSeen = true
        settings.muted = true
        settings.source = FeedSource.popular
        settings.autoEnterPip = true
        if (dual) settings.dualWindow = true
        val session = RecordingSession()
        val dir = directory.resolve("s${opened.size}")
        Files.createDirectories(dir)
        val stage = StageViewModel(
            settings = settings,
            http = CatalogHttp(),
            directory = dir,
            context = context,
        )
        opened.add(stage)
        val wiring = LifecycleWiring(stage, session)
        val fake = if (withPip) FakePiP() else null
        val coordinator = PiPCoordinator(
            stage = stage,
            wiring = wiring,
            audio = { null },
        )
        coordinator.pip = fake
        coordinator.playerAdvancing = { playing }
        coordinator.fallbackDelaySeconds = 3.0
        wiring.attachPictureInPicture(coordinator)
        installRooms(stage, dual)
        if (playing) {
            stage.notePlaying(stage.feed.currentUsername)
            if (dual) {
                stage.secondaryFace = stage.secondaryFace?.copy(
                    playback = LanePlayback.Playing,
                    playingUsername = stage.secondaryCursor?.username,
                )
            }
        }
        return Harness(stage, wiring, session, coordinator, fake)
    }

    private fun installRooms(stage: StageViewModel, dual: Boolean) {
        val rooms = listOf("alice", "bob", "carol").map {
            Room(username = it, viewers = 1, show = ShowState.publicShow)
        }
        stage.updateFeed(installing = FeedSource.popular, intent = LaneSelection.Intent.Reload) { feed ->
            feed.replace(CatalogPage(rooms = rooms, total = rooms.size, nextOffset = rooms.size))
        }
        if (dual) {
            stage.dispatch(StageIntent.SetDual(true))
        }
    }

    private class Harness(
        val stage: StageViewModel,
        val wiring: LifecycleWiring,
        val session: RecordingSession,
        val coordinator: PiPCoordinator?,
        val fakePip: FakePiP?,
    )

    private class FakePiP : PictureInPictureControlling {
        override var canAutoEnter: Boolean = true
        override var autoEnter: Boolean = true
        var stopCalls = 0
        var abortCalls = 0
        override fun stop() {
            stopCalls += 1
        }
        override fun abort() {
            abortCalls += 1
        }
    }

    private class RecordingSession : LifecycleSession {
        val released = ArrayList<PlaybackLaneRole>()
        override fun closeGate() = Unit
        override fun onEpoch(epoch: LifecycleEpoch) = Unit
        override fun suspendLanes() = Unit
        override fun deactivateAudio() = Unit
        override fun prepareAudio() = Unit
        override fun stopListener() = Unit
        override fun ensureLane() = Unit
        override fun restoreUi() = Unit
        override fun releaseLane(role: PlaybackLaneRole) {
            released.add(role)
        }
    }

    private class CatalogHttp : HTTPClient {
        override suspend fun send(request: HTTPRequest): HTTPResult =
            HTTPResult(status = 200, data = ByteArray(0), headers = emptyMap())
    }
}
