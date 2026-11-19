package vela.app.stage

import android.annotation.SuppressLint
import android.content.Context
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import java.net.URI
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.json.JSONObject
import vela.core.base.net.HTTPClient
import vela.core.base.net.HTTPRequest
import vela.core.base.net.HTTPResult
import vela.core.base.net.UserAgent
import vela.core.base.site.looksLikeChallenge
import vela.core.hls.SidecarIdlePolicy
import vela.core.stream.StreamDecodeError
import vela.core.stream.StreamDecoder
import vela.core.stream.StreamTicket

/** 侧车失败。质询要弹确认页；脚本和超时不当成网络故障去跳房。 */
sealed class SidecarError(message: String) : Exception(message) {
    data object Unavailable : SidecarError("unavailable")
    data object Script : SidecarError("script")
    data object TimedOut : SidecarError("timedOut")
    data object Challenge : SidecarError("challenge")
}

fun interface IdleHandle {
    fun cancel()
}

/**
 * 离屏网页。测试注入假实现；真机用 [WebSidecarPage]。
 * [loaded] 在页面加载完成前为 false。
 */
interface SidecarPage {
    val loaded: Boolean

    suspend fun load(url: String)

    /** 执行脚本，返回桥接回来的 JSON 文本。 */
    suspend fun evaluate(script: String): String

    fun detach()

    fun attach()

    fun destroy()
}

/**
 * 站点侧车。冷却期内取流、以及收藏查询走这里，好带上确认页留下的 cookie。
 * 空闲 [SidecarIdlePolicy.seconds] 秒后拆掉网页视图，cookie 仍留在默认存储里。
 */
class SiteSidecar(
    private val page: SidecarPage,
    val entryUrl: String,
    private val scope: CoroutineScope,
    private val beforeLoad: suspend () -> Unit = {},
    private val scheduleIdle: (Long, () -> Unit) -> IdleHandle = { delayMs, block ->
        val job = scope.launch {
            delay(delayMs)
            block()
        }
        IdleHandle { job.cancel() }
    },
) {
    private var suspended = false
    private var busy = 0
    private var loading: Deferred<Unit>? = null
    private var idle: IdleHandle? = null

    fun suspendPage() {
        suspended = true
        page.detach()
    }

    fun resumeIfNeeded() {
        suspended = false
        if (page.loaded) page.attach()
    }

    fun close() {
        idle?.cancel()
        idle = null
        loading = null
        page.destroy()
    }

    suspend fun resolve(username: String): StreamTicket {
        val raw = evaluate(RESOLVE_JS, mapOf("username" to username))
        val (status, data) = sidecarPayload(raw)
        return try {
            StreamDecoder.decode(status, data).withRecoveredUrl(jsonStringField(data, "url"))
        } catch (_: StreamDecodeError.Challenge) {
            throw SidecarError.Challenge
        } catch (error: StreamDecodeError) {
            if (error.failure != null) throw error
            throw SidecarError.Script
        } catch (_: SidecarError) {
            throw SidecarError.Script
        }
    }

    /** 在侧车页面里发 GET。收藏被拦住之后用它复用通关状态。 */
    suspend fun get(url: String): HTTPResult {
        val raw = evaluate(GET_JS, mapOf("url" to url))
        val (status, data) = sidecarPayload(raw)
        return HTTPResult(status = status, data = data, headers = emptyMap())
    }

    private suspend fun evaluate(body: String, arguments: Map<String, String>): String {
        busy += 1
        idle?.cancel()
        idle = null
        try {
            ensureLoaded()
            if (!page.loaded && loading == null) throw SidecarError.Unavailable
            return page.evaluate(sidecarScript(body, arguments))
        } finally {
            busy -= 1
            scheduleIdleTeardown()
        }
    }

    private suspend fun ensureLoaded() {
        beforeLoad()
        if (page.loaded) return
        val existing = loading
        if (existing != null) {
            existing.await()
            return
        }
        val task = CompletableDeferred<Unit>()
        loading = task
        try {
            page.load(entryUrl)
            task.complete(Unit)
        } catch (error: Throwable) {
            loading = null
            task.completeExceptionally(error)
            throw error
        }
    }

    private fun scheduleIdleTeardown() {
        idle?.cancel()
        idle = null
        if (busy != 0 || !page.loaded) return
        val delayMs = (SidecarIdlePolicy.seconds * 1000.0).toLong()
        idle = scheduleIdle(delayMs) {
            if (busy == 0) tearDown()
        }
    }

    private fun tearDown() {
        idle = null
        loading = null
        page.destroy()
    }

    companion object {
        private val RESOLVE_JS =
            """
            const response = await fetch(new URL("/get_edge_hls_url_ajax/", location.href).toString(), {
              method: "POST",
              credentials: "include",
              headers: {
                "X-Requested-With": "XMLHttpRequest",
                "Accept": "application/json",
                "Content-Type": "application/x-www-form-urlencoded; charset=UTF-8"
              },
              body: "room_slug=" + encodeURIComponent(username)
            });
            return { status: response.status, body: await response.text() };
            """.trimIndent()

        private val GET_JS =
            """
            const controller = new AbortController();
            const timer = setTimeout(() => controller.abort(), 10000);
            try {
              const response = await fetch(url, {
                credentials: "include",
                signal: controller.signal,
                headers: { "X-Requested-With": "XMLHttpRequest", "Accept": "application/json" }
              });
              return { status: response.status, body: await response.text() };
            } finally { clearTimeout(timer); }
            """.trimIndent()
    }
}

/**
 * 按请求号把结果交回对应的 evaluate。页面上的脚本不知道别的请求的号，不能串结果。
 * 号在这一版是递增的；不可猜测的一次性令牌另见后续收紧。
 */
internal class SidecarDeliverHub {
    private val slots = ConcurrentHashMap<String, CompletableDeferred<String>>()
    private val sequence = AtomicLong()

    fun open(): Pair<String, Deferred<String>> {
        val token = sequence.incrementAndGet().toString()
        val gate = CompletableDeferred<String>()
        slots[token] = gate
        return token to gate
    }

    fun deliver(token: String, payload: String): Boolean {
        val gate = slots.remove(token) ?: return false
        gate.complete(payload)
        return true
    }

    fun fail(token: String): Boolean {
        val gate = slots.remove(token) ?: return false
        gate.completeExceptionally(SidecarError.Script)
        return true
    }

    fun discard(token: String) {
        slots.remove(token)?.cancel()
    }
}

/** 包一层局部桥，把页面脚本里的 deliver/fail 绑到这次请求的号上。 */
internal fun wrapSidecarScript(script: String, token: String): String {
    val quoted = JSONObject.quote(token)
    return """
        (function() {
          var bridge = window.VelaSidecar;
          var VelaSidecar = {
            deliver: function(payload) { bridge.deliver($quoted, String(payload)); },
            fail: function(message) { bridge.fail($quoted, String(message)); }
          };
          $script
        })();
    """.trimIndent()
}

/** 页面世界里的稳定桥。只注册一次，不在每次求值时换同名接口。 */
internal class SidecarJsBridge(private val hub: SidecarDeliverHub) {
    @JavascriptInterface
    fun deliver(token: String, payload: String) {
        hub.deliver(token, payload)
    }

    @JavascriptInterface
    fun fail(token: String, message: String) {
        hub.fail(token)
    }
}

/** 把参数绑成常量，再跑一段会 return 结果的异步脚本。 */
internal fun sidecarScript(body: String, arguments: Map<String, String>): String {
    val bindings = arguments.entries.joinToString("\n") { (key, value) ->
        "const $key = ${JSONObject.quote(value)};"
    }
    return """
        (function() {
          $bindings
          var deliver = function(value) { VelaSidecar.deliver(JSON.stringify(value)); };
          var fail = function(err) { VelaSidecar.fail(String(err && err.message ? err.message : err)); };
          try {
            Promise.resolve((async function() {
              $body
            })()).then(deliver, fail);
          } catch (err) { fail(err); }
        })();
        """.trimIndent()
}

internal fun sidecarPayload(raw: String?): Pair<Int, ByteArray> {
    if (raw.isNullOrBlank()) throw SidecarError.Script
    val element = try {
        Json.parseToJsonElement(raw)
    } catch (_: Exception) {
        throw SidecarError.Script
    }
    val obj = element as? JsonObject ?: throw SidecarError.Script
    val status = obj["status"].asInt() ?: 0
    val body = obj["body"].asText() ?: throw SidecarError.Script
    return status to body.toByteArray(Charsets.UTF_8)
}

/**
 * 侧车窗口开着时，收藏用的聊天上下文和资料请求改走网页。
 * 目录仍走原来的客户端：确认页关掉之后由调用方重载。
 */
internal class SidecarAwareClient(
    private val direct: HTTPClient,
    private val sidecar: () -> SiteSidecar?,
    private val active: () -> Boolean,
) : HTTPClient {
    override suspend fun send(request: HTTPRequest): HTTPResult {
        val rewritten = rewrite(request)
        val side = sidecar()
        if (active() && side != null && routesViaSidecar(rewritten)) {
            return side.get(rewritten.url)
        }
        return direct.send(rewritten)
    }

    private fun rewrite(request: HTTPRequest): HTTPRequest {
        val url = vela.app.infra.Endpoints.rewrite(request.url)
        if (url == request.url) return request
        return HTTPRequest(url, request.method, request.headers, request.body, request.timeoutSeconds)
    }

    companion object {
        fun routesViaSidecar(request: HTTPRequest): Boolean {
            if (!request.method.equals("GET", ignoreCase = true)) return false
            val path = try {
                URI(request.url).path ?: return false
            } catch (_: Exception) {
                return false
            }
            return path.contains("/api/chatvideocontext/") || path.contains("/api/biocontext/")
        }
    }
}

/**
 * 离屏 WebView。只在 [load] 时创建，测试不调用就不会碰网络。
 * 宿主可选：挂到窗口外，页面才加载得完。
 */
class WebSidecarPage(
    private val context: Context,
    private val root: String,
    private val host: FrameLayout? = null,
) : SidecarPage {
    override var loaded: Boolean = false
        private set

    private var webView: WebView? = null
    private var destroyed = false
    private var loadGate: CompletableDeferred<Unit>? = null
    private val hub = SidecarDeliverHub()

    override suspend fun load(url: String) {
        if (destroyed) throw SidecarError.Unavailable
        try {
            withContext(Dispatchers.Main) {
                if (destroyed) throw SidecarError.Unavailable
                val view = ensureWebView()
                val gate = CompletableDeferred<Unit>()
                loadGate = gate
                view.loadUrl(url)
                withTimeout(LOAD_TIMEOUT_MS) { gate.await() }
                loaded = true
            }
        } catch (_: TimeoutCancellationException) {
            throw SidecarError.TimedOut
        }
    }

    override suspend fun evaluate(script: String): String = try {
        withContext(Dispatchers.Main) {
            val view = webView ?: throw SidecarError.Unavailable
            val (token, gate) = hub.open()
            try {
                withTimeout(EVAL_TIMEOUT_MS) {
                    view.evaluateJavascript(wrapSidecarScript(script, token), null)
                    gate.await()
                }
            } finally {
                hub.discard(token)
            }
        }
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        if (cancelled is TimeoutCancellationException) throw SidecarError.TimedOut
        throw cancelled
    }

    override fun detach() {
        val view = webView ?: return
        runOnMain {
            view.stopLoading()
            view.onPause()
            (view.parent as? FrameLayout)?.removeView(view)
        }
    }

    override fun attach() {
        val view = webView ?: return
        runOnMain {
            if (view.parent == null) addOffscreen(view)
            view.onResume()
        }
    }

    override fun destroy() {
        destroyed = true
        loaded = false
        val view = webView
        webView = null
        loadGate?.cancel()
        loadGate = null
        if (view == null) return
        runOnMain {
            view.stopLoading()
            view.removeJavascriptInterface(BRIDGE)
            (view.parent as? FrameLayout)?.removeView(view)
            view.destroy()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun ensureWebView(): WebView {
        webView?.let { return it }
        val view = WebView(context)
        view.settings.javaScriptEnabled = true
        view.settings.domStorageEnabled = true
        view.settings.mediaPlaybackRequiresUserGesture = true
        view.settings.userAgentString = UserAgent.safari
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(view, true)
        // 桥在第一次 loadUrl 之前挂上。WebView 要到下一次页面加载才把注入对象交给 JavaScript，
        // 加载完成后再 addJavascriptInterface，当前页的 deliver/fail 调不到。
        view.addJavascriptInterface(SidecarJsBridge(hub), BRIDGE)
        view.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                loadGate?.complete(Unit)
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                if (request?.isForMainFrame != true) return
                val gate = loadGate ?: return
                if (!gate.isCompleted) gate.completeExceptionally(SidecarError.Unavailable)
            }
        }
        addOffscreen(view)
        webView = view
        return view
    }

    private fun addOffscreen(view: WebView) {
        val parent = host ?: return
        if (view.parent === parent) return
        (view.parent as? FrameLayout)?.removeView(view)
        parent.addView(
            view,
            FrameLayout.LayoutParams(800, 600).apply { topMargin = -4200 },
        )
    }

    companion object {
        private const val BRIDGE = "VelaSidecar"
        private const val LOAD_TIMEOUT_MS = 18_000L
        private const val EVAL_TIMEOUT_MS = 18_000L

        fun entryUrl(root: String): String {
            val trimmed = root.trim().trimEnd('/')
            return "$trimmed/auth/login/?next=/"
        }
    }
}

private fun runOnMain(block: () -> Unit) {
    if (Looper.myLooper() == Looper.getMainLooper()) {
        block()
    } else {
        android.os.Handler(Looper.getMainLooper()).post(block)
    }
}

private fun kotlinx.serialization.json.JsonElement?.asInt(): Int? {
    val primitive = this as? JsonPrimitive ?: return null
    if (this is JsonNull) return null
    return primitive.content.toIntOrNull()
}

private fun kotlinx.serialization.json.JsonElement?.asText(): String? {
    if (this == null || this is JsonNull) return null
    val primitive = this as? JsonPrimitive ?: return null
    return primitive.contentOrNull
}

/** 侧车入口。根地址来自覆盖后的站点；默认是生产根，只有显式加载才会发出请求。 */
internal fun sidecarEntryForRoot(root: String): String = WebSidecarPage.entryUrl(root)

internal fun looksLikeChallengeText(data: ByteArray): Boolean = looksLikeChallenge(data)

private val localeRoot = Locale.ROOT

internal fun hostOf(url: String): String? =
    try {
        URI(url).host?.lowercase(localeRoot)
    } catch (_: Exception) {
        null
    }
