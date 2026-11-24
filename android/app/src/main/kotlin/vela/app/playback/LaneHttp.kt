package vela.app.playback

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import vela.core.base.net.UserAgent

/**
 * 列表和分片请求头，与 `PlayerController`、`PlaylistRelay` 一致。
 * 不存 cookie，不走 Cronet。
 */
internal object LaneHttpHeaders {
    const val REFERER: String = "https://chaturbate.com/"
    const val ORIGIN: String = "https://chaturbate.com"
    const val TIMEOUT_MS: Int = 4_000

    fun apply(set: (name: String, value: String) -> Unit) {
        set("User-Agent", UserAgent.safari)
        set("Referer", REFERER)
        set("Origin", ORIGIN)
        set("Accept", "*/*")
        set("Accept-Encoding", "identity")
        set("Connection", "close")
    }
}

internal class LaneHttpResponse(
    val code: Int,
    val body: ByteArray,
    val finalUrl: URI,
)

/**
 * 用 [HttpURLConnection] 拉上游。手动跟随少量重定向，这样相对地址按最终 URL 改写。
 * 同一线程上的连接可以 [abort]，停刷新时不必干等读超时。
 */
internal class LaneHttp {
    private val active = ConcurrentHashMap<Thread, HttpURLConnection>()

    fun get(url: URI, timeoutMs: Int = LaneHttpHeaders.TIMEOUT_MS): LaneHttpResponse {
        var current = url.toURL()
        var hops = 0
        while (hops < MAX_REDIRECTS) {
            if (Thread.currentThread().isInterrupted) throw IOException("cancelled")
            val connection = current.openConnection() as HttpURLConnection
            active[Thread.currentThread()] = connection
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = timeoutMs
                connection.readTimeout = timeoutMs
                connection.requestMethod = "GET"
                connection.useCaches = false
                LaneHttpHeaders.apply { name, value -> connection.setRequestProperty(name, value) }
                val code = connection.responseCode
                if (code in REDIRECT && hops < MAX_REDIRECTS - 1) {
                    val location = connection.getHeaderField("Location") ?: break
                    current = URL(current, location)
                    hops += 1
                    continue
                }
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                val body = stream?.use { it.readBytes() } ?: ByteArray(0)
                return LaneHttpResponse(code, body, current.toURI())
            } finally {
                active.remove(Thread.currentThread(), connection)
                connection.disconnect()
            }
        }
        throw IOException("bad-body")
    }

    fun abort(thread: Thread) {
        active[thread]?.disconnect()
    }

    private companion object {
        const val MAX_REDIRECTS: Int = 5
        val REDIRECT: IntRange = 300..399
    }
}
