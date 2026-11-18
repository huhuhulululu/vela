package vela.app.playback

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

internal data class UpstreamHeaderHit(
    val path: String,
    val headers: Map<String, List<String>>,
)

/**
 * 只绑 127.0.0.1 的合成上游。200 的媒体列表带低延迟标签和相对分片地址，
 * 序号随该路径的请求次数前进。不访问真实站点。
 */
internal class ScriptUpstream : AutoCloseable {
    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val executor = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "lane-upstream").apply { isDaemon = true }
    }
    private val lock = Any()
    private val counts = HashMap<String, Int>()
    private val scripts = HashMap<String, ArrayDeque<Int>>()
    private val hits = ArrayList<UpstreamHeaderHit>()

    val base: String

    @Volatile
    var holdPaths: Set<String> = emptySet()

    val held: CountDownLatch = CountDownLatch(1)
    val releaseHold: CountDownLatch = CountDownLatch(1)

    init {
        base = "http://127.0.0.1:${server.address.port}"
        server.executor = executor
        server.createContext("/") { exchange -> handle(exchange) }
        server.start()
    }

    fun script(path: String, statuses: List<Int>) {
        synchronized(lock) { scripts[path] = ArrayDeque(statuses) }
    }

    fun count(path: String): Int = synchronized(lock) { counts[path] ?: 0 }

    fun headerHits(): List<UpstreamHeaderHit> = synchronized(lock) { hits.toList() }

    override fun close() {
        releaseHold.countDown()
        server.stop(0)
        executor.shutdownNow()
    }

    private fun handle(exchange: HttpExchange) {
        val path = exchange.requestURI.path ?: "/"
        if (path in holdPaths) {
            held.countDown()
            releaseHold.await(8, TimeUnit.SECONDS)
        }
        val headers = HashMap<String, List<String>>()
        for ((key, value) in exchange.requestHeaders) headers[key] = ArrayList(value)
        val (status, body) = synchronized(lock) {
            val n = (counts[path] ?: 0) + 1
            counts[path] = n
            hits.add(UpstreamHeaderHit(path, headers))
            val queue = scripts[path]
            val status = if (queue.isNullOrEmpty()) 200 else queue.removeFirst()
            val body = when {
                status != 200 -> ByteArray(0)
                path.endsWith(".ts") -> byteArrayOf(0x47, 0x00)
                path.endsWith(".m3u8") -> playlist(n).toByteArray(Charsets.UTF_8)
                else -> ByteArray(0)
            }
            status to body
        }
        val type = if (path.endsWith(".m3u8")) "application/vnd.apple.mpegurl" else "video/mp2t"
        exchange.responseHeaders.add("Content-Type", type)
        exchange.responseHeaders.add("Cache-Control", "no-store")
        if (body.isEmpty() && status != 200) {
            exchange.sendResponseHeaders(status, -1)
        } else {
            exchange.sendResponseHeaders(status, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        exchange.close()
    }

    private fun playlist(sequence: Int): String = """
        #EXTM3U
        #EXT-X-VERSION:3
        #EXT-X-TARGETDURATION:1
        #EXT-X-SERVER-CONTROL:CAN-BLOCK-RELOAD=YES,PART-HOLD-BACK=1.000
        #EXT-X-MEDIA-SEQUENCE:$sequence
        #EXT-X-PART-INF:PART-TARGET=0.5
        #EXTINF:1.0,
        seg$sequence.ts
        #EXT-X-PART:DURATION=0.5,URI="part$sequence.ts"

    """.trimIndent() + "\n"
}
