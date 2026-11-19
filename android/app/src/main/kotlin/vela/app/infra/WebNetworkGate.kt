package vela.app.infra

import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.URL
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * WebView 对交回去的 POST（含 307/308）和 fetch/XHR 不会再把重定向目标交回来。
 * 所有仍交给 WebView 发出的请求都先进本机代理：非白名单主机直接拒绝，
 * 明文响应里的 Location 每一跳都校验，校验不了的跳不再转发。
 * 子资源 GET/HEAD 仍由拦截器自己跟随，不依赖这里。
 */
internal object WebNetworkGate {
    private val roots = ConcurrentHashMap.newKeySet<String>()
    private val ruleKeys = ConcurrentHashMap.newKeySet<String>()
    private val rules = CopyOnWriteArrayList<(String) -> Boolean>()
    private val pending = ConcurrentLinkedQueue<() -> Unit>()
    private val installing = AtomicBoolean(false)

    @Volatile
    private var tunnel: OriginTunnel? = null

    @Volatile
    var enforcing: Boolean = false
        private set

    @Volatile
    private var unavailable: Boolean = false

    fun watch(siteRoot: String) {
        if (siteRoot.isNotBlank()) roots.add(siteRoot)
    }

    fun addRule(key: String, rule: (String) -> Boolean) {
        if (ruleKeys.add(key)) rules.add(rule)
    }

    fun allows(url: String): Boolean {
        val active = ArrayList<String>(roots.size + 2)
        val current = Endpoints.siteRoot()
        active.add(current)
        if (current != Endpoints.PRODUCTION_ROOT) active.add(Endpoints.PRODUCTION_ROOT)
        active.addAll(roots)
        return originGateAllows(url, active, rules)
    }

    /** 代理已接上，或这台 WebView 明确不支持代理。调用方据此决定能不能把 POST 交回去。 */
    fun isEnforcing(): Boolean = enforcing

    fun ensure(view: WebView) {
        // 必须先有 WebView 实例，特征检测才不会误报不支持。
        if (view.context == null) return
        if (enforcing || unavailable) return
        if (!installing.compareAndSet(false, true)) return
        val task = Runnable {
            try {
                if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
                    unavailable = true
                    installing.set(false)
                    deliverPending()
                    return@Runnable
                }
                val port = tunnel().port
                val config = ProxyConfig.Builder()
                    .addProxyRule("http://127.0.0.1:$port")
                    .build()
                ProxyController.getInstance().setProxyOverride(config, CALLBACKS) {
                    enforcing = true
                    deliverPending()
                }
            } catch (_: Throwable) {
                unavailable = true
                installing.set(false)
                deliverPending()
            }
        }
        val looper = Looper.getMainLooper()
        if (looper != null && Looper.myLooper() == looper) {
            task.run()
        } else if (looper != null) {
            Handler(looper).post(task)
        } else {
            unavailable = true
            installing.set(false)
            deliverPending()
        }
    }

    fun runWhenReady(block: () -> Unit) {
        if (enforcing || unavailable) {
            block()
            return
        }
        pending.add(block)
        if (enforcing || unavailable) deliverPending()
    }

    private fun tunnel(): OriginTunnel {
        tunnel?.let { return it }
        return synchronized(this) {
            tunnel ?: OriginTunnel { allows(it) }.also { tunnel = it }
        }
    }

    private fun deliverPending() {
        val tasks = ArrayList<() -> Unit>()
        while (true) {
            val task = pending.poll() ?: break
            tasks.add(task)
        }
        if (tasks.isEmpty()) return
        val run = Runnable {
            for (task in tasks) {
                try {
                    task()
                } catch (_: Throwable) {
                }
            }
        }
        val looper = Looper.getMainLooper()
        if (looper != null && Looper.myLooper() == looper) {
            run.run()
        } else if (looper != null) {
            Handler(looper).post(run)
        } else {
            run.run()
        }
    }

    private val CALLBACKS = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "vela-webview-proxy").apply { isDaemon = true }
    }
}

internal fun originGateAllows(
    url: String,
    roots: Collection<String>,
    extras: List<(String) -> Boolean> = emptyList(),
): Boolean {
    val uri = try {
        URI(url)
    } catch (_: Exception) {
        return false
    }
    val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return false
    if (scheme != "http" && scheme != "https") return false
    if (uri.userInfo != null) return false
    if (uri.host.isNullOrBlank()) return false
    for (root in roots) {
        if (WebOriginPolicy.allows(url, root)) return true
    }
    for (extra in extras) {
        if (runCatching { extra(url) }.getOrDefault(false)) return true
    }
    return false
}

/**
 * 只监听环回的 HTTP 代理。拒绝非白名单目标；3xx 的 Location 不在白名单里时
 * 不把重定向交回客户端，因此客户端无法再向那个主机发出下一跳。
 */
internal class OriginTunnel(
    private val allows: (String) -> Boolean,
) : Closeable {
    private val server = ServerSocket()
    private val workers = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "vela-origin-proxy").apply { isDaemon = true }
    }
    val port: Int

    init {
        server.bind(InetSocketAddress("127.0.0.1", 0))
        port = server.localPort
        Thread({
            while (!server.isClosed) {
                try {
                    val socket = server.accept()
                    workers.execute {
                        try {
                            handle(socket)
                        } catch (_: Throwable) {
                            try {
                                socket.close()
                            } catch (_: Throwable) {
                            }
                        }
                    }
                } catch (_: Exception) {
                    if (server.isClosed) return@Thread
                }
            }
        }, "vela-origin-accept").apply {
            isDaemon = true
            start()
        }
    }

    override fun close() {
        try {
            server.close()
        } catch (_: IOException) {
        }
        workers.shutdownNow()
        workers.awaitTermination(1, TimeUnit.SECONDS)
    }

    private fun handle(socket: Socket) {
        socket.use { client ->
            client.soTimeout = 20_000
            client.tcpNoDelay = true
            val input = client.getInputStream()
            val output = client.getOutputStream()
            val raw = try {
                readHeaderBlock(input)
            } catch (_: IOException) {
                return
            }
            val lines = headerLines(raw)
            if (lines.isEmpty()) return
            val request = lines[0].split(" ")
            if (request.size < 2) {
                writeStatus(output, 400, "bad request")
                return
            }
            val method = request[0]
            val target = request[1]
            if (method.equals("CONNECT", ignoreCase = true)) {
                tunnel(target, input, output, client)
                return
            }
            val absolute = absoluteUrl(target, lines)
            if (absolute == null || !permitted(absolute)) {
                writeStatus(output, 403, "blocked")
                return
            }
            forward(method, absolute, lines, input, output)
        }
    }

    private fun forward(
        method: String,
        absolute: String,
        lines: List<String>,
        input: InputStream,
        output: OutputStream,
    ) {
        val uri = try {
            URI(absolute)
        } catch (_: Exception) {
            writeStatus(output, 403, "blocked")
            return
        }
        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return
        if (scheme != "http") {
            writeStatus(output, 403, "blocked")
            return
        }
        val host = uri.host
        if (host.isNullOrBlank()) {
            writeStatus(output, 403, "blocked")
            return
        }
        val port = if (uri.port > 0) uri.port else 80
        val path = buildString {
            val rawPath = uri.rawPath
            if (rawPath.isNullOrEmpty()) append('/') else append(rawPath)
            if (uri.rawQuery != null) {
                append('?')
                append(uri.rawQuery)
            }
        }
        val contentLength = headerValue(lines, "Content-Length")?.toLongOrNull()
        val chunked = headerValue(lines, "Transfer-Encoding")?.contains("chunked", ignoreCase = true) == true
        if (contentLength != null && contentLength > MAX_BODY) {
            writeStatus(output, 403, "blocked")
            return
        }
        val upstream = Socket()
        try {
            upstream.soTimeout = 20_000
            upstream.tcpNoDelay = true
            upstream.connect(InetSocketAddress(host, port), 15_000)
            val upOut = upstream.getOutputStream()
            val upIn = upstream.getInputStream()
            val head = StringBuilder()
            head.append(method).append(' ').append(path).append(" HTTP/1.1\r\n")
            var hasHost = false
            for (line in lines.drop(1)) {
                val name = line.substringBefore(':').trim()
                val value = line.substringAfter(':', "").trim()
                if (name.isEmpty() || hopByHop(name)) continue
                if (name.equals("Host", ignoreCase = true)) hasHost = true
                head.append(name).append(": ").append(value).append("\r\n")
            }
            if (!hasHost) {
                val hostValue = if (port == 80) host else "$host:$port"
                head.append("Host: ").append(hostValue).append("\r\n")
            }
            head.append("Connection: close\r\n\r\n")
            upOut.write(head.toString().toByteArray(Charsets.ISO_8859_1))
            when {
                chunked -> copyChunked(input, upOut)
                contentLength != null && contentLength > 0L -> copyExact(input, upOut, contentLength.toInt())
            }
            upOut.flush()
            val respRaw = readHeaderBlock(upIn)
            val respLines = headerLines(respRaw)
            val status = statusCode(respLines.firstOrNull().orEmpty())
            if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                val location = headerValue(respLines, "Location")
                val next = location?.let { resolveRedirect(absolute, it) }
                if (next == null || !permitted(next)) {
                    writeStatus(output, 403, "blocked")
                    return
                }
            }
            output.write(respRaw)
            copy(upIn, output)
        } catch (_: IOException) {
            try {
                writeStatus(output, 502, "unavailable")
            } catch (_: IOException) {
            }
        } finally {
            try {
                upstream.close()
            } catch (_: IOException) {
            }
        }
    }

    private fun tunnel(target: String, input: InputStream, output: OutputStream, client: Socket) {
        val host: String
        val port: Int
        if (target.startsWith("[")) {
            val end = target.indexOf(']')
            if (end <= 1) {
                writeStatus(output, 403, "blocked")
                return
            }
            host = target.substring(1, end)
            port = target.substring(end + 1).removePrefix(":").toIntOrNull() ?: 443
        } else {
            val colon = target.lastIndexOf(':')
            if (colon <= 0) {
                host = target
                port = 443
            } else {
                host = target.substring(0, colon)
                port = target.substring(colon + 1).toIntOrNull() ?: 443
            }
        }
        val probe = if (port == 443) "https://$host/" else "https://$host:$port/"
        if (!permitted(probe)) {
            writeStatus(output, 403, "blocked")
            return
        }
        val upstream = Socket()
        try {
            upstream.soTimeout = 20_000
            upstream.tcpNoDelay = true
            upstream.connect(InetSocketAddress(host, port), 15_000)
            output.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
            output.flush()
            val upward = Thread({
                try {
                    copy(upstream.getInputStream(), output)
                } catch (_: IOException) {
                }
                try {
                    client.shutdownOutput()
                } catch (_: IOException) {
                }
            }, "vela-origin-up").apply { isDaemon = true }
            upward.start()
            try {
                copy(input, upstream.getOutputStream())
            } catch (_: IOException) {
            }
            try {
                upstream.shutdownOutput()
            } catch (_: IOException) {
            }
            upward.join(30_000)
        } catch (_: IOException) {
            try {
                writeStatus(output, 502, "unavailable")
            } catch (_: IOException) {
            }
        } finally {
            try {
                upstream.close()
            } catch (_: IOException) {
            }
        }
    }

    private fun permitted(url: String): Boolean {
        val uri = try {
            URI(url)
        } catch (_: Exception) {
            return false
        }
        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return false
        if (scheme != "http" && scheme != "https") return false
        if (uri.userInfo != null || uri.host.isNullOrBlank()) return false
        return try {
            allows(url)
        } catch (_: Exception) {
            false
        }
    }

    private fun absoluteUrl(target: String, lines: List<String>): String? {
        if (target.startsWith("http://") || target.startsWith("https://")) return target
        if (!target.startsWith("/")) return null
        val host = headerValue(lines, "Host") ?: return null
        return "http://$host$target"
    }
}

private const val MAX_BODY = 2_000_000

private fun resolveRedirect(current: String, location: String): String? {
    if (location.isBlank()) return null
    return try {
        URL(URL(current), location.trim()).toString()
    } catch (_: Exception) {
        null
    }
}

private fun statusCode(requestLine: String): Int =
    requestLine.split(" ").getOrNull(1)?.toIntOrNull() ?: 0

private fun headerLines(raw: ByteArray): List<String> =
    raw.toString(Charsets.ISO_8859_1).split("\r\n").filter { it.isNotEmpty() }

private fun headerValue(lines: List<String>, name: String): String? {
    for (line in lines.drop(1)) {
        val index = line.indexOf(':')
        if (index <= 0) continue
        if (line.substring(0, index).trim().equals(name, ignoreCase = true)) {
            return line.substring(index + 1).trim()
        }
    }
    return null
}

private fun hopByHop(name: String): Boolean =
    name.equals("Connection", ignoreCase = true) ||
        name.equals("Proxy-Connection", ignoreCase = true) ||
        name.equals("Proxy-Authorization", ignoreCase = true) ||
        name.equals("Keep-Alive", ignoreCase = true) ||
        name.equals("TE", ignoreCase = true) ||
        name.equals("Trailer", ignoreCase = true) ||
        name.equals("Upgrade", ignoreCase = true)

private fun readHeaderBlock(input: InputStream): ByteArray {
    val out = ByteArrayOutputStream()
    var matched = 0
    while (true) {
        val value = input.read()
        if (value < 0) throw IOException("eof")
        out.write(value)
        matched = when {
            value == CRLF_MARK[matched].toInt() -> matched + 1
            value == '\r'.code -> 1
            else -> 0
        }
        if (matched == CRLF_MARK.size) return out.toByteArray()
        if (out.size() > 64 * 1024) throw IOException("header")
    }
}

private val CRLF_MARK = byteArrayOf(13, 10, 13, 10)
private val CRLF = "\r\n".toByteArray(Charsets.ISO_8859_1)

private fun readAsciiLine(input: InputStream): String {
    val out = ByteArrayOutputStream()
    while (true) {
        val value = input.read()
        if (value < 0 || value == '\n'.code) break
        if (value != '\r'.code) out.write(value)
    }
    return out.toString(Charsets.ISO_8859_1.name())
}

private fun copyChunked(input: InputStream, output: OutputStream) {
    while (true) {
        val sizeLine = readAsciiLine(input)
        output.write(sizeLine.toByteArray(Charsets.ISO_8859_1))
        output.write(CRLF)
        val size = sizeLine.substringBefore(';').trim().toLong(16)
        if (size == 0L) {
            while (true) {
                val trailer = readAsciiLine(input)
                output.write(trailer.toByteArray(Charsets.ISO_8859_1))
                output.write(CRLF)
                if (trailer.isEmpty()) break
            }
            return
        }
        if (size > MAX_BODY) throw IOException("body")
        copyExact(input, output, size.toInt())
        val cr = input.read()
        val lf = input.read()
        if (cr != '\r'.code || lf != '\n'.code) throw IOException("chunk")
        output.write(CRLF)
    }
}

private fun copyExact(input: InputStream, output: OutputStream, count: Int) {
    val buf = ByteArray(8 * 1024)
    var left = count
    while (left > 0) {
        val n = input.read(buf, 0, minOf(buf.size, left))
        if (n < 0) throw IOException("short body")
        output.write(buf, 0, n)
        left -= n
    }
}

private fun copy(input: InputStream, output: OutputStream) {
    val buf = ByteArray(8 * 1024)
    while (true) {
        val n = input.read(buf)
        if (n < 0) break
        output.write(buf, 0, n)
    }
    output.flush()
}

private fun writeStatus(output: OutputStream, code: Int, reason: String) {
    val body = reason.toByteArray(Charsets.UTF_8)
    val head = "HTTP/1.1 $code $reason\r\nContent-Type: text/plain; charset=utf-8\r\n" +
        "Content-Length: ${body.size}\r\nConnection: close\r\n\r\n"
    output.write(head.toByteArray(Charsets.ISO_8859_1))
    output.write(body)
    output.flush()
}
