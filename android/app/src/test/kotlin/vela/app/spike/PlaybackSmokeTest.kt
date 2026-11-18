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
 * 先确认伪上游和 ExoPlayer 能在墙钟上出画面。后面的对比实验都依赖这条路径。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class PlaybackSmokeTest {
    @get:Rule
    val codecs: ShadowMediaCodecConfig = ShadowMediaCodecConfig.withAllDefaultSupportedCodecs()

    @Test(timeout = 60_000)
    fun testWallClockAdvancesAndFramesRender() {
        val before = WallClock.nanos()
        Thread.sleep(30)
        val after = WallClock.nanos()
        assertTrue("反射拿到的不是墙钟 before=$before after=$after", after - before > 20_000_000L)

        val upstream = FakeUpstream()
        val session = SpikeSession(
            context = ApplicationProvider.getApplicationContext(),
            upstream = upstream,
            stripLowLatency = true,
        )
        try {
            val rendered = session.awaitFirstFrame(15_000)
            session.play(3_000)
            val sample = session.sampleNow()
            val hits = upstream.snapshotHits()
            println(
                "SPIKE smoke rendered=$rendered sample=$sample hits=${hits.size} " +
                    "long=${hits.count { it.heldMs >= 200 }} state=${session.stateLabel()}",
            )
            assertTrue(session.stateLabel(), rendered)
            assertTrue("rendered=${sample.rendered}", sample.rendered > 0)
            assertTrue("hits=${hits.size}", hits.isNotEmpty())
        } finally {
            session.close()
        }
    }
}
