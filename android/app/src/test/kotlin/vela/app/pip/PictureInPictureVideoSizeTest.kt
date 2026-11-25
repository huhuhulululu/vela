package vela.app.pip

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.test.core.app.ApplicationProvider
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowActivity
import org.robolectric.shadows.ShadowLooper
import vela.app.MainActivity
import vela.app.infra.Settings
import vela.app.lane.FakeLanePlayer
import vela.app.lane.PlaybackLane
import vela.app.playback.LaneHub
import vela.app.stage.StageViewModel
import vela.core.base.lane.LaneID
import vela.core.base.model.FeedSource
import vela.core.base.net.HTTPClient
import vela.core.base.net.HTTPRequest
import vela.core.base.net.HTTPResult

/**
 * 焦点路画面尺寸到达后，画中画比例要用画面的宽高比，不能停留在 9:16；
 * 无效尺寸不设置参数，尺寸没变不重复设置。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], shadows = [RecordingPipParamsShadow::class])
class PictureInPictureVideoSizeTest {
    private val opened = ArrayList<StageViewModel>()

    @Before
    fun setUp() {
        RecordingPipParamsShadow.recorded.clear()
        RecordingPipParamsShadow.inPipMode = false
        val context = ApplicationProvider.getApplicationContext<Context>()
        shadowOf(context.packageManager)
            .setSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE, true)
    }

    @After
    fun tearDown() {
        opened.forEach { runCatching { it.close() } }
        opened.clear()
        RecordingPipParamsShadow.recorded.clear()
        RecordingPipParamsShadow.inPipMode = false
    }

    @Test
    fun testFocusLaneVideoSizeArrivalReplacesFallbackAspect() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences(Settings.PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        val settings = Settings(context)
        settings.ageConfirmed = true
        settings.hintSeen = true
        settings.muted = true
        settings.source = FeedSource.popular
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        try {
            assertNotNull("支持画中画的设备要挂上控制器", activity.pictureInPicture)
            activity.session.ensureLane()
            ShadowLooper.idleMainLooper()
            val lane = activity.stage.primaryLane
            assertNotNull("ensureLane 之后要有主路播放器", lane)
            val before = RecordingPipParamsShadow.recorded.lastOrNull()
            assertNotNull("attach 后应已设置过画中画参数", before)
            assertEquals("还没有画面尺寸时用 9:16", 9.0 / 16.0, before!!.aspectRatio!!.toDouble(), 0.001)

            RecordingPipParamsShadow.recorded.clear()
            lane!!.onVideoSize(1920, 1080)
            ShadowLooper.idleMainLooper()
            val noted = RecordingPipParamsShadow.recorded.toList()
            assertEquals("尺寸到达要刷新一次参数", 1, noted.size)
            assertEquals(
                "比例要用画面的 16:9，不是 9:16",
                1920.0 / 1080.0,
                noted[0].aspectRatio!!.toDouble(),
                0.01,
            )

            lane.onVideoSize(1920, 1080)
            ShadowLooper.idleMainLooper()
            assertEquals("尺寸没变不要反复设置", 1, RecordingPipParamsShadow.recorded.size)

            lane.onVideoSize(1080, 1920)
            ShadowLooper.idleMainLooper()
            val all = RecordingPipParamsShadow.recorded.toList()
            assertEquals("宽高变化要再刷新", 2, all.size)
            assertEquals(1080.0 / 1920.0, all[1].aspectRatio!!.toDouble(), 0.01)
        } finally {
            controller.destroy()
        }
    }

    @Test
    fun testNoteVideoSizeIgnoresInvalidAndRepeatedSizes() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences(Settings.PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        val settings = Settings(context)
        settings.ageConfirmed = true
        settings.hintSeen = true
        settings.muted = true
        val directory = Files.createTempDirectory("vela-pip-size")
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val stage = StageViewModel(
            settings = settings,
            http = object : HTTPClient {
                override suspend fun send(request: HTTPRequest): HTTPResult =
                    HTTPResult(status = 200, data = ByteArray(0))
            },
            directory = directory,
            context = activity,
        )
        opened.add(stage)
        val lane = PlaybackLane(id = LaneID.PRIMARY, hub = LaneHub(), player = FakeLanePlayer())
        stage.primaryLane = lane
        val pip = ActivityPictureInPicture(activity, stage, onEvent = {})
        pip.attach()
        try {
            RecordingPipParamsShadow.recorded.clear()
            pip.noteVideoSize(0.0, 0.0)
            pip.noteVideoSize(-1.0, 100.0)
            assertTrue("无效尺寸不设置参数", RecordingPipParamsShadow.recorded.isEmpty())

            pip.noteVideoSize(1280.0, 720.0)
            assertEquals(1, RecordingPipParamsShadow.recorded.size)
            assertEquals(
                1280.0 / 720.0,
                RecordingPipParamsShadow.recorded.last().aspectRatio!!.toDouble(),
                0.01,
            )

            pip.noteVideoSize(1280.0, 720.0)
            assertEquals("尺寸没变不要反复设置", 1, RecordingPipParamsShadow.recorded.size)

            pip.noteVideoSize(720.0, 1280.0)
            assertEquals(2, RecordingPipParamsShadow.recorded.size)
            assertEquals(
                720.0 / 1280.0,
                RecordingPipParamsShadow.recorded.last().aspectRatio!!.toDouble(),
                0.01,
            )
        } finally {
            pip.detach()
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun testAutoEnterParamRefreshesWithPlaybackPauseAndFocusChanges() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences(Settings.PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        val settings = Settings(context)
        settings.ageConfirmed = true
        settings.hintSeen = true
        settings.muted = true
        settings.autoEnterPip = true
        val directory = Files.createTempDirectory("vela-pip-auto-enter")
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val activity = controller.get()
        val stage = StageViewModel(
            settings = settings,
            http = object : HTTPClient {
                override suspend fun send(request: HTTPRequest): HTTPResult =
                    HTTPResult(status = 200, data = ByteArray(0))
            },
            directory = directory,
            context = activity,
        )
        opened.add(stage)
        stage.updateFeed(installing = FeedSource.popular) { store ->
            store.replace(
                vela.core.base.site.CatalogPage(
                    rooms = listOf(
                        vela.core.base.model.Room(username = "alice", viewers = 2, show = vela.core.base.model.ShowState.publicShow),
                        vela.core.base.model.Room(username = "bob", viewers = 2, show = vela.core.base.model.ShowState.publicShow),
                    ),
                    total = 2,
                    nextOffset = 2,
                ),
            )
        }
        val lane = PlaybackLane(id = LaneID.PRIMARY, hub = LaneHub(), player = FakeLanePlayer())
        stage.primaryLane = lane
        val pip = ActivityPictureInPicture(activity, stage, onEvent = {})
        pip.attach()
        ShadowLooper.idleMainLooper()
        try {
            assertTrue("未起播时 autoEnterEnabled 应为 false", !RecordingPipParamsShadow.recorded.last().isAutoEnterEnabled)

            // 模拟 ExoLanePlayer 在 Playing 之前通过 onTracksChanged 提前报出视频尺寸。
            lane.onVideoSize(1920, 1080)
            pip.noteVideoSize(1920.0, 1080.0)
            ShadowLooper.idleMainLooper()
            assertTrue("尺寸到达但未 Playing 时仍应为 false", !RecordingPipParamsShadow.recorded.last().isAutoEnterEnabled)

            // 随后起播，尺寸不变（不会再触发 noteVideoSize），系统参数必须刷新为 true 且保留 16:9 比例。
            stage.notePlaying("alice")
            ShadowLooper.idleMainLooper()
            val playing = RecordingPipParamsShadow.recorded.last()
            assertTrue("起播后自动画中画参数应刷新为 true", playing.isAutoEnterEnabled)
            assertEquals(1920.0 / 1080.0, playing.aspectRatio!!.toDouble(), 0.01)

            // 手动暂停后自动画中画参数必须刷新回 false。
            stage.dispatch(vela.app.stage.StageIntent.TogglePause(null))
            ShadowLooper.idleMainLooper()
            assertTrue("手动暂停后自动画中画参数应刷新为 false", !RecordingPipParamsShadow.recorded.last().isAutoEnterEnabled)

            // 恢复播放回到 true。
            stage.dispatch(vela.app.stage.StageIntent.TogglePause(null))
            ShadowLooper.idleMainLooper()
            assertTrue("取消暂停后自动画中画参数应刷新回 true", RecordingPipParamsShadow.recorded.last().isAutoEnterEnabled)

            // 双窗切焦点到未起播的副路，自动画中画参数应刷新为 false。
            stage.dispatch(vela.app.stage.StageIntent.SetDual(true))
            stage.dispatch(vela.app.stage.StageIntent.SetFocus(LaneID.SECONDARY))
            ShadowLooper.idleMainLooper()
            assertTrue("切焦点到未播副路后自动画中画参数应刷新为 false", !RecordingPipParamsShadow.recorded.last().isAutoEnterEnabled)
        } finally {
            pip.detach()
            controller.destroy()
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun testExpandFromPipRestoresForegroundWithoutFullStop() {
        val (controller, stage, wiring, pip, directory) = buildPipActivityHarness("vela-pip-expand")
        val activity = controller.get()
        try {
            // 1. 前台起播双窗。
            wiring.moveTo(vela.core.lifecycle.PhoneScenePhase.ACTIVE)
            stage.notePlaying("alice")
            ShadowLooper.idleMainLooper()
            assertTrue(RecordingPipParamsShadow.recorded.last().isAutoEnterEnabled)

            // 2. 模拟 API 34 自动进入画中画：onPause -> onPictureInPictureModeChanged(true)，无 onStop。
            controller.pause()
            wiring.moveTo(vela.core.lifecycle.PhoneScenePhase.INACTIVE)
            pip.onPictureInPictureModeChanged(true)
            ShadowLooper.idleMainLooper()
            assertEquals(vela.core.lifecycle.PhoneScenePhase.BACKGROUND, wiring.phase)
            assertEquals(vela.core.base.lane.PlaybackLaneRole.PRIMARY, stage.lifecycleEpoch.allowedLane)

            // 3. 模拟用户点小窗展开回全屏：Activity 仍 STARTED 且未 finish，先回调 onPictureInPictureModeChanged(false)，再 onResume。
            pip.onPictureInPictureModeChanged(false)
            ShadowLooper.idleMainLooper()
            assertTrue("展开回全屏时在 onResume 到达前不得立即全停", stage.playbackGateOpen)

            controller.resume()
            wiring.moveTo(vela.core.lifecycle.PhoneScenePhase.ACTIVE)
            ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
            assertEquals(vela.core.lifecycle.PhoneScenePhase.ACTIVE, wiring.phase)
            assertTrue("回到前台后应保持播放门打开", stage.playbackGateOpen)
            assertTrue(!stage.focusLocked && !stage.laneCountLocked)
        } finally {
            pip.detach()
            controller.destroy()
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun testDismissingPipAfterOnStopStopsAllImmediately() {
        val (controller, stage, wiring, pip, directory) = buildPipActivityHarness("vela-pip-dismiss")
        try {
            wiring.moveTo(vela.core.lifecycle.PhoneScenePhase.ACTIVE)
            stage.notePlaying("alice")
            ShadowLooper.idleMainLooper()

            // 自动进入画中画（无 onStop）。
            controller.pause()
            wiring.moveTo(vela.core.lifecycle.PhoneScenePhase.INACTIVE)
            pip.onPictureInPictureModeChanged(true)
            ShadowLooper.idleMainLooper()
            assertEquals(vela.core.lifecycle.PhoneScenePhase.BACKGROUND, wiring.phase)

            // 模拟用户关闭小窗：系统先 onStop（lifecycle 降为 CREATED），再 onPictureInPictureModeChanged(false)。
            controller.stop()
            wiring.moveTo(vela.core.lifecycle.PhoneScenePhase.BACKGROUND)
            pip.onPictureInPictureModeChanged(false)
            ShadowLooper.idleMainLooper()
            assertTrue("关闭画中画后必须立即关掉播放门", !stage.playbackGateOpen)
            assertEquals("关闭画中画后不得再放行任何路", null, stage.lifecycleEpoch.allowedLane)
        } finally {
            pip.detach()
            controller.destroy()
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun testDismissingPipWhenModeChangedArrivesBeforeOnStopStopsImmediately() {
        val (controller, stage, wiring, pip, directory) = buildPipActivityHarness("vela-pip-dismiss-order")
        try {
            wiring.moveTo(vela.core.lifecycle.PhoneScenePhase.ACTIVE)
            stage.notePlaying("alice")
            ShadowLooper.idleMainLooper()

            // 自动进入画中画（无 onStop）。
            controller.pause()
            wiring.moveTo(vela.core.lifecycle.PhoneScenePhase.INACTIVE)
            pip.onPictureInPictureModeChanged(true)
            ShadowLooper.idleMainLooper()
            assertEquals(vela.core.lifecycle.PhoneScenePhase.BACKGROUND, wiring.phase)

            // 部分设备关闭小窗时先回调 onPictureInPictureModeChanged(false)（此时仍 STARTED），紧接着回调 onStop。
            pip.onPictureInPictureModeChanged(false)
            ShadowLooper.idleMainLooper()
            controller.stop()
            wiring.moveTo(vela.core.lifecycle.PhoneScenePhase.BACKGROUND)
            ShadowLooper.idleMainLooper()

            assertTrue("收到紧随其后的 onStop 后应立即全停，不等 3 秒超时", !stage.playbackGateOpen)
            assertEquals("关闭画中画后不得继续放行焦点路", null, stage.lifecycleEpoch.allowedLane)
        } finally {
            pip.detach()
            controller.destroy()
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun testDismissingPipOnStopWhileSystemStillReportsInPipStopsImmediately() {
        val (controller, stage, wiring, pip, directory) = buildPipActivityHarness("vela-pip-stop-inpip")
        try {
            wiring.moveTo(vela.core.lifecycle.PhoneScenePhase.ACTIVE)
            stage.notePlaying("alice")
            ShadowLooper.idleMainLooper()

            controller.pause()
            wiring.moveTo(vela.core.lifecycle.PhoneScenePhase.INACTIVE)
            RecordingPipParamsShadow.inPipMode = true
            pip.onPictureInPictureModeChanged(true)
            ShadowLooper.idleMainLooper()
            assertEquals(vela.core.lifecycle.PhoneScenePhase.BACKGROUND, wiring.phase)
            assertEquals(vela.core.base.lane.PlaybackLaneRole.PRIMARY, stage.lifecycleEpoch.allowedLane)

            // 真实系统在 onStop 先于 modeChanged(false)（或锁屏/退后台不发 modeChanged(false)）时，
            // onStop 触发瞬间 !isFinishing 且 isInPictureInPictureMode 仍为 true，必须立刻全停。
            controller.stop()
            wiring.moveTo(vela.core.lifecycle.PhoneScenePhase.BACKGROUND)
            ShadowLooper.idleMainLooper()
            assertTrue("onStop 时即便系统尚未翻回 isInPictureInPictureMode 也必须立即关门", !stage.playbackGateOpen)
            assertEquals("onStop 后不得继续放行焦点路", null, stage.lifecycleEpoch.allowedLane)
            assertEquals(
                vela.core.lifecycle.PiPPolicy.State.NONE,
                wiring.pipCoordinator?.pictureInPictureState,
            )
            assertTrue(!stage.pictureInPicture)

            // 随后到达的 modeChanged(false) 应幂等忽略，不得重开或报错。
            RecordingPipParamsShadow.inPipMode = false
            pip.onPictureInPictureModeChanged(false)
            ShadowLooper.idleMainLooper()
            assertTrue(!stage.playbackGateOpen)
            assertEquals(null, stage.lifecycleEpoch.allowedLane)
        } finally {
            pip.detach()
            controller.destroy()
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun testDismissingPipModeChangedBeforeOnStopWithIntermediatePauseStillStopsImmediately() {
        val (controller, stage, wiring, pip, directory) = buildPipActivityHarness("vela-pip-order-bounce")
        try {
            wiring.moveTo(vela.core.lifecycle.PhoneScenePhase.ACTIVE)
            stage.notePlaying("alice")
            ShadowLooper.idleMainLooper()

            controller.pause()
            wiring.moveTo(vela.core.lifecycle.PhoneScenePhase.INACTIVE)
            RecordingPipParamsShadow.inPipMode = true
            pip.onPictureInPictureModeChanged(true)
            ShadowLooper.idleMainLooper()
            assertEquals(vela.core.lifecycle.PhoneScenePhase.BACKGROUND, wiring.phase)

            // modeChanged(false) 先到（仍 STARTED），随后任务退场前穿插一次 onPause/onStart（moveTo(INACTIVE)），再 onStop。
            RecordingPipParamsShadow.inPipMode = false
            pip.onPictureInPictureModeChanged(false)
            ShadowLooper.idleMainLooper()
            wiring.moveTo(vela.core.lifecycle.PhoneScenePhase.INACTIVE)
            controller.stop()
            wiring.moveTo(vela.core.lifecycle.PhoneScenePhase.BACKGROUND)
            ShadowLooper.idleMainLooper()

            assertEquals(vela.core.lifecycle.PhoneScenePhase.BACKGROUND, wiring.phase)
            assertTrue("中间出现 INACTIVE 抖动后收到 onStop 仍必须立即全停", !stage.playbackGateOpen)
            assertEquals(null, stage.lifecycleEpoch.allowedLane)
            assertEquals(
                vela.core.lifecycle.PiPPolicy.State.NONE,
                wiring.pipCoordinator?.pictureInPictureState,
            )
        } finally {
            pip.detach()
            controller.destroy()
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun testFinishInPipWithoutModeChangedFalseStopsAllAndClearsPipState() {
        val (controller, stage, wiring, pip, directory) = buildPipActivityHarness("vela-pip-finish")
        val activity = controller.get()
        try {
            wiring.moveTo(vela.core.lifecycle.PhoneScenePhase.ACTIVE)
            stage.notePlaying("alice")
            ShadowLooper.idleMainLooper()

            controller.pause()
            wiring.moveTo(vela.core.lifecycle.PhoneScenePhase.INACTIVE)
            RecordingPipParamsShadow.inPipMode = true
            pip.onPictureInPictureModeChanged(true)
            ShadowLooper.idleMainLooper()

            // AOSP 14 在画中画里调用 finish() 会直接走 onStop -> onDestroy，不派发 onPictureInPictureModeChanged(false)，
            // 因此销毁后的 Activity 上 isInPictureInPictureMode 仍保持 true。
            activity.finish()
            controller.stop()
            wiring.moveTo(vela.core.lifecycle.PhoneScenePhase.BACKGROUND)
            pip.detach()
            controller.destroy()
            ShadowLooper.idleMainLooper()

            assertTrue(activity.isInPictureInPictureMode)
            assertTrue(activity.isDestroyed)
            assertTrue("finish 关闭画中画后必须关掉播放门", !stage.playbackGateOpen)
            assertEquals("finish 关闭画中画后不得继续放行焦点路", null, stage.lifecycleEpoch.allowedLane)
            assertEquals(
                vela.core.lifecycle.PiPPolicy.State.NONE,
                wiring.pipCoordinator?.pictureInPictureState,
            )
            assertTrue(!stage.pictureInPicture)
        } finally {
            pip.detach()
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun testDetachWhileInPipWithoutOnStopStillStopsAll() {
        val (controller, stage, wiring, pip, directory) = buildPipActivityHarness("vela-pip-detach-direct")
        try {
            wiring.moveTo(vela.core.lifecycle.PhoneScenePhase.ACTIVE)
            stage.notePlaying("alice")
            ShadowLooper.idleMainLooper()

            controller.pause()
            wiring.moveTo(vela.core.lifecycle.PhoneScenePhase.INACTIVE)
            RecordingPipParamsShadow.inPipMode = true
            pip.onPictureInPictureModeChanged(true)
            ShadowLooper.idleMainLooper()

            // 极端情形：未经过 onStop 直接走 onDestroy/detach，也必须补发停播并归零画中画状态。
            pip.detach()
            ShadowLooper.idleMainLooper()
            assertTrue("直接 detach 后必须关掉播放门", !stage.playbackGateOpen)
            assertEquals(null, stage.lifecycleEpoch.allowedLane)
            assertEquals(
                vela.core.lifecycle.PiPPolicy.State.NONE,
                wiring.pipCoordinator?.pictureInPictureState,
            )
            assertTrue(!stage.pictureInPicture)
        } finally {
            controller.destroy()
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun testPausedStateChangesDoNotRefreshParamsUntilResumed() {
        val (controller, stage, _, pip, directory) = buildPipActivityHarness("vela-pip-pause-suppress")
        try {
            stage.notePlaying("alice")
            ShadowLooper.idleMainLooper()
            assertTrue(RecordingPipParamsShadow.recorded.last().isAutoEnterEnabled)

            // 离场进入 onPause（不在画中画）：后续舞台状态变化不应在后台持续刷 setPictureInPictureParams。
            controller.pause()
            RecordingPipParamsShadow.recorded.clear()
            stage.dispatch(vela.app.stage.StageIntent.TogglePause(null))
            ShadowLooper.idleMainLooper()
            assertTrue(
                "onPause 后非画中画期间不应刷新系统参数，实际=${RecordingPipParamsShadow.recorded.size}",
                RecordingPipParamsShadow.recorded.isEmpty(),
            )

            // 回到前台 onResume：应补刷一次最新状态（此时已手动暂停，autoEnterEnabled 应同步为 false）。
            controller.resume()
            ShadowLooper.idleMainLooper()
            assertEquals("回前台后应补刷一次参数", 1, RecordingPipParamsShadow.recorded.size)
            assertTrue(!RecordingPipParamsShadow.recorded.last().isAutoEnterEnabled)
        } finally {
            pip.detach()
            controller.destroy()
            directory.toFile().deleteRecursively()
        }
    }

    private data class PipHarness(
        val controller: org.robolectric.android.controller.ActivityController<ComponentActivity>,
        val stage: StageViewModel,
        val wiring: vela.app.stage.LifecycleWiring,
        val pip: ActivityPictureInPicture,
        val directory: java.nio.file.Path,
    )

    private fun buildPipActivityHarness(prefix: String): PipHarness {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences(Settings.PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        val settings = Settings(context)
        settings.ageConfirmed = true
        settings.hintSeen = true
        settings.muted = true
        settings.autoEnterPip = true
        val directory = Files.createTempDirectory(prefix)
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val activity = controller.get()
        val stage = StageViewModel(
            settings = settings,
            http = object : HTTPClient {
                override suspend fun send(request: HTTPRequest): HTTPResult =
                    HTTPResult(status = 200, data = ByteArray(0))
            },
            directory = directory,
            context = activity,
        )
        opened.add(stage)
        stage.updateFeed(installing = FeedSource.popular) { store ->
            store.replace(
                vela.core.base.site.CatalogPage(
                    rooms = listOf(
                        vela.core.base.model.Room(username = "alice", viewers = 2, show = vela.core.base.model.ShowState.publicShow),
                        vela.core.base.model.Room(username = "bob", viewers = 2, show = vela.core.base.model.ShowState.publicShow),
                    ),
                    total = 2,
                    nextOffset = 2,
                ),
            )
        }
        stage.dispatch(vela.app.stage.StageIntent.SetDual(true))
        val session = object : vela.app.stage.LifecycleSession {
            override fun closeGate() = Unit
            override fun onEpoch(epoch: vela.core.lifecycle.LifecycleEpoch) = Unit
            override fun suspendLanes() = Unit
            override fun deactivateAudio() = Unit
            override fun prepareAudio() = Unit
            override fun stopListener() = Unit
            override fun ensureLane() = Unit
            override fun restoreUi() = Unit
        }
        val wiring = vela.app.stage.LifecycleWiring(stage, session)
        val coordinator = PiPCoordinator(stage = stage, wiring = wiring, audio = { null })
        val pip = ActivityPictureInPicture(
            activity = activity,
            stage = stage,
            onEvent = { event -> wiring.onPictureInPictureEvent(event) },
        )
        coordinator.pip = pip
        wiring.attachPictureInPicture(coordinator)
        pip.attach()
        ShadowLooper.idleMainLooper()
        return PipHarness(controller, stage, wiring, pip, directory)
    }
}

/** 系统影子不记 setPictureInPictureParams，这里补一层记录与可编程的 isInPictureInPictureMode，供断言比例与关闭时序用。 */
@Implements(Activity::class)
class RecordingPipParamsShadow : ShadowActivity() {
    @Implementation(minSdk = 26)
    protected fun setPictureInPictureParams(params: PictureInPictureParams) {
        recorded.add(params)
    }

    @Implementation(minSdk = 24)
    public override fun isInPictureInPictureMode(): Boolean = inPipMode

    companion object {
        val recorded = CopyOnWriteArrayList<PictureInPictureParams>()

        @Volatile
        var inPipMode: Boolean = false
    }
}
