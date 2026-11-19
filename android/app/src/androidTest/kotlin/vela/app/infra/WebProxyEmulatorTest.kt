package vela.app.infra

import android.net.http.SslError
import android.view.ViewGroup
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceError
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayOutputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.security.Security
import java.util.Date
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.ExtendedKeyUsage
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.asn1.x509.KeyPurposeId
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import vela.app.MainActivity

/**
 * 白名单 HTTPS 主框架 POST 的 307/308 在 CONNECT 隧道里，来源代理看不到 Location。
 * 断言必须走真实 WebView 的代理选择：跳到 http://127.0.0.1 时本机端口收不到请求。
 * 直接把请求写到代理端口覆盖不到这条隐式绕过。
 */
@RunWith(AndroidJUnit4::class)
class WebProxyEmulatorTest {
    private var scenario: ActivityScenario<MainActivity>? = null
    private var origin: Origin? = null
    private var evil: Evil? = null

    @After
    fun tearDown() {
        scenario?.close()
        scenario = null
        origin?.close()
        origin = null
        evil?.close()
        evil = null
    }

    @Test(timeout = 180_000)
    fun testAllowlistedHttpsPostRedirectToLoopbackDoesNotConnect() {
        val ip = deviceIpv4()
        val sink = Evil()
        evil = sink
        val site = Origin(ip, sink.port)
        origin = site
        WebNetworkGate.addRule("audit-loopback-redirect") { raw ->
            runCatching { URI(raw).host }.getOrNull() == ip
        }
        val errors = AtomicReference("")
        scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario?.onActivity { activity ->
            val web = WebView(activity)
            activity.addContentView(
                web,
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 400),
            )
            web.settings.javaScriptEnabled = true
            web.settings.domStorageEnabled = true
            web.webChromeClient = object : WebChromeClient() {}
            web.webViewClient = object : WebViewClient() {
                override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
                    handler?.proceed()
                }

                override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                    if (request?.isForMainFrame == true) {
                        errors.set("${error?.errorCode} ${error?.description}")
                    }
                }
            }
            WebNetworkGate.ensure(web)
            WebNetworkGate.runWhenReady {
                web.loadUrl(site.page(307))
            }
        }
        assertTrue(
            "307 的白名单 POST 没有进页面 posts=${site.posts.get()} evil=${sink.hits.get()} err=${errors.get()} enforcing=${WebNetworkGate.isEnforcing()}",
            site.posts.await(307, 60_000),
        )
        val after307 = sink.awaitQuiet()
        assertEquals(
            "307 跳到了回环 hits=$after307 note=${sink.last.get()}",
            0,
            after307,
        )
        scenario?.onActivity { activity ->
            val web = findWeb(activity.findViewById(android.R.id.content)) ?: error("没有 WebView")
            web.loadUrl(site.page(308))
        }
        assertTrue(
            "308 的白名单 POST 没有进页面 posts=${site.posts.get()} evil=${sink.hits.get()}",
            site.posts.await(308, 40_000),
        )
        val after308 = sink.awaitQuiet()
        assertEquals(
            "308 跳到了回环 hits=$after308 note=${sink.last.get()}",
            0,
            after308,
        )
    }

    private fun findWeb(root: android.view.View?): WebView? {
        if (root is WebView) return root
        if (root is ViewGroup) {
            for (index in 0 until root.childCount) {
                findWeb(root.getChildAt(index))?.let { return it }
            }
        }
        return null
    }
}

private fun deviceIpv4(): String {
    val interfaces = NetworkInterface.getNetworkInterfaces() ?: error("没有网卡")
    for (nif in interfaces) {
        if (!nif.isUp || nif.isLoopback) continue
        for (addr in nif.inetAddresses) {
            if (addr is Inet4Address && !addr.isLoopbackAddress && !addr.isLinkLocalAddress) {
                addr.hostAddress?.let { return it }
            }
        }
    }
    error("没有可供 WebView 访问的非回环地址")
}

private class PostCounts {
    private val counts = java.util.concurrent.ConcurrentHashMap<Int, AtomicInteger>()
    private val latch = java.util.concurrent.ConcurrentHashMap<Int, CountDownLatch>()

    fun add(status: Int) {
        counts.computeIfAbsent(status) { AtomicInteger() }.incrementAndGet()
        latch.computeIfAbsent(status) { CountDownLatch(1) }.countDown()
    }

    fun await(status: Int, timeoutMs: Long): Boolean =
        latch.computeIfAbsent(status) { CountDownLatch(1) }.await(timeoutMs, TimeUnit.MILLISECONDS)

    fun get(): String = counts.entries.joinToString { "${it.key}=${it.value.get()}" }
}

/** 非回环地址上的 HTTPS 白名单页。证书不受信任，测试页收到校验错误后继续，代理选择仍是系统的。 */
private class Origin(
    private val ip: String,
    private val redirectPort: Int,
) : AutoCloseable {
    val posts = PostCounts()
    private val server: SSLServerSocket
    private val thread: Thread

    val port: Int
        get() = server.localPort

    fun page(status: Int): String = "https://$ip:$port/$status"

    init {
        server = tlsServer(ip)
        thread = Thread({
            while (!server.isClosed) {
                try {
                    val socket = server.accept()
                    Thread {
                    try {
                        serve(socket)
                    } catch (_: Exception) {
                    }
                }.apply { isDaemon = true }.start()
                } catch (_: Exception) {
                    if (server.isClosed) return@Thread
                }
            }
        }, "audit-origin").apply {
            isDaemon = true
            start()
        }
    }

    private fun serve(socket: Socket) {
        socket.use { client ->
            client.soTimeout = 8_000
            val input = client.getInputStream()
            val raw = ByteArrayOutputStream()
            var matched = 0
            val mark = byteArrayOf(13, 10, 13, 10)
            while (true) {
                val value = input.read()
                if (value < 0) return
                raw.write(value)
                matched = if (value == mark[matched].toInt()) matched + 1 else if (value == '\r'.code) 1 else 0
                if (matched == mark.size) break
                if (raw.size() > 64 * 1024) return
            }
            val header = raw.toString(Charsets.ISO_8859_1.name())
            val request = header.lineSequence().firstOrNull().orEmpty()
            val method = request.substringBefore(' ')
            val target = request.split(" ").getOrNull(1).orEmpty()
            val path = target.substringBefore('?')
            val length = header.lineSequence()
                .firstOrNull { it.startsWith("Content-Length:", ignoreCase = true) }
                ?.substringAfter(':')
                ?.trim()
                ?.toIntOrNull()
                ?: 0
            var left = length
            while (left > 0) {
                val n = input.read()
                if (n < 0) break
                left -= 1
            }
            val out = client.getOutputStream()
            if (method.equals("POST", ignoreCase = true) && (path == "/go307" || path == "/go308")) {
                val status = if (path == "/go307") 307 else 308
                posts.add(status)
                val location = "http://127.0.0.1:$redirectPort/pwn"
                val body = ByteArray(0)
                val response = "HTTP/1.1 $status Redirect\r\nLocation: $location\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                out.write(response.toByteArray(Charsets.ISO_8859_1))
                out.flush()
                return
            }
            val status = path.trim('/').toIntOrNull()
            val action = if (status == 308) "/go308" else "/go307"
            val html = """
                <!DOCTYPE html><html><body>
                <form id="f" method="POST" action="$action"><input name="room" value="secret-body"></form>
                <script>document.getElementById('f').submit();</script>
                </body></html>
            """.trimIndent()
            val bytes = html.toByteArray(Charsets.UTF_8)
            val response = "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
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

private class Evil : AutoCloseable {
    val hits = AtomicInteger()
    val last = AtomicReference("")
    private val server = ServerSocket()
    private val thread: Thread

    val port: Int

    init {
        server.bind(InetSocketAddress("127.0.0.1", 0))
        port = server.localPort
        thread = Thread({
            while (!server.isClosed) {
                try {
                    val socket = server.accept()
                    hits.incrementAndGet()
                    socket.use { client ->
                        client.soTimeout = 1_000
                        val line = ByteArrayOutputStream()
                        while (line.size() < 200) {
                            val value = client.getInputStream().read()
                            if (value < 0 || value == '\n'.code) break
                            line.write(value)
                        }
                        last.set(line.toString(Charsets.ISO_8859_1.name()))
                    }
                } catch (_: Exception) {
                    if (server.isClosed) return@Thread
                }
            }
        }, "audit-evil").apply {
            isDaemon = true
            start()
        }
    }

    fun awaitQuiet(): Int {
        val deadline = android.os.SystemClock.elapsedRealtime() + 3_000
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            if (hits.get() > 0) break
            Thread.sleep(100)
        }
        return hits.get()
    }

    override fun close() {
        try {
            server.close()
        } catch (_: Exception) {
        }
        thread.join(1_000)
    }
}

private fun tlsServer(host: String): SSLServerSocket {
    if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
        Security.addProvider(BouncyCastleProvider())
    }
    val generator = KeyPairGenerator.getInstance("RSA")
    generator.initialize(2048)
    val keys = generator.generateKeyPair()
    val now = System.currentTimeMillis()
    val name = X500Name("CN=$host")
    val signer = JcaContentSignerBuilder("SHA256withRSA").build(keys.private)
    val builder = JcaX509v3CertificateBuilder(
        name,
        java.math.BigInteger.valueOf(now),
        Date(now - 60_000L),
        Date(now + 3_600_000L),
        name,
        keys.public,
    )
    builder.addExtension(Extension.basicConstraints, true, BasicConstraints(false))
    builder.addExtension(
        Extension.keyUsage,
        true,
        KeyUsage(KeyUsage.digitalSignature or KeyUsage.keyEncipherment),
    )
    builder.addExtension(
        Extension.extendedKeyUsage,
        false,
        ExtendedKeyUsage(KeyPurposeId.id_kp_serverAuth),
    )
    builder.addExtension(
        Extension.subjectAlternativeName,
        false,
        GeneralNames(GeneralName(GeneralName.iPAddress, host)),
    )
    val holder = builder.build(signer)
    val cert = JcaX509CertificateConverter().getCertificate(holder)
    val password = charArrayOf('x')
    val store = KeyStore.getInstance("PKCS12")
    store.load(null, null)
    store.setKeyEntry("local", keys.private, password, arrayOf(cert))
    val managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
    managers.init(store, password)
    val context = SSLContext.getInstance("TLS")
    context.init(managers.keyManagers, null, SecureRandom())
    val socket = context.serverSocketFactory.createServerSocket() as SSLServerSocket
    socket.bind(InetSocketAddress("0.0.0.0", 0))
    return socket
}
