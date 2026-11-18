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
 * 去标签之后的普通直播。缓冲 min/max 都是 6 秒。对比目标偏移 3 秒和播放器默认值（约 3×TARGETDURATION）的卡顿。
 * 发布间隔在 T 和 1.5×T 之间抖动，窗口为墙钟 60 秒。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class LiveOffsetStallTest {
    @get:Rule
    val codecs: ShadowMediaCodecConfig = ShadowMediaCodecConfig.withAllDefaultSupportedCodecs()

    @Test(timeout = 240_000)
    fun testTargetOffsetThreeSecondsVersusDefault() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val three = measure(context, 3_000L, "offset-3s")
        val fallback = measure(context, null, "offset-default")
        SpikeReport.emit(
            "live-offset",
            mapOf(
                "offset3Rebuffers" to three.rebuffers,
                "offset3MaxGapMs" to three.maxFrameGapMs,
                "offset3Errors" to three.playerErrors,
                "offset3Rendered" to three.rendered,
                "defaultRebuffers" to fallback.rebuffers,
                "defaultMaxGapMs" to fallback.maxFrameGapMs,
                "defaultErrors" to fallback.playerErrors,
                "defaultRendered" to fallback.rendered,
            ),
        )
        assertTrue(three.rendered > 0)
        assertTrue(fallback.rendered > 0)
    }

    private fun measure(
        context: android.content.Context,
        targetOffsetMs: Long?,
        label: String,
    ): SpikeSample {
        val upstream = FakeUpstream()
        upstream.jitterMaxFactor = 1.5
        val session = SpikeSession(
            context = context,
            upstream = upstream,
            stripLowLatency = true,
            targetOffsetMs = targetOffsetMs,
        )
        try {
            assertTrue("$label 没有出画 ${session.stateLabel()}", session.awaitFirstFrame(20_000))
            session.markGap()
            session.play(60_000)
            val sample = session.sampleNow()
            println("SPIKE $label $sample ${session.stateLabel()}")
            return sample
        } finally {
            session.close()
        }
    }
}
