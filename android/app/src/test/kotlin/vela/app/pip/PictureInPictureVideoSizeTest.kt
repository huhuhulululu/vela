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
        val context = ApplicationProvider.getApplicationContext<Context>()
        shadowOf(context.packageManager)
            .setSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE, true)
    }

    @After
    fun tearDown() {
        opened.forEach { runCatching { it.close() } }
        opened.clear()
        RecordingPipParamsShadow.recorded.clear()
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
}

/** 系统影子不记 setPictureInPictureParams，这里补一层记录，供断言比例用。 */
@Implements(Activity::class)
class RecordingPipParamsShadow : ShadowActivity() {
    @Implementation(minSdk = 26)
    protected fun setPictureInPictureParams(params: PictureInPictureParams) {
        recorded.add(params)
    }

    companion object {
        val recorded = CopyOnWriteArrayList<PictureInPictureParams>()
    }
}
