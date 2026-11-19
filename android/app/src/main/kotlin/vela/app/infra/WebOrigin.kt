package vela.app.infra

import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.ByteArrayInputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.Locale

/**
 * 侧车和质询页只访问站点本身，以及过确认页时绕不开的质询域名。
 * 不放行任意第三方。
 */
internal object WebOriginPolicy {
    val challengeHosts: Set<String> = setOf(
        "challenges.cloudflare.com",
        "static.cloudflareinsights.com",
    )

    fun allows(url: String, siteRoot: String): Boolean {
        val host = webHost(url) ?: return false
        val site = webHost(siteRoot) ?: return false
        if (host == site || isSubdomain(host, site)) return true
        if (host in challengeHosts || challengeHosts.any { isSubdomain(host, it) }) return true
        return false
    }

    private fun isSubdomain(host: String, parent: String): Boolean {
        if (parent.none { it.isLetter() }) return false
        return host.endsWith(".$parent")
    }
}

internal fun webHost(url: String): String? =
    try {
        URI(url).host?.lowercase(Locale.ROOT)
    } catch (_: Exception) {
        null
    }

/** 第一方 cookie 要留着（通关状态在里面）。第三方默认关掉。 */
internal fun applyFirstPartyCookiesOnly(view: WebView) {
    val cookies = CookieManager.getInstance()
    cookies.setAcceptCookie(true)
    cookies.setAcceptThirdPartyCookies(view, false)
}

internal fun blockedWebResponse(): WebResourceResponse =
    WebResourceResponse(
        "text/plain",
        "utf-8",
        403,
        "blocked",
        emptyMap(),
        ByteArrayInputStream(ByteArray(0)),
    )

internal fun unavailableWebResponse(): WebResourceResponse =
    WebResourceResponse(
        "text/plain",
        "utf-8",
        502,
        "unavailable",
        emptyMap(),
        ByteArrayInputStream(ByteArray(0)),
    )

/**
 * 主框架和子资源都先看来源。允许的 GET/HEAD 子资源自己跟随重定向，
 * 每一跳都要仍在允许的来源里；WebView 不会把重定向后的地址交回来。
 * 其它方法留回 WebView，这样 POST 正文不会丢。
 */
internal fun interceptWhitelisted(
    view: WebView?,
    request: WebResourceRequest?,
    allows: (String) -> Boolean,
): WebResourceResponse? {
    if (request == null) return blockedWebResponse()
    val url = request.url?.toString() ?: return blockedWebResponse()
    if (!allows(url)) {
        if (request.isForMainFrame) view?.post { view.stopLoading() }
        return blockedWebResponse()
    }
    if (request.isForMainFrame) return null
    val method = request.method?.uppercase(Locale.ROOT) ?: "GET"
    if (method != "GET" && method != "HEAD") return null
    return try {
        loadGuardedSubresource(url, method, request.requestHeaders ?: emptyMap(), allows)
    } catch (_: Exception) {
        unavailableWebResponse()
    }
}

internal fun loadGuardedSubresource(
    initialUrl: String,
    method: String,
    headers: Map<String, String>,
    allows: (String) -> Boolean,
): WebResourceResponse {
    var current = initialUrl
    var verb = method.uppercase(Locale.ROOT)
    var hops = 0
    while (hops < MAX_RESOURCE_HOPS) {
        if (!allows(current)) return blockedWebResponse()
        val connection = (URL(current).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = false
            connectTimeout = 15_000
            readTimeout = 15_000
            requestMethod = if (verb == "HEAD") "HEAD" else "GET"
            useCaches = false
        }
        try {
            forwardSubresourceHeaders(connection, headers, current)
            val status = connection.responseCode
            if (status in 300..399) {
                val location = connection.getHeaderField("Location")
                connection.disconnect()
                if (location.isNullOrBlank()) return blockedWebResponse()
                val next = try {
                    URL(URL(current), location).toString()
                } catch (_: Exception) {
                    return blockedWebResponse()
                }
                if (!allows(next)) return blockedWebResponse()
                if (status == 303) verb = "GET"
                current = next
                hops += 1
                continue
            }
            return subresourceResponse(connection, current, status)
        } catch (error: IOException) {
            try {
                connection.disconnect()
            } catch (_: Throwable) {
            }
            throw error
        }
    }
    return blockedWebResponse()
}

private const val MAX_RESOURCE_HOPS = 8

private fun forwardSubresourceHeaders(
    connection: HttpURLConnection,
    headers: Map<String, String>,
    url: String,
) {
    for ((name, value) in headers) {
        if (name.equals("Host", true) ||
            name.equals("Connection", true) ||
            name.equals("Content-Length", true) ||
            name.equals("Accept-Encoding", true) ||
            name.equals("Cookie", true)
        ) {
            continue
        }
        try {
            connection.setRequestProperty(name, value)
        } catch (_: IllegalStateException) {
        }
    }
    val cookie = headers.entries.firstOrNull { it.key.equals("Cookie", true) }?.value ?: readWebCookie(url)
    if (!cookie.isNullOrBlank()) {
        try {
            connection.setRequestProperty("Cookie", cookie)
        } catch (_: IllegalStateException) {
        }
    }
}

private fun readWebCookie(url: String): String? =
    try {
        CookieManager.getInstance().getCookie(url)
    } catch (_: Throwable) {
        null
    }

private fun storeWebCookies(connection: HttpURLConnection, url: String) {
    try {
        val manager = CookieManager.getInstance()
        val values = connection.headerFields.entries
            .filter { it.key != null && it.key.equals("Set-Cookie", true) }
            .flatMap { it.value }
        if (values.isEmpty()) return
        for (value in values) manager.setCookie(url, value)
        manager.flush()
    } catch (_: Throwable) {
    }
}

private fun subresourceResponse(connection: HttpURLConnection, url: String, status: Int): WebResourceResponse {
    storeWebCookies(connection, url)
    val contentType = connection.getHeaderField("Content-Type").orEmpty()
    val mime = contentType.substringBefore(';').trim().ifEmpty { "application/octet-stream" }
    val charset = Regex("charset=([^;]+)", RegexOption.IGNORE_CASE)
        .find(contentType)
        ?.groupValues
        ?.getOrNull(1)
        ?.trim()
        ?.trim('"')
        ?.ifEmpty { null }
    val reason = connection.responseMessage?.takeIf { it.isNotBlank() } ?: "OK"
    val headers = LinkedHashMap<String, String>()
    for ((name, values) in connection.headerFields) {
        if (name.isNullOrBlank()) continue
        if (name.equals("Content-Encoding", true) ||
            name.equals("Content-Length", true) ||
            name.equals("Transfer-Encoding", true) ||
            name.equals("Connection", true)
        ) {
            continue
        }
        headers[name] = values.joinToString(",")
    }
    val raw = try {
        if (status >= 400) connection.errorStream else connection.inputStream
    } catch (_: IOException) {
        null
    }
    val stream = ClosingConnectionStream(raw ?: ByteArrayInputStream(ByteArray(0)), connection)
    return WebResourceResponse(mime, charset, status, reason, headers, stream)
}

private class ClosingConnectionStream(
    source: InputStream,
    private val connection: HttpURLConnection,
) : FilterInputStream(source) {
    override fun close() {
        try {
            super.close()
        } finally {
            try {
                connection.disconnect()
            } catch (_: Throwable) {
            }
        }
    }
}

/**
 * 子资源（含重定向后的地址）不在来源里就直接挡掉，不再发到网络上。
 * 主框架 GET 仍由 [shouldOverrideUrlLoading] 取消；POST 不会走那一层，所以这里也校验主框架。
 * 页面完成时再核对实际地址，外站不能当成加载成功。
 */
internal open class OriginWebViewClient(
    private val siteRoot: String,
    private val onFinished: (String?) -> Unit = {},
    private val onMainFrameError: () -> Unit = {},
    private val onOriginRejected: () -> Unit = {},
) : WebViewClient() {
    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        if (!request.isForMainFrame) return false
        val url = request.url?.toString() ?: return true
        return !WebOriginPolicy.allows(url, siteRoot)
    }

    @Deprecated("Use the request overload")
    override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
        return !WebOriginPolicy.allows(url, siteRoot)
    }

    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
        return interceptWhitelisted(view, request) { WebOriginPolicy.allows(it, siteRoot) }
    }

    override fun onPageFinished(view: WebView?, url: String?) {
        val current = view?.url ?: url
        if (current.isNullOrBlank() || current.equals("about:blank", ignoreCase = true)) return
        if (!WebOriginPolicy.allows(current, siteRoot)) {
            onOriginRejected()
            onMainFrameError()
            return
        }
        onFinished(current)
    }

    override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
        if (request?.isForMainFrame != true) return
        onMainFrameError()
    }
}
