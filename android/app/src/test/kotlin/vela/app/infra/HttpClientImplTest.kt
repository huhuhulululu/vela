package vela.app.infra

import com.sun.net.httpserver.HttpServer
import java.net.CookieHandler
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import vela.core.base.net.HTTPRequest

class HttpClientImplTest {
    private val previousCookies = CookieHandler.getDefault()

    @After
    fun tearDown() {
        CookieHandler.setDefault(previousCookies)
    }

    @Test
    fun testTimeoutCappedAtTwelveSeconds() {
        assertEquals(12_000, HttpClientImpl.resolveTimeoutMillis(60.0))
        assertEquals(12_000, HttpClientImpl.resolveTimeoutMillis(12.0))
        assertEquals(8_000, HttpClientImpl.resolveTimeoutMillis(8.0))
        assertEquals(12_000, HttpClientImpl.resolveTimeoutMillis(0.0))
        withServer({ exchange ->
            exchange.sendResponseHeaders(204, -1)
            exchange.close()
        }) { port ->
            val connection = URL("http://127.0.0.1:$port/").openConnection() as HttpURLConnection
            try {
                HttpClientImpl.applyTimeouts(connection, 60.0)
                assertEquals(12_000, connection.connectTimeout)
                assertEquals(12_000, connection.readTimeout)
            } finally {
                connection.disconnect()
            }
        }
    }

    @Test
    fun testShortTimeoutFailsFast() {
        withServer({ exchange ->
            Thread.sleep(5_000)
            val body = "late".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }) { port ->
            val client = HttpClientImpl()
            val started = System.nanoTime()
            val failed = runCatching {
                runBlocking {
                    client.send(HTTPRequest(url = "http://127.0.0.1:$port/hang", timeoutSeconds = 0.4))
                }
            }
            val elapsedMs = (System.nanoTime() - started) / 1_000_000
            assertTrue(failed.isFailure)
            assertTrue("elapsed=$elapsedMs", elapsedMs < 3_000)
        }
    }

    @Test
    fun testDoesNotStoreCookies() {
        val seen = AtomicReference<String?>(null)
        val hits = AtomicInteger()
        withServer({ exchange ->
            val n = hits.incrementAndGet()
            if (n == 2) seen.set(exchange.requestHeaders.getFirst("Cookie"))
            exchange.responseHeaders.add("Set-Cookie", "sid=secret; Path=/")
            val body = "ok".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }) { port ->
            val client = HttpClientImpl()
            val url = "http://127.0.0.1:$port/room"
            val first = runBlocking { client.send(HTTPRequest(url)) }
            val second = runBlocking { client.send(HTTPRequest(url)) }
            assertEquals(200, first.status)
            assertEquals(200, second.status)
            assertEquals("ok", first.data.toString(Charsets.UTF_8))
            assertNull(seen.get())
            assertNull(CookieHandler.getDefault())
        }
    }

    @Test(timeout = 8_000)
    fun testCancelDisconnectsInFlightConnection() {
        val accepted = CountDownLatch(1)
        val closed = CountDownLatch(1)
        withServer({ exchange ->
            accepted.countDown()
            try {
                exchange.sendResponseHeaders(200, 0)
                val body = exchange.responseBody
                while (true) {
                    body.write(byteArrayOf(1))
                    body.flush()
                    Thread.sleep(100)
                }
            } catch (_: Exception) {
                closed.countDown()
            }
        }) { port ->
            val scope = CoroutineScope(Dispatchers.IO)
            val job = scope.launch {
                HttpClientImpl().send(HTTPRequest(url = "http://127.0.0.1:$port/drip", timeoutSeconds = 12.0))
            }
            try {
                assertTrue(accepted.await(3, TimeUnit.SECONDS))
                Thread.sleep(200)
                job.cancel()
                assertTrue("取消后连接没有断开", closed.await(2, TimeUnit.SECONDS))
                runBlocking { withTimeout(2_000) { job.join() } }
            } finally {
                job.cancel()
                scope.cancel()
            }
        }
    }

    @Test(timeout = 8_000)
    fun testSlowDripStopsAtTheTotalDeadline() {
        withServer({ exchange ->
            exchange.sendResponseHeaders(200, 0)
            val body = exchange.responseBody
            try {
                while (true) {
                    body.write(byteArrayOf(1))
                    body.flush()
                    Thread.sleep(200)
                }
            } catch (_: Exception) {
            }
        }) { port ->
            val started = System.nanoTime()
            val failed = runCatching {
                runBlocking {
                    HttpClientImpl().send(HTTPRequest(url = "http://127.0.0.1:$port/drip", timeoutSeconds = 1.0))
                }
            }
            val elapsedMs = (System.nanoTime() - started) / 1_000_000
            assertTrue(failed.isFailure)
            assertTrue("持续滴数据时没有总期限 elapsed=$elapsedMs", elapsedMs < 3_000)
        }
    }

    @Test
    fun testForwardsCallerHeadersAndErrorBody() {
        val userAgent = AtomicReference<String?>(null)
        withServer({ exchange ->
            userAgent.set(exchange.requestHeaders.getFirst("User-Agent"))
            val body = "nope".toByteArray()
            exchange.sendResponseHeaders(503, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }) { port ->
            val client = HttpClientImpl()
            val result = runBlocking {
                client.send(
                    HTTPRequest(url = "http://127.0.0.1:$port/x")
                        .withHeader("User-Agent", "vela-test-agent"),
                )
            }
            assertEquals(503, result.status)
            assertEquals("nope", result.data.toString(Charsets.UTF_8))
            assertEquals("vela-test-agent", userAgent.get())
        }
    }

    private fun withServer(handler: com.sun.net.httpserver.HttpHandler, block: (Int) -> Unit) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/", handler)
        val pool = Executors.newCachedThreadPool()
        server.executor = pool
        server.start()
        try {
            block(server.address.port)
        } finally {
            server.stop(0)
            pool.shutdownNow()
        }
    }
}
