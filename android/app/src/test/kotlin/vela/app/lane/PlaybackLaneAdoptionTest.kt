package vela.app.lane

import android.graphics.SurfaceTexture
import android.view.Surface
import androidx.media3.test.utils.robolectric.ShadowMediaCodecConfig
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.net.URI
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import vela.app.playback.LaneHub
import vela.app.spike.FakeUpstream
import vela.app.spike.WallClock
import vela.app.spike.WallClockPump
import vela.core.base.play.StreamRendition

/**
 * R1 接到真的 ExoPlayer 上：重置之后原地重新 prepare，不续期、不占静默重试，10 秒内再出画。
 * 时钟按墙钟走，和 P1 一样。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class PlaybackLaneAdoptionTest {
    @get:Rule
    val codecs: ShadowMediaCodecConfig = ShadowMediaCodecConfig.withAllDefaultSupportedCodecs()

    @Test(timeout = 90_000)
    fun testR1ReprepareReturnsPictureWithoutRenewOrRetry() {
        val upstream = FakeUpstream()
        val hub = LaneHub()
        check(hub.start())
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val lane = PlaybackLane.create(context, hub = hub)
        val surface = runCatching { Surface(SurfaceTexture(false)) }.getOrNull()
        val renews = AtomicInteger()
        val pump = WallClockPump()
        try {
            surface?.let { lane.attachSurface(it) }
            lane.renewRendition = { _, _ ->
                renews.incrementAndGet()
                null
            }
            lane.play(
                StreamRendition(video = URI.create(upstream.videoPlaylistUrl())),
                username = "synth",
                peakBitrate = Double.POSITIVE_INFINITY,
                allowed = true,
            )
            upstream.startPublishing()
            assertTrue(
                diagnose("起播", lane, renews),
                wait(pump, 20_000) { lane.renderedOutputBufferCount() > 0 },
            )
            pump.pump(1_000)
            val marked = lane.renderedOutputBufferCount()
            upstream.rewindWithDiscontinuity(1)
            val back = wait(pump, 10_000) {
                lane.reprepareCount in 1..2 &&
                    lane.renderedOutputBufferCount() > marked &&
                    !lane.failed
            }
            assertTrue(diagnose("R1 marked=$marked", lane, renews), back)
            assertTrue("reprepare=${lane.reprepareCount}", lane.reprepareCount in 1..2)
            assertFalse(lane.silentRetryUsed)
            assertFalse(lane.failed)
            check(renews.get() == 0) { "R1 不应续期 renews=${renews.get()}" }
        } finally {
            runCatching { lane.release() }
            hub.stopListener()
            upstream.close()
            runCatching { surface?.release() }
        }
    }

    private fun wait(pump: WallClockPump, timeoutMs: Long, condition: () -> Boolean): Boolean {
        val end = WallClock.nanos() + timeoutMs * 1_000_000L
        while (WallClock.nanos() < end) {
            pump.pump(200)
            if (condition()) return true
        }
        return condition()
    }

    private fun diagnose(stage: String, lane: PlaybackLane, renews: AtomicInteger): String =
        "$stage reprepare=${lane.reprepareCount} rendered=${lane.renderedOutputBufferCount()} " +
            "failed=${lane.failed} err=${lane.lastError} other=${lane.otherErrorCount} " +
            "reset=${lane.resetErrorCount} renews=${renews.get()} " +
            "log=${LaneLog.snapshot().takeLast(12)}"
}
