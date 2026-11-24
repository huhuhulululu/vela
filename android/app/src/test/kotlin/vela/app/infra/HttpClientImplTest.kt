package vela.app.infra

import com.sun.net.httpserver.HttpServer
import java.io.File
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
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asExecutor
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

    @Test(timeout = 8_000)
    fun testHeaderDelayCountsAgainstTheTotalDeadline() {
        withServer({ exchange ->
            Thread.sleep(800)
            exchange.sendResponseHeaders(200, 100_000)
            try {
                Thread.sleep(5_000)
            } catch (_: InterruptedException) {
            }
        }) { port ->
            val started = System.nanoTime()
            val failed = runCatching {
                runBlocking {
                    HttpClientImpl().send(HTTPRequest(url = "http://127.0.0.1:$port/slow-header", timeoutSeconds = 1.0))
                }
            }
            val elapsedMs = (System.nanoTime() - started) / 1_000_000
            assertTrue(failed.isFailure)
            assertTrue("响应头之后又给了完整读超时 elapsed=$elapsedMs", elapsedMs < 1_500)
        }
    }

    @Test(timeout = 15_000)
    fun testQueuedTimeCountsAgainstTheTotalDeadline() {
        val hits = AtomicInteger()
        saturateIo {
            withServer({ exchange ->
                hits.incrementAndGet()
                val body = "ok".toByteArray()
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }) { port ->
                val scope = CoroutineScope(SupervisorJob())
                val job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    HttpClientImpl().send(HTTPRequest(url = "http://127.0.0.1:$port/queued", timeoutSeconds = 0.4))
                }
                Thread.sleep(700)
                releaseSaturatedIo()
                val finished = runCatching { runBlocking { withTimeout(3_000) { job.join() } } }
                Thread.sleep(200)
                assertTrue(finished.isSuccess)
                assertTrue("排队时间没有计入总期限", job.isCancelled || !job.isActive)
                assertEquals("排队超过期限后仍然发出了请求", 0, hits.get())
                scope.cancel()
            }
        }
    }

    @Test(timeout = 15_000)
    fun testQueuedDeadlineReturnsWhileIoStaysSaturated() {
        val hits = AtomicInteger()
        saturateIo {
            withServer({ exchange ->
                hits.incrementAndGet()
                val body = "ok".toByteArray()
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }) { port ->
                val scope = CoroutineScope(SupervisorJob())
                val started = System.nanoTime()
                val job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    HttpClientImpl().send(HTTPRequest(url = "http://127.0.0.1:$port/queued", timeoutSeconds = 0.4))
                }
                val finished = runCatching { runBlocking { withTimeout(1_500) { job.join() } } }
                val elapsedMs = (System.nanoTime() - started) / 1_000_000
                assertTrue("IO 仍饱和时到期没有返回 $finished elapsed=$elapsedMs", finished.isSuccess)
                assertTrue("elapsed=$elapsedMs", elapsedMs < 1_200)
                assertTrue(job.isCompleted)
                assertEquals("到期后仍把请求发出去了", 0, hits.get())
                scope.cancel()
            }
        }
    }

    @Test(timeout = 15_000)
    fun testCancelBeforeExecutionDoesNotSend() {
        val hits = AtomicInteger()
        saturateIo {
            withServer({ exchange ->
                hits.incrementAndGet()
                val body = "ok".toByteArray()
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }) { port ->
                val scope = CoroutineScope(SupervisorJob())
                val job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    HttpClientImpl().send(HTTPRequest(url = "http://127.0.0.1:$port/queued", timeoutSeconds = 8.0))
                }
                job.cancel()
                releaseSaturatedIo()
                runBlocking { withTimeout(3_000) { job.join() } }
                Thread.sleep(300)
                assertEquals("取消后排队任务仍然发出了请求", 0, hits.get())
                scope.cancel()
            }
        }
    }

    @Test(timeout = 8_000)
    fun testCancelWhileConnectingReturnsWithoutCrashing() {
        val crashes = java.util.Collections.synchronizedList(mutableListOf<Throwable>())
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, error -> crashes.add(error) }
        val scope = CoroutineScope(Dispatchers.IO)
        val job = scope.launch {
            HttpClientImpl().send(HTTPRequest(url = "http://203.0.113.1:81/hang", timeoutSeconds = 0.6))
        }
        try {
            Thread.sleep(150)
            job.cancel()
            runBlocking { withTimeout(2_000) { job.join() } }
            assertTrue("取消连接时线程崩了 $crashes", crashes.isEmpty())
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previous)
            job.cancel()
            scope.cancel()
            // 连接超时还占着 IO 线程时，后面的饱和测试会误判。
            Thread.sleep(800)
        }
    }

    @Test
    fun testClientBytecodeDoesNotReferenceOnSpinWait() {
        val location = HttpClientImpl::class.java.protectionDomain?.codeSource?.location
        assertTrue("找不到 HttpClientImpl 的 class", location != null)
        val root = File(location!!.toURI())
        val files = ArrayList<Pair<String, ByteArray>>()
        if (root.isDirectory) {
            root.walkTopDown()
                .filter { it.isFile && it.name.contains("HttpClientImpl") && it.extension == "class" }
                .forEach { files.add(it.name to it.readBytes()) }
        } else {
            java.util.jar.JarFile(root).use { jar ->
                val entries = jar.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (entry.name.contains("HttpClientImpl") && entry.name.endsWith(".class")) {
                        files.add(entry.name to jar.getInputStream(entry).readBytes())
                    }
                }
            }
        }
        assertTrue("找不到 HttpClientImpl 的 class：$location", files.isNotEmpty())
        for ((name, bytes) in files) {
            val text = String(bytes, Charsets.ISO_8859_1)
            assertTrue("$name 调用了 minSdk 26 没有的 onSpinWait", !text.contains("onSpinWait"))
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

    private val ioRelease = java.util.concurrent.atomic.AtomicReference<CountDownLatch?>(null)

    private fun saturateIo(block: () -> Unit) {
        val release = CountDownLatch(1)
        ioRelease.set(release)
        val running = CountDownLatch(64)
        val executor = Dispatchers.IO.asExecutor()
        repeat(96) {
            executor.execute {
                running.countDown()
                release.await()
            }
        }
        try {
            assertTrue("IO 没有饱和", running.await(5, TimeUnit.SECONDS))
            block()
        } finally {
            release.countDown()
            ioRelease.set(null)
        }
    }

    private fun releaseSaturatedIo() {
        ioRelease.get()?.countDown()
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
