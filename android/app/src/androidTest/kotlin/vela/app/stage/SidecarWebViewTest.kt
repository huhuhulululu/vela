package vela.app.stage

import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import vela.app.MainActivity
import vela.app.infra.Settings
import vela.core.stream.StreamTicket

/**
 * 真实 WebView：桥必须在首次 loadUrl 之前就在，当前页才能回传；
 * 连续请求和并发收藏查询不能互相覆盖结果。
 */
@RunWith(AndroidJUnit4::class)
class SidecarWebViewTest {
    private lateinit var site: LoopbackSite
    private var scenario: ActivityScenario<MainActivity>? = null
    private var page: WebSidecarPage? = null

    @Before
    fun setUp() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        target.getSharedPreferences(Settings.PREFS_NAME, android.content.Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        site = LoopbackSite()
    }

    @After
    fun tearDown() {
        val activityScenario = scenario
        if (activityScenario != null) {
            activityScenario.onActivity { page?.destroy() }
        } else {
            page?.destroy()
        }
        page = null
        scenario?.close()
        site.close()
    }

    @Test(timeout = 90_000)
    fun testFirstLoadConsecutiveAndConcurrentFavoriteQueries() = runBlocking {
        val host = attachHost()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val web = WebSidecarPage(host.context, site.origin, host)
        page = web
        val sidecar = SiteSidecar(
            page = web,
            entryUrl = "${site.origin}/auth/login/?next=/",
            scope = scope,
            scheduleIdle = { _, _ -> IdleHandle { } },
        )
        try {
            val first: StreamTicket = try {
                withTimeout(25_000) { sidecar.resolve("ann") }
            } catch (error: Throwable) {
                throw AssertionError(
                    "首次取流没有在当前页回传 error=$error hits=${site.hitDump()}",
                    error,
                )
            }
            assertTrue("首次取流没有在当前页回传", first.isPlayable)
            assertEquals("https://cdn.example/master.m3u8", first.url.toString())

            val second = withTimeout(15_000) { sidecar.resolve("beth") }
            assertTrue(second.isPlayable)
            assertEquals("beth", site.lastSlug.get())

            val flag = withTimeout(15_000) {
                web.evaluate(sidecarScript("return {status:200, body:String(window.__bridgeAtLoad === true)};", emptyMap()))
            }
            val (_, flagBody) = sidecarPayload(flag)
            assertEquals(
                "桥在页面加载时不可见，说明是 load 之后才注入",
                "true",
                flagBody.toString(Charsets.UTF_8),
            )

            val names = listOf("alpha", "bravo", "charlie")
            val results = withTimeout(20_000) {
                names.map { name ->
                    async {
                        name to sidecar.get("${site.origin}/api/chatvideocontext/$name/")
                    }
                }.awaitAll()
            }
            for ((name, result) in results) {
                val text = result.data.toString(Charsets.UTF_8)
                assertTrue("并发查询串了结果 name=$name body=$text", text.contains("\"username\":\"$name\""))
                assertEquals(200, result.status)
            }
        } finally {
            scope.cancel()
        }
    }

    private fun attachHost(): FrameLayout {
        val ready = java.util.concurrent.atomic.AtomicReference<FrameLayout>()
        val launched = ActivityScenario.launch(MainActivity::class.java)
        scenario = launched
        launched.onActivity { activity ->
            val frame = FrameLayout(activity)
            activity.addContentView(
                frame,
                ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 400),
            )
            ready.set(frame)
        }
        return ready.get()
    }
}

/** 设备本机回环。只应答测试页、取流和收藏查询，不访问真实站点。 */
internal class LoopbackSite : AutoCloseable {
    private val server = ServerSocket(0)
    private val hits = ConcurrentHashMap<String, AtomicInteger>()
    val origin: String = "http://127.0.0.1:${server.localPort}"
    val lastSlug = java.util.concurrent.atomic.AtomicReference("")

    @Volatile
    var html: String = """
        <!DOCTYPE html><html><head><script>
        window.__bridgeAtLoad = (typeof VelaSidecar !== "undefined" && typeof VelaSidecar.deliver === "function");
        </script></head><body>ready</body></html>
    """.trimIndent()

    private val thread = Thread({
        while (!server.isClosed) {
            try {
                val socket = server.accept()
                Thread { serve(socket) }.start()
            } catch (_: Exception) {
                if (server.isClosed) return@Thread
            }
        }
    }, "sidecar-loopback").apply {
        isDaemon = true
        start()
    }

    fun hitCount(path: String): Int = hits[path]?.get() ?: 0

    fun hitDump(): String = hits.entries.joinToString { "${it.key}=${it.value.get()}" }

    private fun serve(socket: Socket) {
        socket.use { client ->
            client.soTimeout = 4_000
            val input = client.getInputStream()
            val headerBytes = ByteArrayOutputStream()
            var seen = 0
            val window = ByteArray(4)
            while (true) {
                val b = input.read()
                if (b < 0) return
                headerBytes.write(b)
                window[seen % 4] = b.toByte()
                seen += 1
                if (seen >= 4 &&
                    window[(seen - 4) % 4] == '\r'.code.toByte() &&
                    window[(seen - 3) % 4] == '\n'.code.toByte() &&
                    window[(seen - 2) % 4] == '\r'.code.toByte() &&
                    window[(seen - 1) % 4] == '\n'.code.toByte()
                ) {
                    break
                }
                if (headerBytes.size() > 64 * 1024) return
            }
            val header = headerBytes.toString(Charsets.ISO_8859_1.name())
            val requestLine = header.lineSequence().firstOrNull().orEmpty()
            val target = requestLine.split(" ").getOrNull(1) ?: "/"
            val path = target.substringBefore('?')
            val length = header.lineSequence()
                .firstOrNull { it.startsWith("Content-Length:", ignoreCase = true) }
                ?.substringAfter(":")
                ?.trim()
                ?.toIntOrNull()
                ?: 0
            val body = if (length > 0) {
                val buf = ByteArray(length)
                var off = 0
                while (off < length) {
                    val n = input.read(buf, off, length - off)
                    if (n < 0) break
                    off += n
                }
                String(buf, 0, off, Charsets.UTF_8)
            } else {
                ""
            }
            hits.computeIfAbsent(path) { AtomicInteger() }.incrementAndGet()
            if (path.startsWith("/get_edge_hls_url_ajax")) {
                val slug = body.substringAfter("room_slug=", "").substringBefore("&")
                lastSlug.set(java.net.URLDecoder.decode(slug, "UTF-8"))
            }
            val payload = when {
                path.startsWith("/get_edge_hls_url_ajax") ->
                    """{"success":true,"url":"https://cdn.example/master.m3u8","room_status":"public"}"""
                path.contains("/api/chatvideocontext/") -> {
                    val name = path.trimEnd('/').substringAfterLast('/')
                    """{"username":"$name","room_status":"offline"}"""
                }
                else -> html
            }
            val bytes = payload.toByteArray(Charsets.UTF_8)
            val type = if (path.startsWith("/get_edge_hls_url_ajax") || path.contains("/api/")) {
                "application/json; charset=utf-8"
            } else {
                "text/html; charset=utf-8"
            }
            val response = "HTTP/1.1 200 OK\r\nContent-Type: $type\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
            val out = client.getOutputStream()
            out.write(response.toByteArray(Charsets.ISO_8859_1))
            out.write(bytes)
            out.flush()
        }
    }

    override fun close() {
        try {
            server.close()
        } catch (_: Exception) {
        }
        thread.join(1_000)
    }
}
