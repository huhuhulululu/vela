package vela.app.playback

import androidx.media3.test.utils.robolectric.ShadowMediaCodecConfig
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.net.URI
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import vela.app.spike.FakeUpstream
import vela.core.base.play.StreamRendition

/**
 * R1：序号回退且带 DISCONTINUITY 时，错误以可识别类型交给加载错误策略，不续期。
 * 原地重新 prepare 由 A3 断言。
 * R2：续期后序号不前进，不报错；序号再前进后继续出画。
 * R3：序号不变、最后分片地址变了（真实地址，不用合成序号）。10 秒内无重置、无续期，画面还在。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class LaneDataSourceAdoptionTest {
    @get:Rule
    val codecs: ShadowMediaCodecConfig = ShadowMediaCodecConfig.withAllDefaultSupportedCodecs()

    @Test(timeout = 90_000)
    fun r1() {
        val upstream = FakeUpstream()
        val renews = AtomicInteger()
        val hub = LaneHub()
        try {
            val handle = register(hub, upstream) {
                renews.incrementAndGet()
                null
            }
            val policy = VelaLoadErrorPolicy()
            val drive = PlaybackDrive(hub, handle, policy) { upstream.startPublishing() }
            try {
                assertTrue(drive.awaitFrame(20_000))
                drive.play(2_000)
                upstream.rewindWithDiscontinuity(1)
                drive.play(8_000)
                assertEquals(0, renews.get())
                val error = drive.player.playerError
                assertTrue(
                    "resets=${policy.recognizedResets.get()} chain=${error?.let { chain(it) }}",
                    policy.recognizedResets.get() > 0,
                )
                assertTrue(error != null && policy.isPlaylistReset(error))
            } finally {
                drive.close()
            }
        } finally {
            hub.stopListener()
            upstream.close()
        }
    }

    @Test(timeout = 90_000)
    fun r2() {
        val upstream = FakeUpstream()
        val renews = AtomicInteger()
        val hub = LaneHub()
        try {
            val rendition = StreamRendition(video = URI.create(upstream.videoPlaylistUrl()))
            val handle = register(hub, upstream) {
                renews.incrementAndGet()
                upstream.failPlaylistWith = null
                rendition
            }
            val policy = VelaLoadErrorPolicy()
            val drive = PlaybackDrive(hub, handle, policy) { upstream.startPublishing() }
            try {
                assertTrue(drive.awaitFrame(20_000))
                drive.play(2_000)
                val before = drive.rendered()
                upstream.publishHold = true
                upstream.failPlaylistWith = 403
                drive.play(4_000)
                assertTrue(renews.get() >= 1)
                assertNull(drive.player.playerError?.toString(), drive.player.playerError)
                upstream.publishHold = false
                drive.play(6_000)
                assertNull(drive.player.playerError?.toString(), drive.player.playerError)
                assertEquals(0, policy.recognizedResets.get())
                assertTrue("before=$before now=${drive.rendered()}", drive.rendered() > before)
            } finally {
                drive.close()
            }
        } finally {
            hub.stopListener()
            upstream.close()
        }
    }

    @Test(timeout = 90_000)
    fun r3() {
        val upstream = FakeUpstream()
        val renews = AtomicInteger()
        val hub = LaneHub()
        try {
            val handle = register(hub, upstream) {
                renews.incrementAndGet()
                null
            }
            val policy = VelaLoadErrorPolicy()
            val drive = PlaybackDrive(hub, handle, policy) { upstream.startPublishing() }
            try {
                assertTrue(drive.awaitFrame(20_000))
                drive.play(2_000)
                val before = drive.rendered()
                upstream.publishHold = true
                val rotated = upstream.rotateLastSegmentUri()
                assertNotNull(rotated)
                drive.play(2_000)
                upstream.publishHold = false
                drive.play(10_000)
                assertEquals(0, renews.get())
                assertEquals(0, policy.recognizedResets.get())
                assertNull(drive.player.playerError?.toString(), drive.player.playerError)
                assertTrue("before=$before now=${drive.rendered()}", drive.rendered() > before)
            } finally {
                drive.close()
            }
        } finally {
            hub.stopListener()
            upstream.close()
        }
    }

    private fun chain(error: Throwable): String {
        val parts = ArrayList<String>()
        val seen = HashSet<Throwable>()
        var current: Throwable? = error
        while (current != null && seen.add(current)) {
            parts.add(current.javaClass.name + ": " + current.message)
            current = current.cause
        }
        return parts.joinToString(" | ")
    }

    private fun register(
        hub: LaneHub,
        upstream: FakeUpstream,
        renew: (suspend () -> StreamRendition?)? = null,
    ): LaneHandle {
        check(hub.start())
        val rendition = StreamRendition(video = URI.create(upstream.videoPlaylistUrl()))
        return hub.localURL(rendition, Double.POSITIVE_INFINITY, false, renew) ?: error("没有登记")
    }
}
