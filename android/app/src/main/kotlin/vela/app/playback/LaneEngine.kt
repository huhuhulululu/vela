package vela.app.playback

import android.net.Uri
import android.os.Handler
import androidx.media3.common.MediaItem
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.upstream.BandwidthMeter
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy

/**
 * 第 1 节的播放器侧默认值。P1 的结论：直播目标偏移 3 秒；
 * BandwidthMeter 用固定估计，并且关掉平台诊断，避免拉起 `NetworkTypeObserver`。
 * 缓冲 min/max 6000 毫秒；起播与再缓冲用 Media3 默认的 1000 / 2000，否则过不了
 * `min >= bufferForPlayback` 的约束。优先时间阈值，和 P1 实测能出画的配置一致。
 */
object LaneEngine {
    const val MIN_BUFFER_MS: Int = 6_000
    const val MAX_BUFFER_MS: Int = 6_000
    const val BUFFER_FOR_PLAYBACK_MS: Int = 1_000
    const val BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS: Int = 2_000
    const val LIVE_TARGET_OFFSET_MS: Long = 3_000L
    const val FIXED_BITRATE: Long = 800_000L

    fun loadControl(): DefaultLoadControl =
        DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                MIN_BUFFER_MS,
                MAX_BUFFER_MS,
                BUFFER_FOR_PLAYBACK_MS,
                BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS,
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

    fun liveConfiguration(): MediaItem.LiveConfiguration =
        MediaItem.LiveConfiguration.Builder().setTargetOffsetMs(LIVE_TARGET_OFFSET_MS).build()

    fun mediaItem(uri: Uri): MediaItem =
        MediaItem.Builder().setUri(uri).setLiveConfiguration(liveConfiguration()).build()

    fun bandwidthMeter(): BandwidthMeter = FixedBitrateBandwidthMeter()

    fun configure(builder: ExoPlayer.Builder): ExoPlayer.Builder =
        builder
            .setLoadControl(loadControl())
            .setBandwidthMeter(bandwidthMeter())
            .setUsePlatformDiagnostics(false)

    fun hlsSource(
        factory: DataSource.Factory,
        item: MediaItem,
        policy: LoadErrorHandlingPolicy = VelaLoadErrorPolicy(),
    ): HlsMediaSource =
        HlsMediaSource.Factory(factory)
            .setLoadErrorHandlingPolicy(policy)
            .setAllowChunklessPreparation(true)
            .createMediaSource(item)
}

/** 固定码率，不注册网络监听。 */
internal class FixedBitrateBandwidthMeter(
    private val bitrate: Long = LaneEngine.FIXED_BITRATE,
) : BandwidthMeter {
    override fun getBitrateEstimate(): Long = bitrate

    override fun getTransferListener(): androidx.media3.datasource.TransferListener? = null

    override fun addEventListener(eventHandler: Handler, eventListener: BandwidthMeter.EventListener) = Unit

    override fun removeEventListener(eventListener: BandwidthMeter.EventListener) = Unit
}
