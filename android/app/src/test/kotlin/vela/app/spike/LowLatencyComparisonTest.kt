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
 * 同一类伪上游上对比：播放器看到原生低延迟标签，以及按 steadyPlaylist 去掉这些标签。
 * 测量窗口是出画之后的墙钟 60 秒。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class LowLatencyComparisonTest {
    @get:Rule
    val codecs: ShadowMediaCodecConfig = ShadowMediaCodecConfig.withAllDefaultSupportedCodecs()

    @Test(timeout = 240_000)
    fun testNativeLowLatencyVersusStrippedTags() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val native = measure(context, strip = false, label = "native-ll")
        val stripped = measure(context, strip = true, label = "stripped")
        SpikeReport.emit(
            "ll-vs-strip",
            mapOf(
                "nativePerMinute" to native.perMinute,
                "nativeRequests" to native.requests,
                "nativeLong" to native.longConnections,
                "nativeMaxConcurrent" to native.maxConcurrent,
                "nativeVideoPlaylists" to native.videoPlaylists,
                "nativeSegments" to native.mediaSegments,
                "strippedPerMinute" to stripped.perMinute,
                "strippedRequests" to stripped.requests,
                "strippedLong" to stripped.longConnections,
                "strippedMaxConcurrent" to stripped.maxConcurrent,
                "strippedVideoPlaylists" to stripped.videoPlaylists,
                "strippedSegments" to stripped.mediaSegments,
            ),
        )
        assertTrue(native.requests > 0)
        assertTrue(stripped.requests > 0)
    }

    private fun measure(context: android.content.Context, strip: Boolean, label: String): HitWindow {
        val upstream = FakeUpstream()
        val session = SpikeSession(
            context = context,
            upstream = upstream,
            stripLowLatency = strip,
            mediaPath = upstream.videoPlaylistUrl(),
            targetOffsetMs = 3_000L,
        )
        try {
            assertTrue("$label 没有出画 ${session.stateLabel()}", session.awaitFirstFrame(20_000))
            val from = WallClock.millis()
            session.play(60_000)
            val to = WallClock.millis()
            val window = upstream.window(from, to)
            val sample = session.sampleNow()
            println("SPIKE $label $window rendered=${sample.rendered} gap=${sample.maxFrameGapMs} ${session.stateLabel()}")
            assertTrue("$label 稳态没有画面", sample.rendered > 0)
            return window
        } finally {
            session.close()
        }
    }
}
