package vela.app.ui

import android.annotation.SuppressLint
import android.net.Uri
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import java.net.URI
import vela.app.BuildConfig
import vela.app.infra.Endpoints
import vela.core.base.net.UserAgent

/**
 * 质询页只有这一份 WebView 配置，和 iOS SiteWebView 对齐：
 * Safari UA、DOM storage、媒体需手势、离开时由调用方 destroy。
 * 导航只留站点根、质询 CDN，debug/perf 再加回环与模拟器宿主机。
 */
@SuppressLint("SetJavaScriptEnabled")
internal fun configureChallengeWeb(view: WebView, onProgress: (Float) -> Unit) {
    view.settings.javaScriptEnabled = true
    view.settings.domStorageEnabled = true
    view.settings.mediaPlaybackRequiresUserGesture = true
    view.settings.userAgentString = UserAgent.safari
    view.webViewClient = ChallengeNavigationClient()
    view.webChromeClient = object : WebChromeClient() {
        override fun onProgressChanged(web: WebView?, newProgress: Int) {
            onProgress(newProgress / 100f)
        }
    }
}

internal class ChallengeNavigationClient : WebViewClient() {
    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        return !challengeNavigationAllowed(request.url)
    }

    @Deprecated("旧 WebView 仍会走字符串重载")
    override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
        return !challengeNavigationAllowed(url)
    }
}

internal fun challengeNavigationAllowed(url: Uri?): Boolean {
    if (url == null) return false
    return challengeNavigationAllowed(
        raw = url.toString(),
        siteRoot = Endpoints.siteRoot(),
        allowExtraLoopback = challengeLoopbackEnabled(),
    )
}

internal fun challengeNavigationAllowed(url: String): Boolean =
    challengeNavigationAllowed(
        raw = url,
        siteRoot = Endpoints.siteRoot(),
        allowExtraLoopback = challengeLoopbackEnabled(),
    )

internal fun challengeNavigationAllowed(raw: String, siteRoot: String, allowExtraLoopback: Boolean): Boolean {
    val uri = try {
        URI(raw)
    } catch (_: Exception) {
        return false
    }
    if (uri.userInfo != null) return false
    val scheme = uri.scheme?.lowercase(java.util.Locale.ROOT) ?: return false
    val host = uri.host?.lowercase(java.util.Locale.ROOT) ?: return false
    val loopback = host == "127.0.0.1" || host == "localhost" || host == "10.0.2.2"
    val site = siteHosts(siteRoot)
    val cdn = host == "challenges.cloudflare.com" ||
        host.endsWith(".challenges.cloudflare.com") ||
        host == "static.cloudflareinsights.com"
    return when (scheme) {
        "https" -> host in site || cdn || (loopback && allowExtraLoopback)
        "http" -> loopback && (allowExtraLoopback || host in site)
        else -> false
    }
}

private fun challengeLoopbackEnabled(): Boolean =
    BuildConfig.DEBUG || BuildConfig.BUILD_TYPE == "perf"

private fun siteHosts(siteRoot: String): Set<String> {
    val host = try {
        URI(siteRoot).host?.lowercase(java.util.Locale.ROOT)
    } catch (_: Exception) {
        null
    } ?: return setOf("chaturbate.com", "www.chaturbate.com")
    val hosts = linkedSetOf(host)
    if (host == "chaturbate.com") hosts.add("www.chaturbate.com")
    if (host == "www.chaturbate.com") hosts.add("chaturbate.com")
    return hosts
}
