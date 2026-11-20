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
 * 403 分两条：默认加载错误策略实际走了哪条（回退轨道还是退避重试）；
 * 数据源里做受控续期（等 1.5 秒再换新响应）之后，播放器有没有 onPlayerError、画面停多久。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class LoadError403Test {
    @get:Rule
    val codecs: ShadowMediaCodecConfig = ShadowMediaCodecConfig.withAllDefaultSupportedCodecs()

    @Test(timeout = 90_000)
    fun testDefaultPolicyPathOn403() {
        val upstream = FakeUpstream()
        val session = SpikeSession(
            context = ApplicationProvider.getApplicationContext(),
            upstream = upstream,
            stripLowLatency = true,
            renewOn403 = false,
        )
        try {
            assertTrue(session.awaitFirstFrame(20_000))
            upstream.oneShotMedia403 = true
            session.play(8_000)
            val events = session.policy.events.toList()
            val sample = session.sampleNow()
            println("SPIKE 403-policy events=$events sample=$sample ${session.stateLabel()}")
            SpikeReport.emit(
                "403-policy",
                mapOf(
                    "events" to events.joinToString(" | "),
                    "playerErrors" to sample.playerErrors,
                    "rendered" to sample.rendered,
                    "fallback403" to events.count { it.startsWith("fallback") && it.contains("code=403") },
                    "retry403" to events.count { it.startsWith("retry") && it.contains("code=403") },
                    "fallbackType" to events.filter { it.startsWith("fallback") && it.contains("code=403") }
                        .joinToString(" | "),
                    "retryLine" to events.filter { it.startsWith("retry") && it.contains("code=403") }
                        .joinToString(" | "),
                ),
            )
            assertTrue("策略没有看到 403：$events", events.any { it.contains("code=403") })
        } finally {
            session.close()
        }
    }

    @Test(timeout = 90_000)
    fun testControlled403Renewal() {
        val upstream = FakeUpstream()
        val session = SpikeSession(
            context = ApplicationProvider.getApplicationContext(),
            upstream = upstream,
            stripLowLatency = true,
            renewOn403 = true,
            renewDelayMs = 1_500L,
            onRenew = {
                upstream.failPlaylistWith = null
                upstream.failSegmentWith = null
                upstream.retoken("renewed", forbidOld = false)
            },
        )
        try {
            assertTrue(session.awaitFirstFrame(20_000))
            upstream.failPlaylistWith = 403
            upstream.failSegmentWith = 403
            session.markGap()
            session.play(10_000)
            val sample = session.sampleNow()
            println("SPIKE 403-renewal $sample ${session.stateLabel()}")
            SpikeReport.emit(
                "403-renewal",
                mapOf(
                    "playerErrors" to sample.playerErrors,
                    "maxFrameGapMs" to sample.maxFrameGapMs,
                    "recoveryMs" to sample.recoveryMs,
                    "resumeAfterErrorMs" to sample.resumeAfterErrorMs,
                    "rendered" to sample.rendered,
                    "rebuffers" to sample.rebuffers,
                    "loadErrors" to sample.loadErrors.joinToString(" | "),
                ),
            )
            assertTrue("续期后没有画面 ${session.stateLabel()}", sample.rendered > 0)
        } finally {
            session.close()
        }
    }
}
