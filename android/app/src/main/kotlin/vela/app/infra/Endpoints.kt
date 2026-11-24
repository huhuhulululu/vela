package vela.app.infra

/**
 * 站点根地址。生产环境固定为 chaturbate；debug / perf 的 hooks 只在回环或模拟器宿主机上覆盖它。
 * 缩略图 CDN 不跟着改，避免把图片请求指到伪上游。
 */
object Endpoints {
    const val PRODUCTION_ROOT = "https://chaturbate.com"
    const val THUMB_ROOT = "https://thumb.live.mmcdn.com"

    @Volatile
    private var overriddenRoot: String? = null

    fun siteRoot(): String = overriddenRoot ?: PRODUCTION_ROOT

    fun overrideRoot(root: String) {
        overriddenRoot = root.trim().trimEnd('/')
    }

    fun clearOverride() {
        overriddenRoot = null
    }

    fun catalog(query: String): String = "${siteRoot()}/api/ts/roomlist/room-list/?$query"

    fun chatContext(username: String): String = "${siteRoot()}/api/chatvideocontext/$username/"

    fun referer(username: String): String = "${siteRoot()}/$username/"

    fun thumbnail(username: String): String = "$THUMB_ROOT/riw/$username.jpg"

    /** 只改写生产站点的绝对地址。其它主机（含缩略图 CDN）原样返回。 */
    fun rewrite(url: String): String {
        val root = siteRoot()
        if (root == PRODUCTION_ROOT) return url
        for (prefix in PRODUCTION_PREFIXES) {
            if (url.startsWith(prefix)) return root + url.substring(prefix.length)
        }
        return url
    }

    private val PRODUCTION_PREFIXES = listOf("https://chaturbate.com", "http://chaturbate.com")
}
