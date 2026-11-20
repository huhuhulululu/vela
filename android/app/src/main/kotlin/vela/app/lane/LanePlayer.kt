package vela.app.lane

import android.net.Uri
import android.view.Surface
import androidx.annotation.VisibleForTesting
import vela.app.playback.VelaLoadErrorPolicy
import vela.core.hls.FailureCode
import vela.core.hls.MuteAudioPolicy

/** 可取消的一次等待。取消之后回调不得再进来。 */
fun interface Cancellable {
    fun cancel()
}

/** 看门狗和音轨复查的等待。测试注入手动实现，免得干等 10 秒、16 秒。 */
fun interface Sleeper {
    fun sleep(seconds: Double, onDone: () -> Unit): Cancellable
}

interface LanePlayerListener {
    fun onPlaying(playing: Boolean)
    fun onPlaybackError(reset: Boolean, chain: List<FailureCode>)

    /** 播放点落到直播窗口外面。不是加载失败，也不要当成序号回退。 */
    fun onBehindLiveWindow()
    fun onFirstFrame()
    fun onVideoSize(width: Int, height: Int)
}

/**
 * 播放器给这一路用的窄接口。正式实现是 [ExoLanePlayer]；
 * 策略测试用假的，这样 15 秒、30 秒分档不必真的等。
 */
interface LanePlayer {
    val isPrepared: Boolean

    fun setMedia(url: Uri, ownerId: String, policy: VelaLoadErrorPolicy, playWhenReady: Boolean)

    fun setPlayWhenReady(play: Boolean)

    /** 停掉加载。暂停时靠它把播放器侧的网络清掉，媒体项可以不留。 */
    fun stop()

    fun release()

    fun setMuted(muted: Boolean, volume: Float)

    fun attachSurface(surface: Surface?)

    fun bufferedEndSeconds(): Double?

    fun renderedOutputBufferCount(): Int

    fun audioEnabledCount(): Int

    /** 仪器测试读 ExoPlayer 音量，须在应用主线程调用。 */
    @VisibleForTesting
    fun playbackVolumeForTest(): Float

    fun audibleGroup(): MuteAudioPolicy.AudibleGroupState

    fun setListener(listener: LanePlayerListener?)
}
