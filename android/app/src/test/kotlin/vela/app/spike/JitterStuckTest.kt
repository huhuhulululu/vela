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
 * 去标签的直播。分片发布间隔在 T 与 1.5×T 之间均匀抖动（T = 2 秒，与现网 TARGETDURATION 一致）。
 * 墙钟 10 分钟内数 PlaylistStuck 和会迫使重建的播放错误。不在测试里自动重建，避免把次数吃掉。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class JitterStuckTest {
    @get:Rule
    val codecs: ShadowMediaCodecConfig = ShadowMediaCodecConfig.withAllDefaultSupportedCodecs()

    @Test(timeout = 900_000)
    fun testJitterUpToOneAndHalfTargetForTenMinutes() {
        val upstream = FakeUpstream()
        upstream.jitterMaxFactor = 1.5
        val session = SpikeSession(
            context = ApplicationProvider.getApplicationContext(),
            upstream = upstream,
            stripLowLatency = true,
            targetOffsetMs = 3_000L,
            autoReprepare = false,
        )
        try {
            assertTrue(session.awaitFirstFrame(20_000))
            session.markGap()
            session.play(10 * 60_000L)
            val sample = session.sampleNow()
            println("SPIKE jitter10m $sample ${session.stateLabel()}")
            SpikeReport.emit(
                "jitter-10m",
                mapOf(
                    "playlistStuck" to sample.playlistStuck,
                    "playlistReset" to sample.playlistReset,
                    "playerErrors" to sample.playerErrors,
                    "behindLiveWindow" to sample.behindLiveWindow,
                    "rebuffers" to sample.rebuffers,
                    "maxFrameGapMs" to sample.maxFrameGapMs,
                    "rendered" to sample.rendered,
                    "loadErrors" to sample.loadErrors.distinct().joinToString(" | "),
                ),
            )
            assertTrue("10 分钟内没有画面", sample.rendered > 0)
        } finally {
            session.close()
        }
    }
}
