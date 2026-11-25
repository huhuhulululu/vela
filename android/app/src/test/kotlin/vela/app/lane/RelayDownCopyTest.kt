package vela.app.lane

import android.content.Context
import android.net.Uri
import android.view.Surface
import androidx.test.core.app.ApplicationProvider
import java.net.URI
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import vela.app.playback.LaneHub
import vela.app.playback.VelaLoadErrorPolicy
import vela.app.stage.StageText
import vela.core.base.lane.LaneID
import vela.core.base.play.StreamRendition
import vela.core.hls.MuteAudioPolicy

/**
 * 安卓没有本地中继套接字。登记失败的那句文案在资源、StageText 回退、
 * PlaybackLane.lastError 三处必须是同一句，且不再提「本地中继」。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RelayDownCopyTest {
    private var hub: LaneHub? = null
    private var lane: PlaybackLane? = null

    @After
    fun tearDown() {
        lane?.release()
        hub?.stopListener()
        LaneLog.clear()
    }

    @Test
    fun testFallbackCopyNoLongerClaimsLocalRelay() {
        val copy = StageText(null).relayDown()
        assertEquals(EXPECTED, copy)
        assertFalse(copy.contains("本地中继"))
    }

    @Test
    fun testResourceCopyMatchesFallback() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val copy = StageText(context).relayDown()
        assertEquals(EXPECTED, copy)
        assertEquals(StageText(null).relayDown(), copy)
        assertFalse(copy.contains("本地中继"))
    }

    @Test
    fun testLaneRegistrationFailureUsesSameCopy() {
        val copy = laneRegistrationError()
        assertEquals(EXPECTED, copy)
        assertEquals(StageText(null).relayDown(), copy)
        assertFalse(copy!!.contains("本地中继"))
    }

    private fun laneRegistrationError(): String? {
        val hub = LaneHub()
        assertTrue(hub.start())
        hub.allowStart = false
        val player = StopHookPlayer()
        player.onStop = { hub.allowStart = true }
        val lane = PlaybackLane(
            id = LaneID.PRIMARY,
            hub = hub,
            player = player,
            sleeper = Sleeper { _, _ -> Cancellable { } },
        )
        this.hub = hub
        this.lane = lane
        lane.play(
            StreamRendition(video = URI.create("http://127.0.0.1/live.m3u8")),
            username = "room-a",
            peakBitrate = 800_000.0,
            allowed = true,
        )
        assertTrue("登记失败要进失败态", lane.failed)
        return lane.lastError
    }

    private class StopHookPlayer : LanePlayer {
        var onStop: (() -> Unit)? = null

        override val isPrepared: Boolean = false

        override fun setMedia(url: Uri, ownerId: String, policy: VelaLoadErrorPolicy, playWhenReady: Boolean) = Unit

        override fun setPlayWhenReady(play: Boolean) = Unit

        override fun stop() {
            onStop?.invoke()
        }

        override fun release() = Unit

        override fun setMuted(muted: Boolean, volume: Float) = Unit

        override fun attachSurface(surface: Surface?) = Unit

        override fun bufferedEndSeconds(): Double? = 0.0

        override fun renderedOutputBufferCount(): Int = 0

        override fun audioEnabledCount(): Int = 0

        override fun volume(): Float = 0f

        override fun audibleGroup(): MuteAudioPolicy.AudibleGroupState = MuteAudioPolicy.AudibleGroupState.unknown

        override fun setListener(listener: LanePlayerListener?) = Unit
    }

    private companion object {
        const val EXPECTED = "画面源没能接上，暂时无法播放"
    }
}
