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
 * R1 序号回退加 DISCONTINUITY、R2 续期后序号不前进、R3 序号不变但最后一个分片地址变了。
 * 每种都在分片真实地址和分片合成地址下各跑一次。R1 模拟「原地重新 prepare」，最多两次。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class AdoptionScenarioTest {
    @get:Rule
    val codecs: ShadowMediaCodecConfig = ShadowMediaCodecConfig.withAllDefaultSupportedCodecs()

    @Test(timeout = 90_000)
    fun testR1RealSegmentUris() = r1(SegmentAddressMode.REAL)

    @Test(timeout = 90_000)
    fun testR1SyntheticSegmentUris() = r1(SegmentAddressMode.SYNTHETIC)

    @Test(timeout = 90_000)
    fun testR2RealSegmentUris() = r2(SegmentAddressMode.REAL)

    @Test(timeout = 90_000)
    fun testR2SyntheticSegmentUris() = r2(SegmentAddressMode.SYNTHETIC)

    @Test(timeout = 90_000)
    fun testR3RealSegmentUris() = r3(SegmentAddressMode.REAL)

    @Test(timeout = 90_000)
    fun testR3SyntheticSegmentUris() = r3(SegmentAddressMode.SYNTHETIC)

    private fun r1(mode: SegmentAddressMode) {
        val upstream = FakeUpstream()
        val session = open(upstream, mode, autoReprepare = true)
        try {
            assertTrue(session.awaitFirstFrame(20_000))
            session.play(3_000)
            session.markGap()
            upstream.rewindWithDiscontinuity(1)
            session.play(12_000)
            emit("R1", mode, session)
        } finally {
            session.close()
        }
    }

    private fun r2(mode: SegmentAddressMode) {
        val upstream = FakeUpstream()
        val session = open(upstream, mode, autoReprepare = false)
        try {
            assertTrue(session.awaitFirstFrame(20_000))
            session.play(3_000)
            session.markGap()
            upstream.publishHold = true
            upstream.retoken("after-renew", forbidOld = false)
            session.play(2_000)
            upstream.publishHold = false
            session.play(8_000)
            emit("R2", mode, session)
        } finally {
            session.close()
        }
    }

    private fun r3(mode: SegmentAddressMode) {
        val upstream = FakeUpstream()
        val session = open(upstream, mode, autoReprepare = false)
        try {
            assertTrue(session.awaitFirstFrame(20_000))
            session.play(3_000)
            session.markGap()
            upstream.publishHold = true
            val rotated = upstream.rotateLastSegmentUri()
            session.play(2_000)
            upstream.publishHold = false
            session.play(10_000)
            val sample = session.sampleNow()
            println("SPIKE R3 $mode rotated=$rotated $sample ${session.stateLabel()}")
            emit("R3", mode, session, extra = mapOf("rotated" to (rotated ?: "")))
        } finally {
            session.close()
        }
    }

    private fun open(upstream: FakeUpstream, mode: SegmentAddressMode, autoReprepare: Boolean): SpikeSession {
        return SpikeSession(
            context = ApplicationProvider.getApplicationContext(),
            upstream = upstream,
            stripLowLatency = true,
            segmentMode = mode,
            autoReprepare = autoReprepare,
            targetOffsetMs = 3_000L,
        )
    }

    private fun emit(
        scenario: String,
        mode: SegmentAddressMode,
        session: SpikeSession,
        extra: Map<String, Any?> = emptyMap(),
    ) {
        val sample = session.sampleNow()
        println("SPIKE $scenario $mode $sample ${session.stateLabel()}")
        assertTrue("$scenario $mode 没有出过画 ${session.stateLabel()}", sample.rendered > 0)
        SpikeReport.emit(
            "$scenario-${mode.name.lowercase()}",
            mapOf(
                "playerErrors" to sample.playerErrors,
                "playlistReset" to sample.playlistReset,
                "playlistStuck" to sample.playlistStuck,
                "behindLiveWindow" to sample.behindLiveWindow,
                "reprepares" to sample.reprepares,
                "recoveryMs" to sample.recoveryMs,
                "resumeAfterErrorMs" to sample.resumeAfterErrorMs,
                "maxFrameGapMs" to sample.maxFrameGapMs,
                "rebuffers" to sample.rebuffers,
                "rendered" to sample.rendered,
                "loadErrors" to sample.loadErrors.distinct().joinToString(" | "),
            ) + extra,
        )
    }
}
