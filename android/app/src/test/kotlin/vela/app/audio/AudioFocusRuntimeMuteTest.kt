package vela.app.audio

import android.content.Context
import android.media.AudioManager
import androidx.activity.ComponentActivity
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import vela.app.infra.Settings
import vela.app.stage.AppPlaybackSession
import vela.app.stage.PlaybackSink
import vela.app.stage.StageViewModel
import vela.app.stage.answerHappy
import vela.app.stage.scriptHttp
import vela.core.base.model.FeedSource
import vela.core.base.play.StreamRendition
import java.nio.file.Files

/**
 * 丢焦点或申请失败只静音当前播放器时，stage.muted 仍为 false。
 * 之后起播会按这个运行时标志把音量加回来，等于没拿焦点也出声。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AudioFocusRuntimeMuteTest {
    @Test
    fun testFocusLossUpdatesRuntimeMuteButNotSettingsAndNextPlayStaysSilent() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences(Settings.PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        val settings = Settings(context)
        settings.ageConfirmed = true
        settings.hintSeen = true
        settings.muted = false
        settings.source = FeedSource.popular
        val directory = Files.createTempDirectory("vela-focus-loss")
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val audio = activity.getSystemService(AudioManager::class.java)
        shadowOf(audio).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        val http = scriptHttp { answerHappy(it) }
        val stage = StageViewModel(settings = settings, http = http, directory = directory, context = activity)
        val session = AppPlaybackSession(
            activity = activity,
            stage = stage,
            disposeComposition = {},
            restoreComposition = {},
        )
        stage.bindLifecycleHooks(
            ensurePlayback = {},
            onMuteChanged = { muted -> session.onMuteChanged(muted) },
        )
        try {
            session.prepareAudio()
            assertFalse(stage.settings.muted)
            val listener = shadowOf(audio).lastAudioFocusRequest.listener
            listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS)
            assertTrue("丢焦点后运行时仍是出声", stage.state.value.muted)
            assertFalse("丢焦点不应改写静音偏好", stage.settings.muted)

            val volumes = mutableListOf<Boolean>()
            stage.attachPlayback(volumeSink(volumes))
            stage.requestPlay()
            assertTrue("随后起播没有把音量交给播放器", volumes.isNotEmpty())
            assertTrue("没重新拿到焦点就把声音开回来了 muted=$volumes", volumes.last())
        } finally {
            stage.close()
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun testStoredUnmuteDeniedKeepsRuntimeMutedWithoutWritingSettings() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences(Settings.PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        val settings = Settings(context)
        settings.ageConfirmed = true
        settings.hintSeen = true
        settings.muted = false
        val directory = Files.createTempDirectory("vela-focus-deny")
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val audio = activity.getSystemService(AudioManager::class.java)
        shadowOf(audio).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
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
        try {
            session.prepareAudio()
            assertTrue("申请失败后运行时仍按保存的取消静音出声", stage.state.value.muted)
            assertFalse(stage.settings.muted)
        } finally {
            stage.close()
            directory.toFile().deleteRecursively()
        }
    }

    private fun volumeSink(volumes: MutableList<Boolean>): PlaybackSink = object : PlaybackSink {
        override val lastError: String? = null
        override val failed: Boolean = false
        override fun play(
            rendition: StreamRendition,
            username: String,
            peakBitrate: Double,
            allowed: Boolean,
            isRetry: Boolean,
        ) = Unit
        override fun stop() = Unit
        override fun setVolume(volume: Double, muted: Boolean) {
            volumes.add(muted)
        }
        override fun setAllowed(allowed: Boolean) = Unit
        override fun refreshFinished(reason: String?, report: Boolean) = Unit
        override fun setRefreshHandler(handler: ((String, Boolean) -> Unit)?) = Unit
        override fun setRenewHandler(handler: (suspend (String, Double) -> StreamRendition?)?) = Unit
    }
}
