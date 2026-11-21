package vela.app.lane

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.Surface
import androidx.annotation.VisibleForTesting
import androidx.media3.common.PlaybackException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Locale
import vela.app.infra.Probe
import vela.app.playback.LaneHub
import vela.app.playback.VelaLoadErrorPolicy
import vela.core.base.lane.LaneID
import vela.core.base.play.StreamRendition
import vela.core.hls.DisplayFit
import vela.core.hls.FailureCode
import vela.core.hls.MuteAudioPolicy
import vela.core.hls.PlaybackErrorCopy
import vela.core.hls.PlaybackFailureClassifier
import vela.core.hls.PlaybackPolicy
import vela.core.hls.RefreshTracker

/** 一次播放失败。系统原文不进这里，界面只用 [message]。 */
data class PlaybackFailureInfo(
    val stage: String,
    val username: String,
    val chain: List<FailureCode>,
    val message: String,
)

/**
 * 一路播放。
 *
 * 暂停调用播放器的 `stop()`，同时关掉这一路的清单刷新，做到零网络。
 * 会话还留着：恢复按 [PlaybackPolicy.resumeAction] 分档，
 * 不超过 15 秒在原源上重新 prepare，15 到 30 秒重建播放项，满 30 秒交给外面重新解析。
 *
 * R1（序号回退且带 DISCONTINUITY）原地重新 prepare，60 秒内最多 2 次，
 * 不占房间那一次静默重试，也不进失败界面。
 * 同一条 Lane 的 `lastInfo` 在抛出重置后仍记着旧序号，不换源的话每次重取都会再重置；
 * 所以这里换一条源来清掉这份记忆，档位地址不变。
 */
class PlaybackLane(
    val id: LaneID,
    private val hub: LaneHub,
    private val player: LanePlayer,
    private val clock: Clock = Clock.systemUTC(),
    private val sleeper: Sleeper = HandlerSleeper(),
    private val isRenewing: (String) -> Boolean = { source -> hub.isRenewing(source) },
) : LanePlayerListener {
    val laneLabel: String = id.label

    var refreshRequested: ((String, Boolean) -> Unit)? = null
    var noAudioDetected: ((String) -> Unit)? = null
    var failureFilter: ((PlaybackFailureInfo) -> PlaybackFailureClassifier.Outcome)? = null
    var resourceDegraded: ((PlaybackFailureInfo) -> Unit)? = null
    var renewRendition: (suspend (String, Double) -> StreamRendition?)? = null

    var probe: (String, Long) -> Unit = { laneName, at ->
        Probe.current.onRenderedFirstFrame(laneName, at)
    }

    /** 播放状态变化时通知舞台。代替前台轮询。 */
    var onPlaybackFace: (() -> Unit)? = null

    var readyUsername: String? = null
        private set
    var playingUsername: String? = null
        private set

    @Volatile
    var failed: Boolean = false
        private set

    @Volatile
    var lastError: String? = null
        private set

    var lastFailure: PlaybackFailureInfo? = null
        private set

    @Volatile
    var activeSourceId: String? = null
        private set

    @Volatile
    var reprepareCount: Int = 0
        private set

    @Volatile
    var resetErrorCount: Int = 0
        private set

    @Volatile
    var otherErrorCount: Int = 0
        private set

    @Volatile
    var firstFrameElapsedMs: Long = -1L
        private set

    var videoWidth: Int = 0
        private set
    var videoHeight: Int = 0
        private set
    var displaySize: DisplayFit.Size = DisplayFit.Size(0.0, 0.0)
        private set

    val refreshPending: Boolean
        get() = refresh.pending

    val silentRetryUsed: Boolean
        get() = retried

    private val loadPolicy = VelaLoadErrorPolicy()
    private val refresh = RefreshTracker()
    private val resetTimes = ArrayList<Long>()
    private val behindLiveTimes = ArrayList<Long>()
    private val sizeListeners = ArrayList<(Int, Int) -> Unit>()

    private var wantsMuted: Boolean = true
    private var allowed: Boolean = true
    private var sessionOpen: Boolean = false
    private var itemPlayed: Boolean = false
    private var isPlayingNow: Boolean = false
    private var retried: Boolean = false
    private var repreparing: Boolean = false
    private var suppressWatchdog: Boolean = false
    private var generation: Int = 0
    private var pausedAt: Instant? = null
    private var hiddenSince: Instant? = null
    private var lastPlay: LastPlay? = null
    private var currentUrl: Uri? = null
    private var stuckBaseEnd: Double? = null
    private var playStartedMs: Long = 0L
    private var firstFrameLogged: Boolean = true

    private var startup: Cancellable? = null
    private var stuck: Cancellable? = null
    private var releaseNap: Cancellable? = null
    private var audioNap: Cancellable? = null

    init {
        player.setListener(this)
        player.setMuted(muted = true, volume = 0f)
    }

    fun play(
        rendition: StreamRendition,
        username: String,
        peakBitrate: Double,
        allowed: Boolean,
        isRetry: Boolean = false,
    ) {
        this.allowed = allowed
        if (!isRetry || lastPlay?.username != username) retried = false
        start(rendition, username, peakBitrate, keepReady = false)
    }

    /** 这一路是否还挂着播放项。没有就不换档，下次起播带新上限。 */
    fun hasItem(): Boolean = sessionOpen && lastPlay != null

    /** 正在用的码率上限。还没播过为 null。 */
    fun currentPeak(): Double? = lastPlay?.peak

    /**
     * 按新上限原地换档。不换房间、不计失败。
     * 没有播放项或上限没变就不动，返回 false。
     */
    fun retierPeak(peakBitrate: Double): Boolean {
        val last = lastPlay ?: return false
        if (!sessionOpen) return false
        if (last.peak == peakBitrate) return false
        start(last.rendition, last.username, peakBitrate, keepReady = true)
        return true
    }

    fun setAllowed(value: Boolean) {
        allowed = value
        if (value) resumeIfNeeded() else pauseDecoding()
    }

    /** 窗口连续不可见的起点。可见时取消「停播且不可见满 30 秒才释放」。 */
    fun setHidden(hidden: Boolean) {
        if (hidden) {
            if (hiddenSince != null) return
            hiddenSince = clock.instant()
            schedulePausedRelease()
        } else {
            hiddenSince = null
            cancelRelease()
        }
    }

    fun setVolume(volume: Double, muted: Boolean) {
        wantsMuted = muted
        val level = if (muted) 0.0 else volume.coerceIn(0.0, 1.0)
        player.setMuted(muted, level.toFloat())
        val source = activeSourceId ?: return
        hub.setAudioSuppressed(MuteAudioPolicy.shouldSuppressAudioFetch(muted, null), source)
    }

    fun stop() {
        generation += 1
        cancelStartup()
        cancelStuck()
        cancelAudio()
        cancelRelease()
        dropSource()
        player.stop()
        sessionOpen = false
        itemPlayed = false
        isPlayingNow = false
        readyUsername = null
        playingUsername = null
        pausedAt = null
        refresh.clear()
        lastError = null
        lastFailure = null
        failed = false
        resetTimes.clear()
        behindLiveTimes.clear()
        repreparing = false
        suppressWatchdog = false
    }

    /** 立刻放掉播放项，不等 30 秒。`lastPlay` 留着，恢复时按暂停时长重新解析。 */
    fun suspendNow() {
        allowed = false
        if (pausedAt == null) pausedAt = clock.instant()
        cancelRelease()
        generation += 1
        cancelStartup()
        cancelStuck()
        cancelAudio()
        player.stop()
        dropSource()
        refresh.clear()
        sessionOpen = false
        itemPlayed = false
        isPlayingNow = false
        playingUsername = null
        readyUsername = null
        LaneLog.i("suspend-now lane=$laneLabel")
    }

    fun refreshFinished(reason: String?, report: Boolean) {
        val message = refresh.fail(reason)
        if (!report || message == null) return
        lastError = message
        failed = true
        syncRefreshing()
        publishFace()
    }

    fun attachSurface(surface: Surface?) {
        player.attachSurface(surface)
    }

    fun noteVideoRect(rect: DisplayFit.Rect) {
        displaySize = DisplayFit.Size(rect.width, rect.height)
    }

    fun addVideoSizeListener(listener: (Int, Int) -> Unit) {
        sizeListeners.add(listener)
    }

    fun removeVideoSizeListener(listener: (Int, Int) -> Unit) {
        sizeListeners.remove(listener)
    }

    fun currentVideoSize(): DisplayFit.Size = DisplayFit.Size(videoWidth.toDouble(), videoHeight.toDouble())

    /** 必须在创建播放器的那条线程上调用。 */
    fun renderedOutputBufferCount(): Int = player.renderedOutputBufferCount()

    /** 已选中的音轨数。必须在创建播放器的那条线程上调用。 */
    @VisibleForTesting
    fun audioEnabledCountForTest(): Int = player.audioEnabledCount()

    /** 当前音量。必须在创建播放器的那条线程上调用。 */
    @VisibleForTesting
    fun playerVolumeForTest(): Float = player.volume()

    fun release() {
        onPlaybackFace = null
        stop()
        player.release()
    }

    override fun onPlaying(playing: Boolean) {
        if (!sessionOpen) return
        isPlayingNow = playing
        if (playing) {
            val name = lastPlay?.username
            if (!itemPlayed && name != null) {
                LaneLog.i("playing-started")
                armAudio(name)
            }
            itemPlayed = true
            cancelStartup()
            cancelStuck()
            suppressWatchdog = false
            if (name != null) {
                readyUsername = name
                playingUsername = name
            }
            failed = false
            lastError = null
        } else {
            playingUsername = null
            armStuck()
        }
        publishFace()
    }

    private fun publishFace() {
        onPlaybackFace?.invoke()
    }

    override fun onBehindLiveWindow() {
        if (!sessionOpen || !allowed) return
        val now = clock.instant().toEpochMilli()
        behindLiveTimes.removeAll { now - it >= BEHIND_LIVE_WINDOW_MS }
        if (behindLiveTimes.size >= BEHIND_LIVE_BUDGET) {
            onPlaybackError(
                reset = false,
                chain = listOf(
                    FailureCode(
                        PlaybackFailureClassifier.media3ErrorDomain,
                        PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW,
                    ),
                ),
            )
            return
        }
        behindLiveTimes.add(now)
        val last = lastPlay ?: return
        LaneLog.i("behind-live rejoin")
        // 用当前地址重新 prepare，从直播边接着播。不计其它错误，也不算序号回退。
        start(last.rendition, last.username, last.peak, keepReady = true)
    }

    override fun onPlaybackError(reset: Boolean, chain: List<FailureCode>) {
        if (!sessionOpen || !allowed) return
        if (reset) {
            handleReset()
            return
        }
        otherErrorCount += 1
        val name = lastPlay?.username ?: return
        val message = PlaybackErrorCopy.failureMessage(chain)
        val info = PlaybackFailureInfo(stage = "item-failed", username = name, chain = chain, message = message)
        when (failureFilter?.invoke(info) ?: PlaybackFailureClassifier.Outcome.ROOM_RETRY) {
            PlaybackFailureClassifier.Outcome.ROOM_RETRY ->
                itemFailed(name, chain, message, allowRetry = true)
            PlaybackFailureClassifier.Outcome.FATAL ->
                itemFailed(name, chain, message, allowRetry = false)
            PlaybackFailureClassifier.Outcome.RESOURCE_DEGRADE -> {
                LaneLog.i("item-degrade")
                lastFailure = info
                resourceDegraded?.invoke(info)
            }
        }
    }

    override fun onFirstFrame() {
        if (firstFrameLogged) return
        firstFrameLogged = true
        val elapsed = clock.instant().toEpochMilli() - playStartedMs
        firstFrameElapsedMs = elapsed
        LaneLog.i(
            "first-frame lane=$laneLabel ${formatSeconds(elapsed / 1_000.0)}s size=${videoWidth}x${videoHeight}",
        )
        probe(laneLabel, clock.instant().toEpochMilli())
    }

    override fun onVideoSize(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        videoWidth = width
        videoHeight = height
        val listeners = sizeListeners.toList()
        for (listener in listeners) listener(width, height)
    }

    private fun start(rendition: StreamRendition, username: String, peak: Double, keepReady: Boolean) {
        cancelRelease()
        lastPlay = LastPlay(rendition, username, peak)
        generation += 1
        failed = false
        lastError = null
        lastFailure = null
        if (!keepReady) readyUsername = null
        playingUsername = null
        isPlayingNow = false
        itemPlayed = false
        suppressWatchdog = false
        dropSource()
        val suppressed = MuteAudioPolicy.shouldSuppressAudioFetch(wantsMuted, null)
        if (hub.listeningPort == 0) hub.start()
        val handle = hub.localURL(rendition, peak, suppressed) { renewCurrent() }
        if (handle == null) {
            player.stop()
            sessionOpen = false
            if (!hub.allowStart) return
            lastError = "画面源没能接上，暂时无法播放"
            failed = true
            publishFace()
            return
        }
        activeSourceId = handle.id
        currentUrl = handle.url
        cancelStuck()
        cancelStartup()
        cancelAudio()
        beginFirstFrame()
        pausedAt = null
        refresh.clear()
        sessionOpen = true
        if (allowed) {
            player.setMedia(handle.url, handle.id, loadPolicy, playWhenReady = true)
            syncRefreshing()
            armStartup()
        } else {
            player.stop()
            syncRefreshing()
            pausedAt = clock.instant()
            schedulePausedRelease()
        }
    }

    private fun pauseDecoding() {
        cancelStartup()
        cancelStuck()
        cancelAudio()
        isPlayingNow = false
        playingUsername = null
        syncRefreshing()
        if (!sessionOpen) return
        if (pausedAt == null) pausedAt = clock.instant()
        player.stop()
        schedulePausedRelease()
    }

    private fun resumeIfNeeded() {
        cancelRelease()
        if (refresh.pending) return
        if (!sessionOpen) {
            val since = pausedAt ?: return
            pausedAt = null
            reresolve(secondsBetween(since, clock.instant()))
            return
        }
        val since = pausedAt
        if (since == null) {
            syncRefreshing()
            if (!isPlayingNow) prepareCurrent(play = true)
            if (!itemPlayed && startup == null) armStartup()
            return
        }
        pausedAt = null
        val pausedFor = secondsBetween(since, clock.instant())
        when (PlaybackPolicy.resumeAction(pausedFor)) {
            PlaybackPolicy.ResumeAction.RESUME -> {
                LaneLog.i("resume-live continue after ${pausedFor.toInt()}s")
                syncRefreshing()
                prepareCurrent(play = true)
                if (!itemPlayed) armStartup()
            }
            PlaybackPolicy.ResumeAction.REJOIN -> {
                LaneLog.i("resume-live rebuild after ${pausedFor.toInt()}s")
                val last = lastPlay ?: return
                start(last.rendition, last.username, last.peak, keepReady = true)
            }
            PlaybackPolicy.ResumeAction.RERESOLVE -> reresolve(pausedFor)
        }
    }

    private fun reresolve(pausedFor: Double) {
        LaneLog.i("resume-live re-resolve after ${pausedFor.toInt()}s")
        val last = lastPlay
        val callback = refreshRequested
        if (last != null && callback != null) {
            cancelStartup()
            cancelStuck()
            player.stop()
            isPlayingNow = false
            activeSourceId?.let { hub.setRefreshing(false, it) }
            refresh.begin()
            callback.invoke(last.username, false)
        } else if (last != null) {
            start(last.rendition, last.username, last.peak, keepReady = true)
        }
    }

    private fun prepareCurrent(play: Boolean) {
        val url = currentUrl
        val source = activeSourceId
        if (url == null || source == null) return
        if (player.isPrepared) {
            player.setPlayWhenReady(play)
        } else {
            beginFirstFrame()
            player.setMedia(url, source, loadPolicy, playWhenReady = play)
        }
    }

    private fun handleReset() {
        if (repreparing) return
        val now = clock.instant().toEpochMilli()
        resetTimes.removeAll { now - it >= REPREPARE_WINDOW_MS }
        if (resetTimes.size >= REPREPARE_BUDGET) {
            LaneLog.i("playlist-reset budget")
            suppressWatchdog = true
            player.stop()
            dropSource()
            isPlayingNow = false
            return
        }
        resetTimes.add(now)
        reprepareCount += 1
        resetErrorCount += 1
        LaneLog.i("playlist-reset reprepare count=$reprepareCount")
        reprepareInPlace()
    }

    private fun reprepareInPlace() {
        val last = lastPlay ?: return
        val play = allowed && !refresh.pending
        repreparing = true
        try {
            generation += 1
            cancelStartup()
            cancelStuck()
            cancelAudio()
            dropSource()
            val suppressed = MuteAudioPolicy.shouldSuppressAudioFetch(wantsMuted, null)
            if (hub.listeningPort == 0) hub.start()
            val handle = hub.localURL(last.rendition, last.peak, suppressed) { renewCurrent() }
            if (handle == null) {
                player.stop()
                return
            }
            activeSourceId = handle.id
            currentUrl = handle.url
            sessionOpen = true
            beginFirstFrame()
            player.setMedia(handle.url, handle.id, loadPolicy, playWhenReady = play)
            syncRefreshing()
            if (play && !itemPlayed) armStartup()
        } finally {
            repreparing = false
        }
    }

    private fun itemFailed(name: String, chain: List<FailureCode>, message: String, allowRetry: Boolean) {
        if (allowRetry && !retried && lastPlay?.username == name) {
            LaneLog.i("retry after item failure")
            retryWithFreshAddress(name)
            return
        }
        lastFailure = PlaybackFailureInfo("item-failed", name, chain, message)
        lastError = message
        failed = true
        syncRefreshing()
        publishFace()
    }

    private fun retryWithFreshAddress(username: String) {
        val last = lastPlay ?: return
        if (last.username != username) return
        retried = true
        cancelStartup()
        cancelStuck()
        activeSourceId?.let { hub.setRefreshing(false, it) }
        val callback = refreshRequested
        if (callback != null) {
            refresh.begin()
            callback.invoke(username, true)
        } else {
            start(last.rendition, last.username, last.peak, keepReady = false)
        }
    }

    private fun armStartup() {
        cancelStartup()
        val token = generation
        startup = sleeper.sleep(PlaybackPolicy.startupTimeout) {
            startup = null
            onStartup(token)
        }
    }

    private fun onStartup(token: Int) {
        val stalled = PlaybackPolicy.startupStalled(
            sameItem = token == generation,
            allowed = allowed,
            itemPlayed = itemPlayed,
            isPlaying = isPlayingNow,
            refreshing = refresh.pending,
        )
        if (!stalled) return
        val chain = emptyList<FailureCode>()
        val message = PlaybackErrorCopy.startTimeoutMessage(chain)
        val name = lastPlay?.username ?: ""
        lastFailure = PlaybackFailureInfo("start-timeout", name, chain, message)
        lastError = message
        failed = true
        syncRefreshing()
        publishFace()
    }

    private fun armStuck() {
        if (stuck != null || suppressWatchdog || repreparing) return
        if (!itemPlayed || !allowed || refresh.pending) return
        val token = generation
        stuckBaseEnd = player.bufferedEndSeconds()
        stuck = sleeper.sleep(PlaybackPolicy.stuckAfter) {
            stuck = null
            onStuck(token)
        }
    }

    private fun onStuck(token: Int) {
        val name = lastPlay?.username ?: return
        val action = PlaybackPolicy.stuckAction(
            sameItem = token == generation,
            allowed = allowed,
            itemPlayed = itemPlayed,
            isPlaying = isPlayingNow,
            refreshing = refresh.pending,
            retryAvailable = !retried,
            bufferAdvancing = PlaybackPolicy.bufferAdvanced(stuckBaseEnd, player.bufferedEndSeconds()),
            renewing = activeSourceId?.let { isRenewing(it) } ?: false,
        )
        when (action) {
            PlaybackPolicy.StuckAction.IGNORE -> Unit
            PlaybackPolicy.StuckAction.REARM -> {
                LaneLog.i("stuck-watchdog rearm: still progressing")
                armStuck()
            }
            PlaybackPolicy.StuckAction.RECOVER -> {
                LaneLog.i("stuck-watchdog recover after ${PlaybackPolicy.stuckAfter.toInt()}s")
                retryWithFreshAddress(name)
            }
            PlaybackPolicy.StuckAction.GIVE_UP -> {
                val chain = emptyList<FailureCode>()
                val message = PlaybackErrorCopy.failureMessage(chain)
                lastFailure = PlaybackFailureInfo("stuck-giveup", name, chain, message)
                lastError = message
                failed = true
                syncRefreshing()
                publishFace()
            }
        }
    }

    private fun armAudio(username: String) {
        cancelAudio()
        var delay = PlaybackPolicy.audioCheckDelay
        var recheck = false
        fun schedule() {
            audioNap = sleeper.sleep(delay) {
                audioNap = null
                if (!sessionOpen) return@sleep
                val report = MuteAudioPolicy.audioReport(
                    muted = wantsMuted,
                    isRecheck = recheck,
                    enabledTrackCount = player.audioEnabledCount(),
                    group = player.audibleGroup(),
                )
                when (report) {
                    is MuteAudioPolicy.AudioReport.Ok -> Unit
                    MuteAudioPolicy.AudioReport.Recheck -> {
                        recheck = true
                        delay = PlaybackPolicy.audioRecheckDelay
                        schedule()
                    }
                    MuteAudioPolicy.AudioReport.Missing -> {
                        if (wantsMuted) return@sleep
                        noAudioDetected?.invoke(username)
                    }
                }
            }
        }
        schedule()
    }

    private fun schedulePausedRelease() {
        cancelRelease()
        if (allowed || !sessionOpen) return
        val paused = pausedAt ?: return
        val hidden = hiddenSince ?: return
        val now = clock.instant()
        val pausedFor = secondsBetween(paused, now)
        val hiddenFor = secondsBetween(hidden, now)
        if (PlaybackPolicy.shouldReleasePausedItem(pausedFor, hiddenFor)) {
            releasePausedSource(pausedFor)
            return
        }
        val delay = PlaybackPolicy.releasePausedItemAfter - minOf(pausedFor, hiddenFor)
        releaseNap = sleeper.sleep(delay) {
            releaseNap = null
            schedulePausedRelease()
        }
    }

    private fun releasePausedSource(pausedFor: Double) {
        if (!sessionOpen || allowed) return
        LaneLog.i("paused-release after ${pausedFor.toInt()}s")
        generation += 1
        cancelStartup()
        cancelStuck()
        cancelAudio()
        player.stop()
        dropSource()
        sessionOpen = false
        itemPlayed = false
        isPlayingNow = false
        playingUsername = null
        readyUsername = null
    }

    private fun syncRefreshing() {
        val source = activeSourceId ?: return
        hub.setRefreshing(PlaybackPolicy.relayShouldRefresh(allowed, failed), source)
    }

    private fun dropSource() {
        val source = activeSourceId ?: return
        activeSourceId = null
        currentUrl = null
        hub.deactivate(source)
    }

    private suspend fun renewCurrent(): StreamRendition? {
        val play = lastPlay ?: return null
        val fresh = renewRendition?.invoke(play.username, play.peak) ?: return null
        if (fresh.isUnselected) return null
        lastPlay = play.copy(rendition = fresh)
        return fresh
    }

    private fun beginFirstFrame() {
        firstFrameLogged = false
        playStartedMs = clock.instant().toEpochMilli()
    }

    private fun cancelStartup() {
        startup?.cancel()
        startup = null
    }

    private fun cancelStuck() {
        stuck?.cancel()
        stuck = null
        stuckBaseEnd = null
    }

    private fun cancelAudio() {
        audioNap?.cancel()
        audioNap = null
    }

    private fun cancelRelease() {
        releaseNap?.cancel()
        releaseNap = null
    }

    private fun secondsBetween(start: Instant, end: Instant): Double {
        val nanos = Duration.between(start, end).toNanos()
        return nanos / 1_000_000_000.0
    }

    private data class LastPlay(
        val rendition: StreamRendition,
        val username: String,
        val peak: Double,
    )

    companion object {
        const val REPREPARE_BUDGET: Int = 2
        const val REPREPARE_WINDOW_MS: Long = 60_000L
        const val BEHIND_LIVE_BUDGET: Int = 2
        const val BEHIND_LIVE_WINDOW_MS: Long = 30_000L

        val sharedHub: LaneHub by lazy { LaneHub().also { it.start() } }

        fun create(
            context: Context,
            id: LaneID = LaneID.PRIMARY,
            hub: LaneHub = sharedHub,
            clock: Clock = Clock.systemUTC(),
        ): PlaybackLane {
            if (hub.listeningPort == 0) hub.start()
            return PlaybackLane(
                id = id,
                hub = hub,
                player = ExoLanePlayer(context, hub),
                clock = clock,
            )
        }
    }
}

internal class HandlerSleeper : Sleeper {
    private val handler = Handler(Looper.getMainLooper())

    override fun sleep(seconds: Double, onDone: () -> Unit): Cancellable {
        val ms = (seconds * 1_000.0).toLong().coerceAtLeast(0L)
        val task = Runnable { onDone() }
        handler.postDelayed(task, ms)
        return Cancellable { handler.removeCallbacks(task) }
    }
}

internal fun formatSeconds(seconds: Double): String = String.format(Locale.US, "%.2f", seconds)
