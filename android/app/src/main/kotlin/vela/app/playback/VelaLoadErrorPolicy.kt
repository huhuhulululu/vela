package vela.app.playback

import androidx.media3.common.C
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistTracker
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import java.util.concurrent.atomic.AtomicInteger

/**
 * 只有一档：403 不排除轨道，临时错误按默认策略退避重试。
 * 序号回退产生的 [HlsPlaylistTracker.PlaylistResetException] 不重试、不续期，
 * 交给播放器（A3 原地重新 prepare）。
 */
class VelaLoadErrorPolicy(
    private val delegate: LoadErrorHandlingPolicy = DefaultLoadErrorHandlingPolicy(),
) : LoadErrorHandlingPolicy {
    /** 加载错误策略见到过的播放列表重置次数。 */
    val recognizedResets: AtomicInteger = AtomicInteger()

    override fun getFallbackSelectionFor(
        fallbackOptions: LoadErrorHandlingPolicy.FallbackOptions,
        loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo,
    ): LoadErrorHandlingPolicy.FallbackSelection? = null

    override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
        if (isPlaylistReset(loadErrorInfo.exception)) {
            recognizedResets.incrementAndGet()
            return C.TIME_UNSET
        }
        return delegate.getRetryDelayMsFor(loadErrorInfo)
    }

    override fun getMinimumLoadableRetryCount(dataType: Int): Int =
        delegate.getMinimumLoadableRetryCount(dataType)

    override fun onLoadTaskConcluded(loadTaskId: Long) {
        delegate.onLoadTaskConcluded(loadTaskId)
    }

    fun isPlaylistReset(error: Throwable): Boolean {
        val seen = HashSet<Throwable>()
        var current: Throwable? = error
        while (current != null && seen.add(current)) {
            if (current is HlsPlaylistTracker.PlaylistResetException) return true
            current = current.cause
        }
        return false
    }
}
