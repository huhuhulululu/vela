package vela.core.base.net

data class HTTPResult(
    val status: Int,
    val data: ByteArray,
    val headers: Map<String, String> = emptyMap(),
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is HTTPResult) return false
        return status == other.status && data.contentEquals(other.data) && headers == other.headers
    }

    override fun hashCode(): Int {
        var result = status
        result = 31 * result + data.contentHashCode()
        result = 31 * result + headers.hashCode()
        return result
    }
}

interface HTTPClient {
    suspend fun send(request: HTTPRequest): HTTPResult
}

class HTTPRequest(
    val url: String,
    val method: String = "GET",
    val headers: Map<String, String> = emptyMap(),
    val body: ByteArray? = null,
    val timeoutSeconds: Double = 60.0,
) {
    fun header(name: String): String? =
        headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

    fun withMethod(method: String): HTTPRequest = HTTPRequest(url, method, headers, body, timeoutSeconds)

    fun withTimeout(seconds: Double): HTTPRequest = HTTPRequest(url, method, headers, body, seconds)

    fun withHeader(name: String, value: String): HTTPRequest {
        val next = LinkedHashMap(headers)
        val existing = next.keys.firstOrNull { it.equals(name, ignoreCase = true) }
        if (existing != null && existing != name) next.remove(existing)
        next[name] = value
        return HTTPRequest(url, method, next, body, timeoutSeconds)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is HTTPRequest) return false
        return url == other.url &&
            method == other.method &&
            headers == other.headers &&
            timeoutSeconds == other.timeoutSeconds &&
            (body == null && other.body == null || body != null && other.body != null && body.contentEquals(other.body))
    }

    override fun hashCode(): Int {
        var result = url.hashCode()
        result = 31 * result + method.hashCode()
        result = 31 * result + headers.hashCode()
        result = 31 * result + (body?.contentHashCode() ?: 0)
        result = 31 * result + timeoutSeconds.hashCode()
        return result
    }
}

object UserAgent {
    const val safari: String =
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.2 Safari/605.1.15"
}

object HTTPHeaders {
    fun applyBrowser(request: HTTPRequest, referer: String): HTTPRequest =
        request
            .withHeader("User-Agent", UserAgent.safari)
            .withHeader("Accept", "application/json, text/plain, */*")
            .withHeader("X-Requested-With", "XMLHttpRequest")
            .withHeader("Referer", referer)
            .withHeader("Origin", "https://chaturbate.com")
            .withTimeout(12.0)
}
