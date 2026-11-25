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

    /**
     * 期限语义与 iOS URLSession 对齐：timeoutSeconds 是空闲超时，正文每到一段数据就往后推；
     * 另有总上限 [HttpClientImpl.TOTAL_CAP_FACTOR] 倍超时，慢速滴流最终仍会被掐断。
     * 旧约定是「总期限 = 超时」：这条在旧约定下 1 秒就失败，慢链路上收得完的首页因此永远收不完。
     */
    @Test(timeout = 10_000)
    fun testSteadySlowBodyLongerThanTheTimeoutStillCompletes() {
        withServer({ exchange ->
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.use { body ->
                repeat(8) {
                    body.write(ByteArray(64) { 7 })
                    body.flush()
                    Thread.sleep(300)
                }
            }
        }) { port ->
            val started = System.nanoTime()
            val result = runBlocking {
                HttpClientImpl().send(HTTPRequest(url = "http://127.0.0.1:$port/steady", timeoutSeconds = 1.0))
            }
            val elapsedMs = (System.nanoTime() - started) / 1_000_000
            assertEquals(200, result.status)
            assertEquals(8 * 64, result.data.size)
            assertTrue("elapsed=$elapsedMs", elapsedMs > 1_500)
        }
    }

    /** 新约定：滴流不会触发空闲超时，但到总上限（4 × 1 秒）一定掐断；旧约定是 1 秒就断。 */
    @Test(timeout = 10_000)
    fun testSlowDripStopsAtTheTotalDeadline() {
        val closed = CountDownLatch(1)
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
                closed.countDown()
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
            assertTrue(failed.exceptionOrNull() is java.net.SocketTimeoutException)
            val cap = HttpClientImpl.TOTAL_CAP_FACTOR * 1_000
            assertTrue("滴流应撑到总上限附近才断 elapsed=$elapsedMs", elapsedMs >= cap - 300)
            assertTrue("持续滴数据时没有在总上限处掐断 elapsed=$elapsedMs", elapsedMs < cap + 1_500)
            assertTrue("掐断后连接没有断开", closed.await(2, TimeUnit.SECONDS))
        }
    }

    /** 响应头迟迟不来按空闲超时算，不会拿到总上限。 */
    @Test(timeout = 8_000)
    fun testHeaderDelayCountsAgainstTheIdleTimeout() {
        withServer({ exchange ->
            try {
                Thread.sleep(5_000)
            } catch (_: InterruptedException) {
            }
        }) { port ->
            val started = System.nanoTime()
            val failed = runCatching {
                runBlocking {
                    HttpClientImpl().send(HTTPRequest(url = "http://127.0.0.1:$port/no-header", timeoutSeconds = 1.0))
                }
            }
            val elapsedMs = (System.nanoTime() - started) / 1_000_000
            assertTrue(failed.isFailure)
            assertTrue("响应头不来时拿到了超过空闲超时的时间 elapsed=$elapsedMs", elapsedMs < 1_500)
        }
    }

    /** 响应头之后正文停住：从响应头那一刻再给一个空闲超时，不再给完整总上限。 */
    @Test(timeout = 8_000)
    fun testBodyStallAfterHeaderStopsAtTheIdleTimeout() {
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
            assertTrue("正文停住后超过一个空闲超时仍没断 elapsed=$elapsedMs", elapsedMs < 2_500)
        }
    }

    /**
     * 安卓的 HttpURLConnection 在建连时把读超时复制进 socket，之后再改 readTimeout 不生效。
     * 假连接照这个行为：建连那一刻锁定读超时，正文两段之间的间隔超过它就抛读超时。
     * 排队吃掉大半期限后，传给连接的读超时仍须是完整空闲超时，正文间隔小于它就能收完。
     */
    @Test(timeout = 15_000)
    fun testQueuedRequestStillGetsTheFullIdleReadTimeoutOnTheConnection() {
        val connection = AndroidLikeConnection(gapsMs = listOf(500, 500, 500, 500))
        saturateIo {
            val client = HttpClientImpl { connection }
            val scope = CoroutineScope(SupervisorJob())
            val result = AtomicReference<Result<vela.core.base.net.HTTPResult>?>(null)
            val job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                result.set(runCatching { client.send(HTTPRequest(url = "http://127.0.0.1:9/queued", timeoutSeconds = 1.0)) })
            }
            Thread.sleep(700)
            releaseSaturatedIo()
            runBlocking { withTimeout(8_000) { job.join() } }
            scope.cancel()
        }
        assertEquals("建连时传给连接的读超时应是完整空闲超时", 1_000, connection.lockedReadTimeout)
        assertEquals("建连时传给连接的连接超时应是完整空闲超时", 1_000, connection.lockedConnectTimeout)
        assertTrue("所有读超时设置都应是完整空闲超时 ${connection.readTimeouts}", connection.readTimeouts.all { it == 1_000 })
        assertEquals(4 * 16, connection.delivered)
    }

    /** 模仿安卓：建连时锁定读超时；正文按给定间隔逐段到达，间隔超过锁定值即读超时。 */
    private class AndroidLikeConnection(private val gapsMs: List<Int>) :
        HttpURLConnection(URL("http://127.0.0.1:9/")) {
        val readTimeouts = java.util.Collections.synchronizedList(mutableListOf<Int>())
        @Volatile var lockedReadTimeout: Int = -1
        @Volatile var lockedConnectTimeout: Int = -1
        @Volatile var delivered: Int = 0

        override fun setReadTimeout(timeout: Int) {
            readTimeouts.add(timeout)
            super.setReadTimeout(timeout)
        }

        override fun connect() {
            if (connected) return
            lockedReadTimeout = readTimeout
            lockedConnectTimeout = connectTimeout
            connected = true
        }

        override fun getResponseCode(): Int {
            connect()
            return 200
        }

        override fun getHeaderFields(): Map<String, List<String>> = emptyMap()

        override fun getInputStream(): java.io.InputStream {
            connect()
            return object : java.io.InputStream() {
                private var index = 0
                override fun read(): Int = error("unused")
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (index >= gapsMs.size) return -1
                    val gap = gapsMs[index]
                    if (gap > lockedReadTimeout) {
                        Thread.sleep(lockedReadTimeout.toLong())
                        throw java.net.SocketTimeoutException("Read timed out (locked at connect)")
                    }
                    Thread.sleep(gap.toLong())
                    index += 1
                    val n = minOf(16, len)
                    java.util.Arrays.fill(b, off, off + n, 7)
                    delivered += n
                    return n
                }
            }
        }

        override fun disconnect() = Unit

        override fun usingProxy(): Boolean = false
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
