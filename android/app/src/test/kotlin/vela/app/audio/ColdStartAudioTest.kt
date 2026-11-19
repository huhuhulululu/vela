package vela.app.audio

import android.content.Context
import android.media.AudioManager
import androidx.activity.ComponentActivity
import androidx.test.core.app.ApplicationProvider
import java.nio.file.Files
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import vela.app.infra.Settings
import vela.app.stage.AppPlaybackSession
import vela.app.stage.LifecycleWiring
import vela.app.stage.StageViewModel
import vela.app.stage.answerHappy
import vela.app.stage.scriptHttp
import vela.core.base.model.FeedSource
import vela.core.lifecycle.PhoneScenePhase

/**
 * 冷启动的 INACTIVE→ACTIVE 不会走 EpochResume，因此以前既不登记拔耳机，
 * 保存的取消静音也不会先申请焦点。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ColdStartAudioTest {
    @Test
    fun testFirstForegroundRegistersNoisyRouteAndRequestsFocusBeforeSound() {
        val fixture = open(muted = false)
        try {
            assertTrue(
                "还没进前台不该先登记拔耳机",
                !noisyRegistered(fixture.activity),
            )
            fixture.wiring.moveTo(PhoneScenePhase.ACTIVE)
            assertTrue("冷启动前台没有登记拔耳机", noisyRegistered(fixture.activity))
            assertNotNull(
                "保存的取消静音没有申请焦点",
                shadowOf(fixture.audio).lastAudioFocusRequest,
            )
        } finally {
            fixture.stage.close()
            fixture.directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun testEnsureLaneInitializesAudioBeforeThePlayerCanStart() {
        val fixture = open(muted = false)
        try {
            fixture.session.ensureLane()
            assertTrue("起播前没有登记拔耳机", noisyRegistered(fixture.activity))
            assertNotNull(shadowOf(fixture.audio).lastAudioFocusRequest)
        } finally {
            fixture.session.releaseLeftovers()
            fixture.stage.close()
            fixture.directory.toFile().deleteRecursively()
        }
    }

    private fun open(muted: Boolean): Fixture {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences(Settings.PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        val settings = Settings(context)
        settings.ageConfirmed = true
        settings.hintSeen = true
        settings.muted = muted
        settings.source = FeedSource.popular
        val directory = Files.createTempDirectory("vela-cold-audio")
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
        val wiring = LifecycleWiring(stage, session)
        return Fixture(activity, audio, stage, session, wiring, directory)
    }

    private fun noisyRegistered(activity: ComponentActivity): Boolean {
        val action = AudioManager.ACTION_AUDIO_BECOMING_NOISY
        return shadowOf(activity.application).registeredReceivers.any { wrapper ->
            val filter = wrapper.intentFilter
            filter != null && (0 until filter.countActions()).any { filter.getAction(it) == action }
        }
    }

    private class Fixture(
        val activity: ComponentActivity,
        val audio: AudioManager,
        val stage: StageViewModel,
        val session: AppPlaybackSession,
        val wiring: LifecycleWiring,
        val directory: java.nio.file.Path,
    )
}
