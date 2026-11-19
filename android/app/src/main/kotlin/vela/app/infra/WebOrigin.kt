package vela.app.infra

import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.ByteArrayInputStream
import java.net.URI
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

/**
 * 子资源（含重定向后的地址）不在来源里就直接挡掉，不再发到网络上。
 * 主框架导航留给 [shouldOverrideUrlLoading] 那一层。
 */
internal open class OriginWebViewClient(
    private val siteRoot: String,
    private val onFinished: (String?) -> Unit = {},
    private val onMainFrameError: () -> Unit = {},
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
        if (request == null || request.isForMainFrame) return null
        val url = request.url?.toString() ?: return blockedWebResponse()
        if (!WebOriginPolicy.allows(url, siteRoot)) return blockedWebResponse()
        return null
    }

    override fun onPageFinished(view: WebView?, url: String?) {
        onFinished(url)
    }

    override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
        if (request?.isForMainFrame != true) return
        onMainFrameError()
    }
}
