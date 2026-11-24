package vela.app.hooks

import java.net.URI
import vela.app.BuildConfig
import vela.app.infra.Endpoints as SiteEndpoints

/**
 * 用伪上游地址覆盖站点根。只接受 http 的回环或模拟器宿主机（10.0.2.2），
 * 避免 debug 包被指向真实站点。
 */
object Endpoints {
    fun installFromBuildConfig(): Boolean = installOverride(BuildConfig.VELA_UPSTREAM)

    fun installOverride(raw: String): Boolean {
        val accepted = accept(raw) ?: return false
        SiteEndpoints.overrideRoot(accepted)
        return true
    }

    internal fun accept(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        val uri = try {
            URI(trimmed)
        } catch (_: Exception) {
            return null
        }
        if (uri.scheme?.equals("http", ignoreCase = true) != true) return null
        val host = uri.host ?: return null
        if (host != "127.0.0.1" && !host.equals("localhost", ignoreCase = true) && host != "10.0.2.2") {
            return null
        }
        if (uri.rawUserInfo != null) return null
        val port = if (uri.port == -1) "" else ":${uri.port}"
        return "http://$host$port"
    }
}
