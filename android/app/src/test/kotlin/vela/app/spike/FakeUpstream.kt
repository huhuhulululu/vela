package vela.app.spike

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URI
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

internal data class UpstreamHit(
    val path: String,
    val query: String,
    val status: Int,
    val startedMs: Long,
    val heldMs: Long,
)

/**
 * 只绑 127.0.0.1、端口由系统分配的伪直播上游。
 * 低延迟播放列表带 PART 与 CAN-BLOCK-RELOAD；去标签由数据源完成，这里始终能按
 * `_HLS_msn` / `_HLS_part` 挂起响应。分片按墙钟发布，间隔可抖动到至多 1.5×T。
 */
internal class FakeUpstream(
    private val targetSec: Double = 2.0,
    private val partSec: Double = 0.5,
    private val window: Int = 6,
) : AutoCloseable {
    private val partsPerSegment = (targetSec / partSec).toInt().coerceAtLeast(1)
    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val executor = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "spike-upstream").apply { isDaemon = true }
    }
    private val running = AtomicBoolean(true)
    private val lock = Object()
    private val hits = ArrayList<UpstreamHit>()
    private val inFlight = AtomicInteger()
    private var maxInFlight = 0

    val baseUrl: String

    @Volatile var exposeToken: String = "t0"

    @Volatile var failPlaylistWith: Int? = null

    @Volatile var failSegmentWith: Int? = null

    /** 下一个音视频分片请求返回 403，只生效一次。 */
    @Volatile var oneShotMedia403: Boolean = false

    /** 非空时，路径里带这个标记的分片返回 403，用来模拟续期前的旧地址。 */
    @Volatile var forbiddenToken: String? = null

    @Volatile var publishHold: Boolean = false

    private val segments = ArrayDeque<Seg>()
    private val openParts = ArrayList<Part>()
    private var openSequence = 0L
    private var nextSequence = 10L
    private var minimumServedSequence = 10L
    private var epoch = 0
    private var discontinuityAt: Long? = null
    private var publisher: Thread? = null

    @Volatile var jitterMaxFactor: Double = 1.0

    private val random = Random(0x5E1A)

    init {
        baseUrl = "http://127.0.0.1:${server.address.port}"
        server.executor = executor
        server.createContext("/") { exchange -> handle(exchange) }
        server.start()
        synchronized(lock) {
            repeat(window) { publishFullLocked(nextSequence++) }
            openSequence = nextSequence
        }
    }

    fun startPublishing() {
        val thread = Thread(::publishLoop, "spike-publish")
        thread.isDaemon = true
        publisher = thread
        thread.start()
    }

    fun videoPlaylistUrl(): String = "$baseUrl/video.m3u8"

    fun audioPlaylistUrl(): String = "$baseUrl/audio.m3u8"

    fun masterPlaylistUrl(): String = "$baseUrl/master.m3u8"

    fun snapshotHits(): List<UpstreamHit> = synchronized(lock) { hits.toList() }

    fun maxConcurrent(): Int = synchronized(lock) { maxInFlight }

    /** 序号大幅回退，并在新窗口的第一个分片前打上 DISCONTINUITY。旧地址不再提供。 */
    fun rewindWithDiscontinuity(newStart: Long) {
        synchronized(lock) {
            segments.clear()
            openParts.clear()
            nextSequence = newStart
            repeat(window) { publishFullLocked(nextSequence++) }
            openSequence = nextSequence
            openParts.clear()
            minimumServedSequence = segments.firstOrNull()?.sequence ?: newStart
            discontinuityAt = minimumServedSequence
            epoch++
            lock.notifyAll()
        }
    }

    /** 序号与分片个数不变，只换最后一个分片的查询串。 */
    fun rotateLastSegmentUri(): String? {
        synchronized(lock) {
            val last = segments.lastOrNull() ?: return null
            last.token = "rot" + last.sequence
            lock.notifyAll()
            return last.url()
        }
    }

    fun retoken(newToken: String, forbidOld: Boolean) {
        synchronized(lock) {
            if (forbidOld) forbiddenToken = exposeToken
            exposeToken = newToken
            segments.forEach { it.token = newToken }
            openParts.forEach { it.token = newToken }
            lock.notifyAll()
        }
    }

    fun clearForbidden() {
        forbiddenToken = null
    }

    override fun close() {
        running.set(false)
        synchronized(lock) { lock.notifyAll() }
        publisher?.join(2_000)
        server.stop(0)
        executor.shutdownNow()
    }

    private fun publishLoop() {
        while (running.get()) {
            val factor = if (jitterMaxFactor <= 1.0) {
                1.0
            } else {
                1.0 + random.nextDouble() * (jitterMaxFactor - 1.0)
            }
            val intervalNs = (targetSec * factor * 1_000_000_000.0).toLong().coerceAtLeast(1L)
            val partGap = (intervalNs / partsPerSegment).coerceAtLeast(1L)
            val openedAt = WallClock.nanos()
            val epochAtStart = epoch
            for (part in 0 until partsPerSegment) {
                sleepUntil(openedAt + partGap * (part + 1))
                while (publishHold && running.get() && epoch == epochAtStart) {
                    sleepUntil(WallClock.nanos() + 50_000_000L)
                }
                if (!running.get()) return
                if (epoch != epochAtStart) break
                synchronized(lock) {
                    openParts.add(Part(openSequence, part, exposeToken))
                    if (part == partsPerSegment - 1) {
                        publishFullLocked(openSequence, openParts.toList())
                        openParts.clear()
                        openSequence = ++nextSequence
                    }
                    lock.notifyAll()
                }
            }
        }
    }

    private fun sleepUntil(dueNs: Long) {
        while (running.get()) {
            val leftMs = (dueNs - WallClock.nanos()) / 1_000_000L
            if (leftMs <= 0) return
            Thread.sleep(leftMs.coerceAtMost(50L))
        }
    }

    private fun publishFullLocked(sequence: Long, parts: List<Part>? = null) {
        segments.addLast(Seg(sequence, exposeToken, parts))
        while (segments.size > window) segments.removeFirst()
    }

    private fun handle(exchange: HttpExchange) {
        val started = WallClock.millis()
        val nowInFlight = inFlight.incrementAndGet()
        var status = 500
        try {
            synchronized(lock) {
                if (nowInFlight > maxInFlight) maxInFlight = nowInFlight
            }
            val uri = URI.create(exchange.requestURI.toString())
            val path = uri.path ?: "/"
            val query = uri.rawQuery ?: ""
            status = dispatch(exchange, path, query)
            synchronized(lock) {
                hits.add(UpstreamHit(path, query, status, started, WallClock.millis() - started))
            }
        } catch (_: Exception) {
            if (!exchange.responseHeaders.isEmpty() || exchange.responseCode > 0) {
                // 已经开始写了
            }
        } finally {
            inFlight.decrementAndGet()
            runCatching { exchange.close() }
        }
    }

    private fun dispatch(exchange: HttpExchange, path: String, query: String): Int {
        if (oneShotMedia403 && (path.endsWith(".ts") || path.endsWith(".aac"))) {
            oneShotMedia403 = false
            write(exchange, 403, ByteArray(0), "text/plain")
            return 403
        }
        val forced = when {
            path.endsWith(".m3u8") -> failPlaylistWith
            path.endsWith(".ts") || path.endsWith(".aac") -> failSegmentWith
            else -> null
        }
        if (forced != null) {
            write(exchange, forced, ByteArray(0), "text/plain")
            return forced
        }
        val banned = forbiddenToken
        if (banned != null && (path.endsWith(".ts") || path.endsWith(".aac")) && query.contains("t=$banned")) {
            write(exchange, 403, ByteArray(0), "text/plain")
            return 403
        }
        return when {
            path.endsWith("/master.m3u8") -> {
                write(exchange, 200, masterText().toByteArray(), "application/vnd.apple.mpegurl")
                200
            }
            path.endsWith("/video.m3u8") -> {
                awaitBlocking(query)
                write(exchange, 200, videoPlaylist().toByteArray(), "application/vnd.apple.mpegurl")
                200
            }
            path.endsWith("/audio.m3u8") -> {
                write(exchange, 200, audioPlaylist().toByteArray(), "application/vnd.apple.mpegurl")
                200
            }
            path.contains("/part/") -> {
                val bytes = partBytes(path)
                if (bytes == null) {
                    write(exchange, 404, ByteArray(0), "text/plain")
                    404
                } else {
                    write(exchange, 200, bytes, "video/mp2t")
                    200
                }
            }
            path.contains("/seg/") && path.endsWith(".ts") -> {
                val bytes = segmentBytes(path)
                if (bytes == null) {
                    write(exchange, 404, ByteArray(0), "text/plain")
                    404
                } else {
                    write(exchange, 200, bytes, "video/mp2t")
                    200
                }
            }
            path.endsWith(".aac") -> {
                write(exchange, 200, TsMedia.audioTemplate, "audio/aac")
                200
            }
            else -> {
                write(exchange, 404, ByteArray(0), "text/plain")
                404
            }
        }
    }

    private fun awaitBlocking(query: String) {
        val params = query.split("&").mapNotNull {
            val eq = it.indexOf('=')
            if (eq <= 0) null else it.substring(0, eq) to it.substring(eq + 1)
        }.toMap()
        val msn = params["_HLS_msn"]?.toLongOrNull() ?: return
        val part = params["_HLS_part"]?.toIntOrNull() ?: 0
        val deadline = WallClock.nanos() + 3_000_000_000L
        synchronized(lock) {
            while (running.get() && WallClock.nanos() < deadline && !blockingSatisfiedLocked(msn, part)) {
                val left = ((deadline - WallClock.nanos()) / 1_000_000L).coerceIn(1L, 50L)
                lock.wait(left)
            }
        }
    }

    private fun blockingSatisfiedLocked(msn: Long, part: Int): Boolean {
        if (segments.any { it.sequence >= msn }) return true
        if (openSequence > msn) return true
        if (openSequence == msn && openParts.size > part) return true
        return false
    }

    private fun masterText(): String = buildString {
        append("#EXTM3U\n")
        append("#EXT-X-VERSION:7\n")
        append("#EXT-X-INDEPENDENT-SEGMENTS\n")
        append("#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"aud\",NAME=\"main\",DEFAULT=YES,AUTOSELECT=YES,")
        append("CHANNELS=\"1\",URI=\"$baseUrl/audio.m3u8\"\n")
        append("#EXT-X-STREAM-INF:BANDWIDTH=120000,AVERAGE-BANDWIDTH=80000,")
        append("CODECS=\"avc1.42C01E,mp4a.40.2\",RESOLUTION=160x90,FRAME-RATE=10.000,AUDIO=\"aud\"\n")
        append("$baseUrl/video.m3u8\n")
    }

    private fun videoPlaylist(): String {
        synchronized(lock) {
            val body = StringBuilder()
            body.append("#EXTM3U\n")
            body.append("#EXT-X-VERSION:6\n")
            body.append("#EXT-X-TARGETDURATION:${targetSec.toInt()}\n")
            body.append("#EXT-X-SERVER-CONTROL:CAN-BLOCK-RELOAD=YES,PART-HOLD-BACK=1.000\n")
            body.append("#EXT-X-PART-INF:PART-TARGET=$partSec\n")
            val first = segments.firstOrNull()?.sequence ?: openSequence
            body.append("#EXT-X-MEDIA-SEQUENCE:$first\n")
            segments.forEachIndexed { index, seg ->
                if (seg.sequence == discontinuityAt) body.append("#EXT-X-DISCONTINUITY\n")
                body.append("#EXTINF:$targetSec,\n")
                body.append(seg.url())
                body.append('\n')
            }
            openParts.forEach { part ->
                val independent = if (part.index == 0) ",INDEPENDENT=YES" else ""
                body.append("#EXT-X-PART:DURATION=$partSec$independent,URI=\"${part.url()}\"\n")
            }
            val nextPart = openParts.size
            if (nextPart < partsPerSegment) {
                val hint = Part(openSequence, nextPart, exposeToken).url()
                body.append("#EXT-X-PRELOAD-HINT:TYPE=PART,URI=\"$hint\"\n")
            }
            return body.toString()
        }
    }

    private fun audioPlaylist(): String {
        synchronized(lock) {
            val body = StringBuilder()
            body.append("#EXTM3U\n")
            body.append("#EXT-X-VERSION:7\n")
            body.append("#EXT-X-TARGETDURATION:${targetSec.toInt()}\n")
            val first = segments.firstOrNull()?.sequence ?: openSequence
            body.append("#EXT-X-MEDIA-SEQUENCE:$first\n")
            segments.forEach { seg ->
                body.append("#EXTINF:$targetSec,\n")
                body.append("$baseUrl/audio/a${seg.sequence}.aac?t=${seg.token}\n")
            }
            return body.toString()
        }
    }

    private fun segmentBytes(path: String): ByteArray? {
        val name = path.substringAfterLast('/')
        val sequence = name.removePrefix("s").removeSuffix(".ts").substringBefore('.').toLongOrNull() ?: return null
        if (sequence < minimumServedSequence) return null
        return TsMedia.videoSegment(sequence)
    }

    private fun partBytes(path: String): ByteArray? {
        val name = path.substringAfterLast('/')
        val core = name.removeSuffix(".ts")
        val sequence = core.substringBefore('_').removePrefix("p").toLongOrNull() ?: return null
        if (sequence < minimumServedSequence) return null
        val part = core.substringAfter('_').toIntOrNull() ?: return null
        val parts = TsMedia.videoParts(sequence, partsPerSegment)
        if (part !in parts.indices) return null
        return parts[part]
    }

    private fun write(exchange: HttpExchange, status: Int, body: ByteArray, type: String) {
        exchange.responseHeaders.add("Content-Type", type)
        exchange.responseHeaders.add("Cache-Control", "no-store")
        if (body.isEmpty() && status != 200) {
            exchange.sendResponseHeaders(status, -1)
        } else {
            exchange.sendResponseHeaders(status, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
    }

    private inner class Seg(
        val sequence: Long,
        var token: String,
        val parts: List<Part>?,
    ) {
        fun url(): String = "$baseUrl/seg/s$sequence.ts?t=$token"
    }

    private inner class Part(val sequence: Long, val index: Int, var token: String) {
        fun url(): String = "$baseUrl/part/p${sequence}_$index.ts?t=$token"
    }
}
