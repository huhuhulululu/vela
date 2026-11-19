package vela.app.playback

import android.graphics.SurfaceTexture
import android.view.Surface
import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ApplicationProvider
import vela.app.spike.WallClock
import vela.app.spike.WallClockPump

/** 用第 1 节的默认缓冲、偏移和数据源把一路跑起来。时钟按墙钟推进。 */
internal class PlaybackDrive(
    hub: LaneHub,
    handle: LaneHandle,
    val policy: VelaLoadErrorPolicy,
    startUpstream: () -> Unit,
) : AutoCloseable {
    val pump = WallClockPump()
    private val surface = runCatching { Surface(SurfaceTexture(false)) }.getOrNull()
    val player: ExoPlayer

    init {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        player = LaneEngine.configure(ExoPlayer.Builder(context)).build()
        surface?.let { player.setVideoSurface(it) }
        player.trackSelectionParameters = player.trackSelectionParameters
            .buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
            .build()
        player.setMediaSource(
            LaneEngine.hlsSource(hub.factory(handle.id), LaneEngine.mediaItem(handle.url), policy),
        )
        player.prepare()
        player.playWhenReady = true
        startUpstream()
    }

    fun awaitFrame(timeoutMs: Long): Boolean {
        val end = WallClock.millis() + timeoutMs
        while (WallClock.millis() < end) {
            pump.pump(100)
            if (rendered() > 0) return true
            if (player.playerError != null) return false
        }
        return rendered() > 0
    }

    fun play(durationMs: Long) {
        pump.pump(durationMs)
    }

    fun rendered(): Int = player.videoDecoderCounters?.renderedOutputBufferCount ?: 0

    override fun close() {
        runCatching { player.stop() }
        pump.pump(200)
        runCatching { player.release() }
        runCatching { surface?.release() }
    }
}
