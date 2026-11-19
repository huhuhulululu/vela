package vela.app.ui

import android.net.Uri
import android.webkit.WebResourceRequest
import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import vela.app.infra.OriginWebViewClient

/**
 * 主框架 POST 不会走 shouldOverrideUrlLoading。拦截器必须自己挡掉白名单外的地址，
 * 侧车也不能把外站的加载完成当成可执行脚本的页面。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WebViewGuardTest {
    @Test
    fun testMainFramePostCannotLeaveTheChallengeAllowlist() {
        val client = ChallengeNavigationClient()
        val blocked = client.shouldInterceptRequest(
            null,
            frame("https://evil.example/steal", method = "POST", mainFrame = true),
        )
        assertNotNull(blocked)
        assertEquals(403, blocked!!.statusCode)

        val allowed = client.shouldInterceptRequest(
            null,
            frame("https://chaturbate.com/auth/login/", method = "POST", mainFrame = true),
        )
        assertNull(allowed)

        val foreignGet = client.shouldInterceptRequest(
            null,
            frame("https://evil.example/pixel.gif", method = "GET", mainFrame = false),
        )
        assertNotNull(foreignGet)
        assertEquals(403, foreignGet!!.statusCode)
    }

    @Test
    fun testSidecarDoesNotFinishOnAForeignOrigin() {
        var finished: String? = null
        var errors = 0
        var rejected = 0
        val client = OriginWebViewClient(
            siteRoot = "https://chaturbate.com",
            onFinished = { finished = it },
            onMainFrameError = { errors += 1 },
            onOriginRejected = { rejected += 1 },
        )
        client.onPageFinished(null, "https://evil.example/pwn")
        assertNull(finished)
        assertEquals(1, errors)
        assertEquals(1, rejected)
        client.onPageFinished(null, "about:blank")
        assertNull(finished)
        assertEquals(1, errors)
        client.onPageFinished(null, "https://chaturbate.com/auth/login/?next=/")
        assertEquals("https://chaturbate.com/auth/login/?next=/", finished)
        assertEquals(1, errors)
        assertTrue(vela.app.stage.sidecarMayRunScript("https://chaturbate.com/auth/login/?next=/", "https://chaturbate.com"))
        assertTrue(!vela.app.stage.sidecarMayRunScript("https://evil.example/pwn", "https://chaturbate.com"))
        assertTrue(!vela.app.stage.sidecarMayRunScript(null, "https://chaturbate.com"))
    }

    @Test
    fun testAllowedSubresourceRedirectDoesNotEscapeTheOrigin() {
        val evil = AcceptProbe()
        val goodHits = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val pool = Executors.newCachedThreadPool()
        server.executor = pool
        server.createContext("/") { exchange ->
            goodHits.incrementAndGet()
            val path = exchange.requestURI.path
            if (path == "/hop.js") {
                exchange.responseHeaders.add("Location", "http://localhost:${evil.port}/pwn.js")
                exchange.sendResponseHeaders(302, -1)
                exchange.close()
                return@createContext
            }
            if (path == "/off.js") {
                exchange.responseHeaders.add("Location", "https://evil.example/pwn.js")
                exchange.sendResponseHeaders(302, -1)
                exchange.close()
                return@createContext
            }
            if (path == "/same.js") {
                exchange.responseHeaders.add("Location", "/final.js")
                exchange.sendResponseHeaders(302, -1)
                exchange.close()
                return@createContext
            }
            val body = "ready".toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/javascript")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val root = "http://127.0.0.1:${server.address.port}"
            val client = OriginWebViewClient(root)
            val redirected = client.shouldInterceptRequest(
                null,
                frame("$root/hop.js", method = "GET", mainFrame = false),
            )
            assertNotNull("跨域 302 不能交回 WebView 自己跟", redirected)
            assertEquals(403, redirected!!.statusCode)
            Thread.sleep(150)
            assertEquals("外站被跟着请求了", 0, evil.hits())

            val stayed = client.shouldInterceptRequest(
                null,
                frame("$root/same.js", method = "GET", mainFrame = false),
            )
            assertNotNull(stayed)
            assertEquals(200, stayed!!.statusCode)
            assertEquals("ready", stayed.data.readBytes().toString(Charsets.UTF_8))

            val challenge = ChallengeNavigationClient()
            val foreign = challenge.shouldInterceptRequest(
                null,
                frame("$root/off.js", method = "GET", mainFrame = false),
            )
            assertNotNull(foreign)
            assertEquals(403, foreign!!.statusCode)
            Thread.sleep(150)
            assertEquals(0, evil.hits())
        } finally {
            server.stop(0)
            pool.shutdownNow()
            evil.close()
        }
    }

    private fun frame(url: String, method: String, mainFrame: Boolean): WebResourceRequest =
        object : WebResourceRequest {
            override fun getUrl(): Uri = Uri.parse(url)
            override fun isForMainFrame(): Boolean = mainFrame
            override fun isRedirect(): Boolean = false
            override fun hasGesture(): Boolean = true
            override fun getMethod(): String = method
            override fun getRequestHeaders(): Map<String, String> = Collections.emptyMap()
        }
}

/** 同时听 IPv4 和 IPv6 回环，误跟到 localhost 时无论走哪边都能数到。 */
private class AcceptProbe : AutoCloseable {
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
            }, "accept-probe").apply {
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
