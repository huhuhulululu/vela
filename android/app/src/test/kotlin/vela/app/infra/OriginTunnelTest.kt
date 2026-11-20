package vela.app.infra

import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 主框架 POST 的 307/308，以及 fetch/XHR 的跨域重定向，WebView 都不会交回拦截器。
 * 这些请求走来源代理：外域目标不能被连上，响应也不能是「请客户端自己跟过去」。
 */
class OriginTunnelTest {
    @Test(timeout = 15_000)
    fun testMainFramePost307And308DoNotReachForeignHost() {
        val evil = LoopProbe()
        val seen = AtomicReference("")
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val pool = Executors.newCachedThreadPool()
        server.executor = pool
        server.createContext("/") { exchange ->
            val body = exchange.requestBody.readBytes()
            seen.set(exchange.requestMethod + " " + body.toString(Charsets.UTF_8))
            val path = exchange.requestURI.path
            val status = if (path == "/go308") 308 else 307
            exchange.responseHeaders.add("Location", "http://localhost:${evil.port}/pwn")
            exchange.sendResponseHeaders(status, -1)
            exchange.close()
        }
        server.start()
        val root = "http://127.0.0.1:${server.address.port}"
        val tunnel = OriginTunnel { WebOriginPolicy.allows(it, root) }
        try {
            val post307 = exchange(tunnel.port, "POST", "$root/login", "room=a".toByteArray())
            assertEquals(403, post307.status)
            assertEquals("POST room=a", seen.get())
            Thread.sleep(100)
            assertEquals(0, evil.hits())

            val post308 = exchange(tunnel.port, "POST", "$root/go308", "room=b".toByteArray())
            assertEquals(403, post308.status)
            assertTrue(seen.get().contains("room=b"))
            Thread.sleep(100)
            assertEquals("308 被跟到了外域", 0, evil.hits())
        } finally {
            tunnel.close()
            server.stop(0)
            pool.shutdownNow()
            evil.close()
        }
    }

    @Test(timeout = 15_000)
    fun testPostFetchRedirectToForeignHostIsRefused() {
        val evil = LoopProbe()
        val method = AtomicReference("")
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val pool = Executors.newCachedThreadPool()
        server.executor = pool
        server.createContext("/") { exchange ->
            method.set(exchange.requestHeaders.getFirst("X-Requested-With").orEmpty())
            exchange.requestBody.readBytes()
            exchange.responseHeaders.add("Location", "http://localhost:${evil.port}/steal")
            exchange.sendResponseHeaders(302, -1)
            exchange.close()
        }
        server.start()
        val root = "http://127.0.0.1:${server.address.port}"
        val tunnel = OriginTunnel { WebOriginPolicy.allows(it, root) }
        try {
            val response = exchange(
                tunnel.port,
                "POST",
                "$root/get_edge_hls_url_ajax/",
                "room_slug=ann".toByteArray(),
                mapOf(
                    "X-Requested-With" to "XMLHttpRequest",
                    "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
                ),
            )
            assertEquals(403, response.status)
            assertEquals("XMLHttpRequest", method.get())
            Thread.sleep(100)
            assertEquals("fetch/XHR 跨域重定向发出去了", 0, evil.hits())

            val direct = exchange(tunnel.port, "POST", "http://localhost:${evil.port}/steal", "x".toByteArray())
            assertEquals(403, direct.status)
            Thread.sleep(50)
            assertEquals(0, evil.hits())
        } finally {
            tunnel.close()
            server.stop(0)
            pool.shutdownNow()
            evil.close()
        }
    }

    @Test(timeout = 15_000)
    fun testSameOriginPost307KeepsTheBody() {
        val landed = AtomicReference("")
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val pool = Executors.newCachedThreadPool()
        server.executor = pool
        server.createContext("/") { exchange ->
            val path = exchange.requestURI.path
            val body = exchange.requestBody.readBytes()
            if (path == "/start") {
                exchange.responseHeaders.add("Location", "/land")
                exchange.sendResponseHeaders(307, -1)
                exchange.close()
                return@createContext
            }
            landed.set(exchange.requestMethod + " " + body.toString(Charsets.UTF_8))
            val payload = "ok".toByteArray()
            exchange.sendResponseHeaders(200, payload.size.toLong())
            exchange.responseBody.use { it.write(payload) }
        }
        server.start()
        val root = "http://127.0.0.1:${server.address.port}"
        val tunnel = OriginTunnel { WebOriginPolicy.allows(it, root) }
        try {
            val first = exchange(tunnel.port, "POST", "$root/start", "room=c".toByteArray())
            assertEquals(307, first.status)
            val next = URL(URL("$root/start"), first.header("Location")).toString()
            val second = exchange(tunnel.port, "POST", next, "room=c".toByteArray())
            assertEquals(200, second.status)
            assertEquals("ok", second.body.toString(Charsets.UTF_8))
            assertEquals("POST room=c", landed.get())
        } finally {
            tunnel.close()
            server.stop(0)
            pool.shutdownNow()
        }
    }

    @Test(timeout = 15_000)
    fun testProxyServesMainFrameGet() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val pool = Executors.newCachedThreadPool()
        server.executor = pool
        server.createContext("/") { exchange ->
            val payload = "page".toByteArray()
            exchange.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
            exchange.sendResponseHeaders(200, payload.size.toLong())
            exchange.responseBody.use { it.write(payload) }
        }
        server.start()
        val root = "http://127.0.0.1:${server.address.port}"
        val tunnel = OriginTunnel { WebOriginPolicy.allows(it, root) }
        try {
            val response = exchange(tunnel.port, "GET", "$root/")
            assertEquals(200, response.status)
            assertEquals("page", response.body.toString(Charsets.UTF_8))
        } finally {
            tunnel.close()
            server.stop(0)
            pool.shutdownNow()
        }
    }

    private fun exchange(
        proxyPort: Int,
        method: String,
        url: String,
        body: ByteArray? = null,
        headers: Map<String, String> = emptyMap(),
    ): RawResponse {
        val socket = Socket("127.0.0.1", proxyPort)
        socket.soTimeout = 5_000
        socket.use { client ->
            val out = client.getOutputStream()
            val host = URI(url).authority ?: URI(url).host
            val sb = StringBuilder()
            sb.append(method).append(' ').append(url).append(" HTTP/1.1\r\n")
            sb.append("Host: ").append(host).append("\r\n")
            for ((name, value) in headers) {
                sb.append(name).append(": ").append(value).append("\r\n")
            }
            if (body != null) sb.append("Content-Length: ").append(body.size).append("\r\n")
            sb.append("Connection: close\r\n\r\n")
            out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
            if (body != null) out.write(body)
            out.flush()
            return parse(client.getInputStream().readBytes())
        }
    }

    private fun parse(raw: ByteArray): RawResponse {
        val text = raw.toString(Charsets.ISO_8859_1)
        val split = text.indexOf("\r\n\r\n")
        val head = if (split >= 0) text.substring(0, split) else text
        val body = if (split >= 0) raw.copyOfRange(split + 4, raw.size) else ByteArray(0)
        val lines = head.split("\r\n")
        val status = lines.firstOrNull()?.split(" ")?.getOrNull(1)?.toIntOrNull() ?: 0
        val headers = LinkedHashMap<String, String>()
        for (line in lines.drop(1)) {
            val index = line.indexOf(':')
            if (index > 0) headers[line.substring(0, index).trim()] = line.substring(index + 1).trim()
        }
        return RawResponse(status, headers, body)
    }

    private class RawResponse(
        val status: Int,
        val headers: Map<String, String>,
        val body: ByteArray,
    ) {
        fun header(name: String): String? =
            headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
    }
}

/** localhost 可能走 ::1，两边都听，误跟出去才能数到。 */
private class LoopProbe : AutoCloseable {
    private val hits = AtomicInteger()
    private val sockets = mutableListOf<ServerSocket>()
    val port: Int

    init {
        val v4 = ServerSocket()
        v4.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0))
        port = v4.localPort
        sockets += v4
        try {
            val v6 = ServerSocket()
            v6.reuseAddress = true
            v6.bind(InetSocketAddress(InetAddress.getByName("::1"), port))
            sockets += v6
        } catch (_: Exception) {
        }
        for (socket in sockets) {
            Thread({
                while (!socket.isClosed) {
                    try {
                        socket.accept().close()
                        hits.incrementAndGet()
                    } catch (_: Exception) {
                        if (socket.isClosed) return@Thread
                    }
                }
            }, "origin-probe").apply {
                isDaemon = true
                start()
            }
        }
    }

    fun hits(): Int = hits.get()

    override fun close() {
        for (socket in sockets) {
            try {
                socket.close()
            } catch (_: Exception) {
            }
        }
    }
}
