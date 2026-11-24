@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package vela.app.spike

import android.content.Context
import android.graphics.SurfaceTexture
import android.view.Surface
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistTracker
import androidx.media3.exoplayer.source.BehindLiveWindowException
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.exoplayer.upstream.BandwidthMeter
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList

internal class FixedBandwidthMeter(
    private val bitrate: Long = 800_000L,
) : BandwidthMeter {
    override fun getBitrateEstimate(): Long = bitrate

    override fun getTransferListener(): androidx.media3.datasource.TransferListener? = null

    override fun addEventListener(eventHandler: android.os.Handler, eventListener: BandwidthMeter.EventListener) = Unit

    override fun removeEventListener(eventListener: BandwidthMeter.EventListener) = Unit
}

@OptIn(UnstableApi::class)
internal class RecordingLoadErrorPolicy(
    private val delegate: LoadErrorHandlingPolicy = DefaultLoadErrorHandlingPolicy(),
) : LoadErrorHandlingPolicy {
    val events = CopyOnWriteArrayList<String>()

    override fun getFallbackSelectionFor(
        fallbackOptions: LoadErrorHandlingPolicy.FallbackOptions,
        loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo,
    ): LoadErrorHandlingPolicy.FallbackSelection? {
        val selection = delegate.getFallbackSelectionFor(fallbackOptions, loadErrorInfo)
        events.add(
            "fallback code=${httpCode(loadErrorInfo.exception)} " +
                "locations=${fallbackOptions.numberOfLocations} tracks=${fallbackOptions.numberOfTracks} " +
                "type=${selection?.type ?: "none"} delay=${selection?.exclusionDurationMs ?: -1}",
        )
        return selection
    }

    override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
        val delay = delegate.getRetryDelayMsFor(loadErrorInfo)
        events.add(
            "retry code=${httpCode(loadErrorInfo.exception)} count=${loadErrorInfo.errorCount} " +
                "delayMs=$delay dataType=${loadErrorInfo.mediaLoadData.dataType}",
        )
        return delay
    }

    override fun getMinimumLoadableRetryCount(dataType: Int): Int = delegate.getMinimumLoadableRetryCount(dataType)

    override fun onLoadTaskConcluded(loadTaskId: Long) {
        delegate.onLoadTaskConcluded(loadTaskId)
    }

    private fun httpCode(error: IOException): Int {
        val invalid = error.causes().filterIsInstance<androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException>().firstOrNull()
        return invalid?.responseCode ?: -1
    }
}

internal data class SpikeSample(
    val rendered: Int,
    val maxFrameGapMs: Long,
    val openGapMs: Long,
    val rebuffers: Int,
    val playerErrors: Int,
    val playlistStuck: Int,
    val playlistReset: Int,
    val behindLiveWindow: Int,
    val firstFrameMs: Long,
    val reprepares: Int,
    val recoveryMs: Long,
    val resumeAfterErrorMs: Long,
    val loadErrors: List<String>,
)

@OptIn(UnstableApi::class)
internal class SpikeSession(
    context: Context,
    val upstream: FakeUpstream,
    private val stripLowLatency: Boolean,
    private val segmentMode: SegmentAddressMode = SegmentAddressMode.REAL,
    private val targetOffsetMs: Long? = 3_000L,
    private val allowChunkless: Boolean = true,
    private val muteAudio: Boolean = true,
    private val renewOn403: Boolean = false,
    private val renewDelayMs: Long = 0L,
    private val onRenew: (() -> Unit)? = null,
    private val autoReprepare: Boolean = false,
    private val usePlatformDiagnostics: Boolean = true,
    private val bandwidthMeter: BandwidthMeter = FixedBandwidthMeter(),
    private val mediaPath: String = "video.m3u8",
) : AutoCloseable {
    val factory = SpikeSourceFactory(
        upstream = upstream,
        segmentMode = segmentMode,
        stripLowLatency = stripLowLatency,
        renewOn403 = renewOn403,
        renewDelayMs = renewDelayMs,
        onRenew = onRenew,
    )
    val policy = RecordingLoadErrorPolicy()
    val pump = WallClockPump()
    private val surface = runCatching { Surface(SurfaceTexture(false)) }.getOrNull()
    private val item: MediaItem
    private val player: ExoPlayer
    private var rendered = 0
    private var maxFrameGapMs = 0L
    private var openGapStarted = WallClock.millis()
    private var sawFrame = false
    private var rebuffers = 0
    private var seenReady = false
    private var playerErrors = 0
    private var playlistStuck = 0
    private var playlistReset = 0
    private var behindLiveWindow = 0
    private var firstFrameMs = -1L
    private var startedMs = 0L
    private var reprepares = 0
    private val loadErrors = ArrayList<String>()
    private var sampling = false
    private var markRendered = -1
    private var markMs = 0L
    private var recoveredMs = -1L
    private var errorMarkedMs = -1L
    private var resumeAfterErrorMs = -1L

    val mediaUri: String = if (mediaPath.startsWith("http")) mediaPath else "vela://lane/a/$mediaPath"

    init {
        val builder = MediaItem.Builder().setUri(mediaUri)
        if (targetOffsetMs != null) {
            builder.setLiveConfiguration(
                MediaItem.LiveConfiguration.Builder().setTargetOffsetMs(targetOffsetMs).build(),
            )
        }
        item = builder.build()
        player = ExoPlayer.Builder(context)
            .setLoadControl(
                DefaultLoadControl.Builder()
                    .setBufferDurationsMs(6_000, 6_000, 1_000, 2_000)
                    .setPrioritizeTimeOverSizeThresholds(true)
                    .build(),
            )
            .setBandwidthMeter(bandwidthMeter)
            .setUsePlatformDiagnostics(usePlatformDiagnostics)
            .build()
        surface?.let { player.setVideoSurface(it) }
        player.addAnalyticsListener(listener())
        if (muteAudio) {
            player.trackSelectionParameters = player.trackSelectionParameters
                .buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                .build()
        }
        player.setMediaSource(newSource())
        player.prepare()
        player.playWhenReady = true
        startedMs = WallClock.millis()
        upstream.startPublishing()
    }

    fun awaitFirstFrame(timeoutMs: Long): Boolean {
        val end = WallClock.millis() + timeoutMs
        while (WallClock.millis() < end) {
            pump.pump(100)
            sample()
            if (firstFrameMs >= 0 || rendered > 0) return true
            if (player.playerError != null && !autoReprepare) break
            maybeReprepare()
        }
        sample()
        return firstFrameMs >= 0 || rendered > 0
    }

    fun play(durationMs: Long) {
        if (!sampling) {
            sampling = true
            openGapStarted = WallClock.millis()
        }
        val end = WallClock.millis() + durationMs
        while (WallClock.millis() < end) {
            pump.pump(100)
            sample()
            maybeReprepare()
        }
        sample()
    }

    fun markGap() {
        sampling = true
        openGapStarted = WallClock.millis()
        maxFrameGapMs = 0L
        sawFrame = rendered > 0
        markRendered = rendered
        markMs = openGapStarted
        recoveredMs = -1L
    }

    fun sampleNow(): SpikeSample {
        sample()
        val open = if (sawFrame) WallClock.millis() - openGapStarted else 0L
        return SpikeSample(
            rendered = rendered,
            maxFrameGapMs = maxOf(maxFrameGapMs, open),
            openGapMs = open,
            rebuffers = rebuffers,
            playerErrors = playerErrors,
            playlistStuck = playlistStuck,
            playlistReset = playlistReset,
            behindLiveWindow = behindLiveWindow,
            firstFrameMs = if (firstFrameMs < 0) -1 else firstFrameMs - startedMs,
            reprepares = reprepares,
            recoveryMs = if (markMs == 0L || recoveredMs < 0) -1 else recoveredMs - markMs,
            resumeAfterErrorMs = if (errorMarkedMs < 0 || resumeAfterErrorMs < 0) {
                -1
            } else {
                resumeAfterErrorMs - errorMarkedMs
            },
            loadErrors = loadErrors.toList(),
        )
    }

    fun stateLabel(): String {
        val error = player.playerError
        val counters = player.videoDecoderCounters
        return "state=${player.playbackState} ready=${player.playWhenReady} rendered=$rendered " +
            "decoderRendered=${counters?.renderedOutputBufferCount} skipped=${counters?.skippedOutputBufferCount} " +
            "dropped=${counters?.droppedBufferCount} format=${player.videoFormat?.sampleMimeType} " +
            "surface=${surface != null} err=${error?.errorCodeName}:${error?.cause?.javaClass?.simpleName} " +
            "hits=${upstream.snapshotHits().size}"
    }

    override fun close() {
        runCatching { player.stop() }
        runCatching { pump.pump(200) }
        runCatching { player.release() }
        runCatching { surface?.release() }
        upstream.close()
    }

    private fun newSource(): HlsMediaSource {
        return HlsMediaSource.Factory(factory)
            .setAllowChunklessPreparation(allowChunkless)
            .setLoadErrorHandlingPolicy(policy)
            .createMediaSource(item)
    }

    private fun sample() {
        val count = player.videoDecoderCounters?.renderedOutputBufferCount ?: rendered
        if (count > rendered) {
            val now = WallClock.millis()
            if (sawFrame && sampling) {
                maxFrameGapMs = maxOf(maxFrameGapMs, now - openGapStarted)
            }
            if (markMs > 0 && recoveredMs < 0 && count > markRendered) recoveredMs = now
            if (errorMarkedMs > 0 && now > errorMarkedMs && resumeAfterErrorMs < 0) resumeAfterErrorMs = now
            rendered = count
            sawFrame = true
            openGapStarted = now
            if (firstFrameMs < 0) firstFrameMs = now
        }
    }

    private fun maybeReprepare() {
        if (!autoReprepare || reprepares >= 2) return
        val error = player.playerError ?: return
        if (!error.causes().any { it.isRecoverableReset() }) return
        reprepares++
        player.stop()
        player.clearMediaItems()
        player.setMediaSource(newSource())
        player.prepare()
        player.playWhenReady = true
    }

    private fun listener(): AnalyticsListener = object : AnalyticsListener {
        override fun onRenderedFirstFrame(eventTime: AnalyticsListener.EventTime, output: Any, renderTimeMs: Long) {
            if (firstFrameMs < 0) firstFrameMs = WallClock.millis()
        }

        override fun onPlaybackStateChanged(eventTime: AnalyticsListener.EventTime, state: Int) {
            if (state == Player.STATE_READY) seenReady = true
            if (state == Player.STATE_BUFFERING && seenReady) rebuffers++
        }

        override fun onPlayerError(eventTime: AnalyticsListener.EventTime, error: PlaybackException) {
            playerErrors++
            if (errorMarkedMs < 0) errorMarkedMs = WallClock.millis()
            note(error)
        }

        override fun onLoadError(
            eventTime: AnalyticsListener.EventTime,
            loadEventInfo: LoadEventInfo,
            mediaLoadData: MediaLoadData,
            error: IOException,
            wasCanceled: Boolean,
        ) {
            note(error)
            loadErrors.add(error.causes().joinToString(">") { it.javaClass.simpleName })
        }
    }

    private fun note(error: Throwable) {
        error.causes().forEach {
            when (it) {
                is HlsPlaylistTracker.PlaylistStuckException -> playlistStuck++
                is HlsPlaylistTracker.PlaylistResetException -> playlistReset++
                is BehindLiveWindowException -> behindLiveWindow++
            }
        }
    }
}

private fun Throwable.causes(): Sequence<Throwable> = sequence {
    var current: Throwable? = this@causes
    val seen = HashSet<Throwable>()
    while (current != null && seen.add(current)) {
        yield(current)
        current = current.cause
    }
}

private fun Throwable.isRecoverableReset(): Boolean {
    return this is HlsPlaylistTracker.PlaylistResetException ||
        this is HlsPlaylistTracker.PlaylistStuckException ||
        this is BehindLiveWindowException
}
