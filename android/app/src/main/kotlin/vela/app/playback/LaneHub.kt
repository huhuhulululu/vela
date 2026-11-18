package vela.app.playback

import android.net.Uri
import java.io.IOException
import java.io.InterruptedIOException
import java.net.URI
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistTracker
import vela.core.base.play.MediaPlaylistInfo
import vela.core.base.play.RelayDefaults
import vela.core.base.play.RelayFailure
import vela.core.base.play.StreamRendition
import vela.core.hls.MuteAudioPolicy
import vela.core.hls.PlaylistLadder
import vela.core.hls.RenewalCoordinator
import vela.core.hls.RenewalPolicy

internal enum class LaneTrack {
    VIDEO,
    AUDIO,
}

/**
 * 一路播放源。视频与音轨各有一条后台刷新（只有 [setRefreshing] 打开才跑）。
 * 静音停的是音轨循环，播放器来要 audio.m3u8 仍然按需取。
 */
internal class Lane(
    val id: String,
    rendition: StreamRendition,
    val maxBandwidth: Double,
    audioSuppressed: Boolean,
    var renew: (suspend () -> StreamRendition?)?,
) {
    val lock = ReentrantLock()
    val coordinator = RenewalCoordinator()
    var rendition: StreamRendition = rendition.isolated()
    var audioSuppressed: Boolean = audioSuppressed
    var refreshing: Boolean = false

    @Volatile
    var removed: Boolean = false

    val loops = HashMap<LaneTrack, Thread>()
    val lastInfo = HashMap<LaneTrack, MediaPlaylistInfo>()
    val productive = HashMap<LaneTrack, Boolean>()

    fun snapshot(): StreamRendition = lock.withLock { rendition.isolated() }
}

/** 登记结果。音视频合一的 [url] 是 `video.m3u8`；有独立音轨时是 `master.m3u8`。 */
data class LaneHandle(
    val id: String,
    val url: Uri,
)

/**
 * 进程内的多路表，对应中继的源表和监听代次，但不绑套接字。
 * `listeningPort` 只是会话代次：非 0 表示已经 start，stop 之后变 0。
 * 迟到的 stop 回调核对代次，不会清掉 stop 期间重新 start 的会话。
 *
 * P1 结论（写进这里，避免以后改回去）：
 * 分片用改写后的真实绝对地址，不用 `vela://…/seg/<序号>`；
 * 静音只关音轨，主列表仍带独立音轨，取消静音不必重建主列表。
 */
class LaneHub internal constructor(
    internal val http: LaneHttp = LaneHttp(),
) {
    private val lock = ReentrantLock()
    private val lanes = LinkedHashMap<String, Lane>()
    private val order = ArrayList<String>()
    private var startAllowed = true
    private var listening = false
    private var epoch = 0
    private var port = 0

    /** 测试用：`stopListener` 摘掉旧会话之后、迟到回调之前调用。 */
    var stopInterleaveForTesting: (() -> Unit)? = null

    var allowStart: Boolean
        get() = lock.withLock { startAllowed }
        set(value) {
            lock.withLock { startAllowed = value }
        }

    /** 没在会话里是 0。不是 TCP 端口。 */
    val listeningPort: Int
        get() = lock.withLock { port }

    fun start(): Boolean = lock.withLock {
        if (!startAllowed) return false
        if (listening) return true
        epoch += 1
        listening = true
        port = epoch
        true
    }

    /**
     * 作废当前会话并清空源表。代次先加一，锁外才跑迟到回调；
     * 回调若发现代次已经变了（stop 期间又 start 过）就什么都不做。
     */
    fun stopListener() {
        val generation: Int
        val stopping: List<Lane>
        lock.lock()
        try {
            epoch += 1
            generation = epoch
            port = 0
            listening = false
            stopping = ArrayList(lanes.values)
            lanes.clear()
            order.clear()
        } finally {
            lock.unlock()
        }
        stopping.forEach { it.shutdown(http) }
        try {
            stopInterleaveForTesting?.invoke()
        } finally {
            Thread {
                val again = lock.withLock {
                    if (epoch != generation) {
                        emptyList()
                    } else {
                        port = 0
                        listening = false
                        val copy = ArrayList(lanes.values)
                        lanes.clear()
                        order.clear()
                        copy
                    }
                }
                again.forEach { it.shutdown(http) }
            }.apply {
                isDaemon = true
                name = "lane-session-cancel"
                start()
            }
        }
    }

    fun localURL(
        rendition: StreamRendition,
        maxBandwidth: Double,
        audioSuppressed: Boolean,
        renew: (suspend () -> StreamRendition?)? = null,
    ): LaneHandle? {
        if (!allowStart) return null
        if (listeningPort == 0) {
            start()
            return null
        }
        val id = UUID.randomUUID().toString()
        val lane = Lane(id, rendition, maxBandwidth, audioSuppressed, renew)
        val evicted = lock.withLock {
            val removed = if (order.size >= MAX_SOURCES) order.removeAt(0) else null
            val old = removed?.let { lanes.remove(it) }
            order.add(id)
            lanes[id] = lane
            old
        }
        evicted?.shutdown(http)
        val name = playerPlaylist(rendition)
        return LaneHandle(id, velaUri(id, name))
    }

    fun deactivate(id: String) {
        val lane = lock.withLock {
            order.remove(id)
            lanes.remove(id)
        } ?: return
        lane.shutdown(http)
    }

    fun setRefreshing(on: Boolean, id: String) {
        val lane = lock.withLock { lanes[id] } ?: return
        lane.lock.withLock {
            if (lane.removed) return
            lane.refreshing = on
            if (!on) {
                lane.coordinator.cancel()
                lane.stopLoops(http)
                return
            }
            lane.coordinator.resume()
            lane.startLoop(LaneTrack.VIDEO, http, this)
            lane.startAudioLoop(http, this)
        }
    }

    fun setAudioSuppressed(suppressed: Boolean, id: String) {
        val lane = lock.withLock { lanes[id] } ?: return
        lane.lock.withLock {
            if (lane.removed || lane.audioSuppressed == suppressed) return
            lane.audioSuppressed = suppressed
            if (suppressed) {
                lane.stopLoop(LaneTrack.AUDIO, http)
            } else {
                lane.startAudioLoop(http, this)
            }
        }
    }

    /** 续期进行中，包括续期成功后的那一次重取。看门狗用来重新计时。 */
    fun isRenewing(id: String): Boolean {
        val lane = lock.withLock { lanes[id] } ?: return false
        return !lane.removed && lane.coordinator.isActive
    }

    /** 映射里有哪些轨。音视频合一只有 `video`。 */
    fun mappedTracks(id: String): Set<String> {
        val lane = lock.withLock { lanes[id] } ?: return emptySet()
        val tracks = linkedSetOf("video")
        if (lane.snapshot().audio != null) tracks.add("audio")
        return tracks
    }

    fun factory(ownerId: String): LaneDataSourceFactory = LaneDataSourceFactory(this, ownerId)

    fun openDataSource(ownerId: String? = null): LaneDataSource = LaneDataSource(this, ownerId)

    internal fun lane(id: String): Lane? = lock.withLock {
        val lane = lanes[id] ?: return null
        if (lane.removed) null else lane
    }

    internal fun load(uri: Uri, ownerId: String?): LoadedBytes {
        if (Thread.currentThread().isInterrupted) throw InterruptedIOException()
        val vela = parseVela(uri)
        if (vela != null) return loadVela(vela, uri)
        return loadHttp(uri, ownerId)
    }

    private fun loadVela(ref: VelaRef, uri: Uri): LoadedBytes {
        val lane = lane(ref.id) ?: throw httpFailure(404, uri)
        val bytes = when (ref.name) {
            "master.m3u8" -> masterBytes(lane, uri)
            "video.m3u8" -> mediaBytes(lane, LaneTrack.VIDEO, uri)
            "audio.m3u8" -> mediaBytes(lane, LaneTrack.AUDIO, uri)
            else -> throw httpFailure(404, uri)
        }
        return LoadedBytes(bytes, uri)
    }

    private fun loadHttp(uri: Uri, ownerId: String?): LoadedBytes {
        val lane = ownerId?.let { lane(it) }
        val target = URI(uri.toString())
        val response = if (lane == null) {
            http.get(target)
        } else {
            fetchRenewed(lane, target) { target }
        }
        if (response.code !in 200..299) throw httpFailure(response.code, uri, response.body)
        val bytes = if (uri.toString().contains(".m3u8")) rewrite(response) else response.body
        val reported = Uri.parse(response.finalUrl.toString())
        return LoadedBytes(bytes, reported)
    }

    private fun masterBytes(lane: Lane, uri: Uri): ByteArray {
        ensureSelected(lane, uri)
        val current = lane.snapshot()
        val local = current.localMaster("vela://lane/${lane.id}")
        if (local != null) return local.toByteArray(Charsets.UTF_8)
        return mediaBytes(lane, LaneTrack.VIDEO, uri)
    }

    private fun mediaBytes(lane: Lane, track: LaneTrack, errorUri: Uri): ByteArray {
        ensureSelected(lane, errorUri)
        val url = trackUrl(lane, track) ?: throw httpFailure(404, errorUri)
        val response = fetchRenewed(lane, url) { trackUrl(lane, track) ?: url }
        if (response.code !in 200..299) throw httpFailure(response.code, errorUri, response.body)
        val bytes = rewrite(response)
        val info = MediaPlaylistInfo.parse(bytes.toString(Charsets.UTF_8))
        if (info != null && isPlaylistReset(lane, track, info)) {
            throw HlsPlaylistTracker.PlaylistResetException(errorUri)
        }
        note(lane, track, bytes)
        return bytes
    }

    /** 序号回退且带 DISCONTINUITY：不是 403，不续期，交给加载错误策略。 */
    private fun isPlaylistReset(lane: Lane, track: LaneTrack, info: MediaPlaylistInfo): Boolean {
        val previous = lane.lock.withLock { lane.lastInfo[track] } ?: return false
        return info.hasDiscontinuity && info.lastSequence < previous.lastSequence
    }

    private fun ensureSelected(lane: Lane, errorUri: Uri) {
        val pending = lane.lock.withLock {
            if (!lane.rendition.isUnselected) null else lane.rendition.video to lane.maxBandwidth
        } ?: return
        val (master, cap) = pending
        val response = fetchRenewed(lane, master) {
            lane.snapshot().let { if (it.isUnselected) master else it.video }
        }
        if (response.code !in 200..299) throw httpFailure(response.code, errorUri, response.body)
        val text = response.body.toString(Charsets.UTF_8)
        if (!text.contains("#EXTM3U")) throw IOException("bad-body")
        val base = response.finalUrl
        val chosen = if (PlaylistLadder.steadyPlaylist(text, base) != null) {
            StreamRendition(video = base)
        } else {
            StreamRendition.select(text, base, cap) ?: throw IOException("no-segments")
        }
        lane.lock.withLock {
            if (lane.rendition.isUnselected) lane.rendition = chosen.isolated()
        }
    }

    /**
     * 200 直接返回。403 / 410 走 [RenewalCoordinator.renewBlocking]，
     * 成功后在同一次 `recovering` 里重取，这段时间 [RenewalCoordinator.isActive] 为真。
     * 这次 GET 还拿着旧地址时，另一条刷新可能已经把续期做完并清掉了在途任务。
     * 预算的最短间隔会拒绝再续一次，这时跟已经换上的地址重取，不能把过期的 403 交回去。
     * 线程中断或 [RenewalCoordinator.cancel] 都不必等 fetch 自己结束。
     */
    private fun fetchRenewed(lane: Lane, url: URI, urlAfter: () -> URI): LaneHttpResponse {
        val first = try {
            http.get(url)
        } catch (error: IOException) {
            if (Thread.currentThread().isInterrupted) throw InterruptedIOException()
            throw error
        }
        if (!RenewalPolicy.isExpiry(RelayFailure.Http(first.code))) return first
        val renewed = try {
            runBlocking {
                lane.coordinator.recovering {
                    refetchIfAddressMoved(url, urlAfter)?.let { return@recovering it }
                    val stale = lane.snapshot()
                    val registered = lane.coordinator.epoch
                    val ok = lane.coordinator.renewBlocking(
                        timeoutMs = RENEW_WAIT_MS,
                        fetch = { lane.renew?.invoke() },
                        apply = { fresh -> installRenewed(lane, fresh, registered, stale) },
                    )
                    if (!ok) return@recovering refetchIfAddressMoved(url, urlAfter)
                    val next = urlAfter()
                    val second = http.get(next)
                    if (second.code !in 200..299) null else second
                }
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            throw InterruptedIOException()
        }
        if (Thread.currentThread().isInterrupted) throw InterruptedIOException()
        return renewed ?: first
    }

    /**
     * [requested] 飞着的时候地址已经换过，就重取现在的地址。
     * 新地址仍是 403 / 410 则返回 null，让调用方继续续期；续期没成功时退回原来的失败。
     */
    private fun refetchIfAddressMoved(requested: URI, urlAfter: () -> URI): LaneHttpResponse? {
        val next = urlAfter()
        if (next == requested) return null
        val response = http.get(next)
        if (RenewalPolicy.isExpiry(RelayFailure.Http(response.code))) return null
        return response
    }

    private fun trackUrl(lane: Lane, track: LaneTrack): URI? {
        val rendition = lane.snapshot()
        return when (track) {
            LaneTrack.VIDEO -> rendition.video
            LaneTrack.AUDIO -> rendition.audio
        }
    }

    private fun rewrite(response: LaneHttpResponse): ByteArray {
        val text = response.body.toString(Charsets.UTF_8)
        if (!text.contains("#EXTM3U")) throw IOException("bad-body")
        val steady = PlaylistLadder.steadyPlaylist(text, response.finalUrl) ?: throw IOException("no-segments")
        return steady.toByteArray(Charsets.UTF_8)
    }

    private fun note(lane: Lane, track: LaneTrack, bytes: ByteArray) {
        val info = MediaPlaylistInfo.parse(bytes.toString(Charsets.UTF_8)) ?: return
        lane.lock.withLock {
            val previous = lane.lastInfo[track]
            lane.productive[track] = previous == null || info.lastSequence > previous.lastSequence
            if (previous == null || info.lastSequence >= previous.lastSequence) {
                lane.lastInfo[track] = info
            }
        }
    }

    internal fun refreshDelayMs(lane: Lane, track: LaneTrack): Long {
        val (info, productive) = lane.lock.withLock { lane.lastInfo[track] to (lane.productive[track] == true) }
        val target = info?.targetDuration ?: RelayDefaults.defaultTargetDuration
        val seconds = if (productive) {
            minOf(target, info?.lastSegmentDuration ?: target)
        } else {
            maxOf(0.25, target / 2.0)
        }
        return (seconds * 1_000.0).toLong().coerceAtLeast(1L)
    }

    internal fun shouldRun(lane: Lane, track: LaneTrack): Boolean = lane.lock.withLock {
        if (lane.removed || !lane.refreshing) return false
        when (track) {
            LaneTrack.VIDEO -> true
            LaneTrack.AUDIO -> MuteAudioPolicy.shouldRefreshAudioPlaylist(
                hasSeparateAudio = lane.rendition.audio != null,
                relayRefreshing = true,
                suppressAudioFetch = lane.audioSuppressed,
            )
        }
    }

    private companion object {
        const val MAX_SOURCES: Int = 2
        val RENEW_WAIT_MS: Long = (RenewalPolicy.waitBound * 1_000.0).toLong()
    }
}

internal data class LoadedBytes(val bytes: ByteArray, val reported: Uri)

internal data class VelaRef(val id: String, val name: String)

internal fun parseVela(uri: Uri): VelaRef? {
    if (!uri.scheme.equals("vela", ignoreCase = true)) return null
    if (!uri.host.equals("lane", ignoreCase = true)) return null
    val parts = uri.path.orEmpty().split('/').filter { it.isNotEmpty() }
    if (parts.size < 2) return null
    return VelaRef(parts[parts.size - 2], parts.last())
}

internal fun velaUri(id: String, name: String): Uri = Uri.parse("vela://lane/$id/$name")

internal fun playerPlaylist(rendition: StreamRendition): String = when {
    rendition.isUnselected -> "master.m3u8"
    rendition.audio != null -> "master.m3u8"
    else -> "video.m3u8"
}

internal fun httpFailure(code: Int, uri: Uri, body: ByteArray = ByteArray(0)):
    androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException =
    androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException(
        code,
        null,
        null,
        emptyMap(),
        androidx.media3.datasource.DataSpec(uri),
        body,
    )

private fun StreamRendition.isolated(): StreamRendition = copy(
    videoAttributes = videoAttributes.toList(),
    audioAttributes = audioAttributes.toList(),
)

private fun LaneHub.installRenewed(
    lane: Lane,
    fresh: StreamRendition,
    epoch: Int,
    stale: StreamRendition,
): Boolean {
    if (fresh.isUnselected) return false
    return lane.lock.withLock {
        if (!lane.coordinator.isCurrent(epoch) || lane.removed) return false
        if (lane.rendition != stale) return true
        lane.rendition = fresh.isolated()
        if (lane.rendition.audio == null) {
            lane.stopLoop(LaneTrack.AUDIO, http)
        } else {
            lane.startAudioLoop(http, this)
        }
        true
    }
}

private fun Lane.shutdown(http: LaneHttp) {
    val threads = lock.withLock {
        removed = true
        refreshing = false
        val running = loops.values.toList()
        loops.clear()
        running
    }
    coordinator.cancel()
    threads.forEach {
        it.interrupt()
        http.abort(it)
    }
}

private fun Lane.stopLoops(http: LaneHttp) {
    val running = loops.values.toList()
    loops.clear()
    running.forEach {
        it.interrupt()
        http.abort(it)
    }
}

private fun Lane.stopLoop(track: LaneTrack, http: LaneHttp) {
    val thread = loops.remove(track) ?: return
    thread.interrupt()
    http.abort(thread)
}

private fun Lane.startAudioLoop(http: LaneHttp, hub: LaneHub) {
    if (rendition.audio == null) return
    if (!MuteAudioPolicy.shouldRefreshAudioPlaylist(true, refreshing, audioSuppressed)) return
    startLoop(LaneTrack.AUDIO, http, hub)
}

private fun Lane.startLoop(track: LaneTrack, http: LaneHttp, hub: LaneHub) {
    val existing = loops[track]
    if (existing != null && existing.isAlive) return
    val thread = Thread({
        while (!Thread.currentThread().isInterrupted && hub.shouldRun(this, track)) {
            try {
                val uri = velaUri(id, if (track == LaneTrack.VIDEO) "video.m3u8" else "audio.m3u8")
                hub.load(uri, id)
            } catch (_: InterruptedException) {
                break
            } catch (_: InterruptedIOException) {
                break
            } catch (_: CancellationException) {
                break
            } catch (_: Exception) {
                lock.withLock { productive[track] = false }
            }
            if (Thread.currentThread().isInterrupted || !hub.shouldRun(this, track)) break
            try {
                Thread.sleep(hub.refreshDelayMs(this, track))
            } catch (_: InterruptedException) {
                break
            }
        }
    }, "lane-refresh-$id-$track")
    thread.isDaemon = true
    loops[track] = thread
    thread.start()
}
