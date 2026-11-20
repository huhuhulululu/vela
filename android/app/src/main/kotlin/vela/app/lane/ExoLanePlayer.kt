package vela.app.lane

import android.content.Context
import android.net.Uri
import android.view.Surface
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.BehindLiveWindowException
import vela.app.playback.LaneEngine
import vela.app.playback.LaneHub
import vela.app.playback.VelaLoadErrorPolicy
import vela.core.hls.FailureCode
import vela.core.hls.MuteAudioPolicy
import vela.core.hls.PlaybackFailureClassifier

/**
 * Media3 播放器。暂停走 [stop]：`playWhenReady = false` 仍会缓冲，停不掉网络。
 * 代次用来丢掉 stop 之后才投递到主线程的旧错误。
 */
internal class ExoLanePlayer(
    context: Context,
    private val hub: LaneHub,
) : LanePlayer {
    private val player: ExoPlayer = LaneEngine.configure(
        ExoPlayer.Builder(context.applicationContext),
    ).build()

    private var listener: LanePlayerListener? = null
    private var policy: VelaLoadErrorPolicy = VelaLoadErrorPolicy()
    private var epoch: Int = 0
    private var playingEpoch: Int = -1
    private var prepared: Boolean = false

    private val events = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (!current()) return
            listener?.onPlaying(isPlaying)
        }

        override fun onPlayerError(error: PlaybackException) {
            if (!current()) return
            // 慢机器解码跟不上直播边时会掉出窗口。这不是分片加载失败，单独交回去跳回直播边。
            if (isBehindLiveWindow(error)) {
                listener?.onBehindLiveWindow()
                return
            }
            val chain = listOf(
                FailureCode(PlaybackFailureClassifier.media3ErrorDomain, error.errorCode),
            )
            listener?.onPlaybackError(policy.isPlaylistReset(error), chain)
        }

        override fun onRenderedFirstFrame() {
            if (!current()) return
            listener?.onFirstFrame()
        }

        override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
            if (!current()) return
            // 主列表的 RESOLUTION 比解码出帧更早。先把窗格留对，
            // 否则要等首帧才有尺寸，而没有尺寸就没有 Surface。
            reportTrackVideoSize(tracks)
        }

        override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
            if (!current()) return
            if (videoSize.width <= 0 || videoSize.height <= 0) return
            listener?.onVideoSize(videoSize.width, videoSize.height)
        }
    }

    init {
        player.addListener(events)
        player.volume = 0f
        player.trackSelectionParameters = player.trackSelectionParameters
            .buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
            .build()
    }

    override val isPrepared: Boolean
        get() = prepared

    override fun setMedia(url: Uri, ownerId: String, policy: VelaLoadErrorPolicy, playWhenReady: Boolean) {
        this.policy = policy
        epoch += 1
        playingEpoch = epoch
        val source = LaneEngine.hlsSource(hub.factory(ownerId), LaneEngine.mediaItem(url), policy)
        player.stop()
        player.clearMediaItems()
        player.setMediaSource(source)
        player.prepare()
        player.playWhenReady = playWhenReady
        prepared = true
    }

    override fun setPlayWhenReady(play: Boolean) {
        player.playWhenReady = play
    }

    override fun stop() {
        epoch += 1
        playingEpoch = -1
        prepared = false
        player.stop()
        player.clearMediaItems()
    }

    override fun release() {
        stop()
        player.removeListener(events)
        player.release()
    }

    override fun setMuted(muted: Boolean, volume: Float) {
        player.volume = volume
        player.trackSelectionParameters = player.trackSelectionParameters
            .buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, muted)
            .build()
    }

    override fun attachSurface(surface: Surface?) {
        if (surface == null) player.clearVideoSurface() else player.setVideoSurface(surface)
    }

    override fun bufferedEndSeconds(): Double? {
        if (!prepared) return null
        val end = player.bufferedPosition
        if (end < 0L) return null
        return end / 1_000.0
    }

    override fun renderedOutputBufferCount(): Int =
        player.videoDecoderCounters?.renderedOutputBufferCount ?: 0

    override fun audioEnabledCount(): Int {
        var count = 0
        for (group in player.currentTracks.groups) {
            if (group.type != C.TRACK_TYPE_AUDIO) continue
            for (index in 0 until group.length) {
                if (group.isTrackSelected(index)) count += 1
            }
        }
        return count
    }

    override fun volume(): Float = player.volume

    override fun audibleGroup(): MuteAudioPolicy.AudibleGroupState {
        val groups = player.currentTracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
        if (groups.isEmpty()) return MuteAudioPolicy.AudibleGroupState.unknown
        var options = 0
        var selected: Int? = null
        for (group in groups) {
            for (index in 0 until group.length) {
                if (group.isTrackSelected(index)) selected = options
                options += 1
            }
        }
        return MuteAudioPolicy.AudibleGroupState(
            loaded = true,
            optionCount = options,
            selectedIndex = selected,
            allowsEmptySelection = true,
        )
    }

    override fun setListener(listener: LanePlayerListener?) {
        this.listener = listener
    }

    private fun isBehindLiveWindow(error: PlaybackException): Boolean {
        if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) return true
        val seen = HashSet<Throwable>()
        var cause = error.cause
        while (cause != null && seen.add(cause)) {
            if (cause is BehindLiveWindowException) return true
            cause = cause.cause
        }
        return false
    }

    private fun reportTrackVideoSize(tracks: androidx.media3.common.Tracks) {
        for (group in tracks.groups) {
            if (group.type != C.TRACK_TYPE_VIDEO) continue
            for (index in 0 until group.length) {
                if (!group.isTrackSelected(index)) continue
                val format = group.getTrackFormat(index)
                if (format.width > 0 && format.height > 0) {
                    listener?.onVideoSize(format.width, format.height)
                    return
                }
            }
        }
    }

    private fun current(): Boolean = playingEpoch >= 0 && playingEpoch == epoch
}
