package vela.app.ui

import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import androidx.test.core.app.ApplicationProvider
import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import vela.app.infra.Endpoints
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

    @Test
    fun testAllowedThirdPartySubresourceDoesNotSendOrStoreCookies() {
        val seen = AtomicReference<String?>(null)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val pool = Executors.newCachedThreadPool()
        server.executor = pool
        server.createContext("/") { exchange ->
            seen.set(exchange.requestHeaders.getFirst("Cookie"))
            val body = "js".toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/javascript")
            exchange.responseHeaders.add("Set-Cookie", "third=1; Path=/")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val url = "http://127.0.0.1:${server.address.port}/a.js"
            val cookies = CookieManager.getInstance()
            cookies.setAcceptCookie(true)
            cookies.setCookie(url, "sid=secret")
            cookies.flush()
            val response = vela.app.infra.loadGuardedSubresource(
                initialUrl = url,
                method = "GET",
                headers = mapOf("Cookie" to "sid=from-header"),
                allows = { true },
                documentRoot = "https://chaturbate.com/",
            )
            assertEquals(200, response.statusCode)
            assertEquals("第三方子资源带上了 Cookie", null, seen.get())
            val stored = cookies.getCookie(url)
            assertTrue("第三方 Set-Cookie 被写回了 $stored", stored == null || !stored.contains("third=1"))
        } finally {
            server.stop(0)
            pool.shutdownNow()
        }
    }

    @Test
    fun testFirstPartySubresourceKeepsItsOwnCookies() {
        val seen = AtomicReference<String?>(null)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val pool = Executors.newCachedThreadPool()
        server.executor = pool
        server.createContext("/") { exchange ->
            seen.set(exchange.requestHeaders.getFirst("Cookie"))
            val body = "js".toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/javascript")
            exchange.responseHeaders.add("Set-Cookie", "fresh=1; Path=/")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val origin = "http://127.0.0.1:${server.address.port}"
            val url = "$origin/a.js"
            val cookies = CookieManager.getInstance()
            cookies.setAcceptCookie(true)
            cookies.setCookie(url, "sid=secret")
            cookies.flush()
            val response = vela.app.infra.loadGuardedSubresource(
                initialUrl = url,
                method = "GET",
                headers = emptyMap(),
                allows = { true },
                documentRoot = "$origin/",
            )
            assertEquals(200, response.statusCode)
            assertTrue("第一方 Cookie 没送出去 ${seen.get()}", seen.get()?.contains("sid=secret") == true)
            val stored = cookies.getCookie(url)
            assertTrue("第一方 Set-Cookie 没写回 $stored", stored?.contains("fresh=1") == true)
        } finally {
            server.stop(0)
            pool.shutdownNow()
        }
    }

    @Test
    fun testChallengeCookiesFollowTheCurrentTopLevelPage() {
        val seen = AtomicReference<String?>(null)
        val server = cookieServer(seen)
        val pool = server.second
        val http = server.first
        http.start()
        val origin = "http://127.0.0.1:${http.address.port}"
        val url = "$origin/a.js"
        Endpoints.overrideRoot(origin)
        try {
            val cookies = primeCookie(url)
            val client = ChallengeNavigationClient()
            client.shouldInterceptRequest(null, frame("https://challenges.cloudflare.com/", "GET", true))
            val third = client.shouldInterceptRequest(null, frame(url, "GET", false))
            assertNotNull(third)
            assertEquals(200, third!!.statusCode)
            assertEquals("质询页上的站点子资源被当成了第一方", null, seen.get())
            val stored = cookies.getCookie(url)
            assertTrue("质询页收下了站点 Set-Cookie $stored", stored == null || !stored.contains("third=1"))

            seen.set(null)
            client.shouldInterceptRequest(null, frame("$origin/", "GET", true))
            val first = client.shouldInterceptRequest(null, frame(url, "GET", false))
            assertEquals(200, first!!.statusCode)
            assertTrue("回到站点顶层后第一方 Cookie 没送出 ${seen.get()}", seen.get()?.contains("sid=secret") == true)
        } finally {
            Endpoints.clearOverride()
            http.stop(0)
            pool.shutdownNow()
        }
    }

    @Test
    fun testChallengeRedirectHopsStayThirdPartyOnTheChallengePage() {
        val hops = AtomicReference("")
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val pool = Executors.newCachedThreadPool()
        server.executor = pool
        server.createContext("/") { exchange ->
            val path = exchange.requestURI.path
            val cookie = exchange.requestHeaders.getFirst("Cookie")
            hops.set(hops.get() + path + "=" + (cookie ?: "-") + ";")
            if (path == "/hop.js") {
                exchange.responseHeaders.add("Location", "/land.js")
                exchange.sendResponseHeaders(302, -1)
                exchange.close()
                return@createContext
            }
            val body = "js".toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/javascript")
            exchange.responseHeaders.add("Set-Cookie", "third=1; Path=/")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        val origin = "http://127.0.0.1:${server.address.port}"
        Endpoints.overrideRoot(origin)
        try {
            primeCookie("$origin/hop.js")
            val client = ChallengeNavigationClient()
            client.shouldInterceptRequest(null, frame("https://challenges.cloudflare.com/cdn-cgi", "GET", true))
            val response = client.shouldInterceptRequest(null, frame("$origin/hop.js", "GET", false))
            assertEquals(200, response!!.statusCode)
            assertTrue("重定向每一跳都该按质询页算第三方 ${hops.get()}", hops.get().contains("/hop.js=-") && hops.get().contains("/land.js=-"))
            val stored = CookieManager.getInstance().getCookie("$origin/land.js")
            assertTrue("跳转后的 Set-Cookie 被写回了 $stored", stored == null || !stored.contains("third=1"))
        } finally {
            Endpoints.clearOverride()
            server.stop(0)
            pool.shutdownNow()
        }
    }

    @Test
    fun testSidecarCookiesFollowTheCurrentTopLevelPage() {
        val seen = AtomicReference<String?>(null)
        val server = cookieServer(seen)
        val pool = server.second
        val http = server.first
        http.start()
        try {
            val origin = "http://127.0.0.1:${http.address.port}"
            val url = "$origin/a.js"
            primeCookie(url)
            val client = OriginWebViewClient(origin)
            client.shouldInterceptRequest(null, frame("https://challenges.cloudflare.com/", "GET", true))
            val response = client.shouldInterceptRequest(null, frame(url, "GET", false))
            assertEquals(200, response!!.statusCode)
            assertEquals("侧车在质询页仍把站点子资源当第一方", null, seen.get())
        } finally {
            http.stop(0)
            pool.shutdownNow()
        }
    }

    @Test
    fun testReplacedChallengeClientKeepsTheTopLevelCookieContext() {
        val seen = AtomicReference<String?>(null)
        val server = cookieServer(seen)
        val pool = server.second
        val http = server.first
        http.start()
        val origin = "http://127.0.0.1:${http.address.port}"
        Endpoints.overrideRoot(origin)
        try {
            val url = "$origin/a.js"
            primeCookie(url)
            val view = WebView(ApplicationProvider.getApplicationContext())
            val first = ChallengeNavigationClient()
            first.shouldInterceptRequest(view, frame("https://challenges.cloudflare.com/", "GET", true))
            val second = ChallengeNavigationClient()
            val response = second.shouldInterceptRequest(view, frame(url, "GET", false))
            assertEquals(200, response!!.statusCode)
            assertEquals("换过导航客户端后仍按入口根发送 Cookie", null, seen.get())
        } finally {
            Endpoints.clearOverride()
            http.stop(0)
            pool.shutdownNow()
        }
    }

    private fun cookieServer(seen: AtomicReference<String?>): Pair<HttpServer, java.util.concurrent.ExecutorService> {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val pool = Executors.newCachedThreadPool()
        server.executor = pool
        server.createContext("/") { exchange ->
            seen.set(exchange.requestHeaders.getFirst("Cookie"))
            val body = "js".toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/javascript")
            exchange.responseHeaders.add("Set-Cookie", "third=1; Path=/")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        return server to pool
    }

    private fun primeCookie(url: String): CookieManager {
        val cookies = CookieManager.getInstance()
        cookies.setAcceptCookie(true)
        cookies.removeAllCookies(null)
        cookies.setCookie(url, "sid=secret")
        cookies.flush()
        return cookies
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
