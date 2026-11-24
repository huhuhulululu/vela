package vela.app.spike

import androidx.media3.test.utils.robolectric.ShadowMediaCodecConfig
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/**
 * 静音＝关掉音频轨。比较 allowChunklessPreparation 开与关时，稳态 60 秒里 audio.m3u8 和音频分片的请求。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class MutedAudioRequestTest {
    @get:Rule
    val codecs: ShadowMediaCodecConfig = ShadowMediaCodecConfig.withAllDefaultSupportedCodecs()

    @Test(timeout = 240_000)
    fun testMutedAudioRequestsWithAndWithoutChunklessPreparation() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val enabled = measure(context, chunkless = true)
        val disabled = measure(context, chunkless = false)
        SpikeReport.emit(
            "muted-audio",
            mapOf(
                "chunklessAudioPlaylists" to enabled.audioPlaylists,
                "chunklessAudioSegments" to enabled.audioSegments,
                "chunklessVideoPlaylists" to enabled.videoPlaylists,
                "prefetchAudioPlaylists" to disabled.audioPlaylists,
                "prefetchAudioSegments" to disabled.audioSegments,
                "prefetchVideoPlaylists" to disabled.videoPlaylists,
            ),
        )
        assertTrue(enabled.videoPlaylists > 0)
        assertTrue(disabled.videoPlaylists > 0)
    }

    private fun measure(context: android.content.Context, chunkless: Boolean): HitWindow {
        val upstream = FakeUpstream()
        val session = SpikeSession(
            context = context,
            upstream = upstream,
            stripLowLatency = true,
            allowChunkless = chunkless,
            muteAudio = true,
            mediaPath = upstream.masterPlaylistUrl(),
            targetOffsetMs = 3_000L,
        )
        try {
            assertTrue("chunkless=$chunkless 没有出画 ${session.stateLabel()}", session.awaitFirstFrame(20_000))
            val from = WallClock.millis()
            session.play(60_000)
            val window = upstream.window(from, WallClock.millis())
            println("SPIKE muted chunkless=$chunkless $window ${session.stateLabel()}")
            assertTrue("chunkless=$chunkless 没有画面", session.sampleNow().rendered > 0)
            return window
        } finally {
            session.close()
        }
    }
}
