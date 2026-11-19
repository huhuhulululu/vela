package vela.app.infra

import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import vela.core.base.net.HTTPClient
import vela.core.base.net.HTTPRequest
import vela.core.base.net.HTTPResult

/**
 * 进程内的 HTTP 客户端。不安装 [java.net.CookieHandler]，也不在请求之间保留 cookie。
 * 超时上限 12 秒；调用方给出的更短超时（例如聊天上下文的 8 秒）照用。
 */
class HttpClientImpl : HTTPClient {
    override suspend fun send(request: HTTPRequest): HTTPResult = withContext(Dispatchers.IO) {
        val connection = (URL(request.url).openConnection() as HttpURLConnection)
        try {
            applyTimeouts(connection, request.timeoutSeconds)
            connection.instanceFollowRedirects = true
            connection.useCaches = false
            connection.requestMethod = request.method.uppercase()
            for ((name, value) in request.headers) {
                connection.setRequestProperty(name, value)
            }
            val body = request.body
            if (body != null) {
                connection.doOutput = true
                connection.outputStream.use { it.write(body) }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val data = stream?.use { it.readBytes() } ?: ByteArray(0)
            HTTPResult(status = status, data = data, headers = readHeaders(connection))
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        const val TIMEOUT_CAP_MILLIS: Int = 12_000

        fun resolveTimeoutMillis(timeoutSeconds: Double): Int {
            if (timeoutSeconds.isNaN() || timeoutSeconds <= 0.0 || timeoutSeconds > 12.0) {
                return TIMEOUT_CAP_MILLIS
            }
            return (timeoutSeconds * 1000.0).roundToInt().coerceAtLeast(1)
        }

        fun applyTimeouts(connection: HttpURLConnection, timeoutSeconds: Double) {
            val millis = resolveTimeoutMillis(timeoutSeconds)
            connection.connectTimeout = millis
            connection.readTimeout = millis
        }

        private fun readHeaders(connection: HttpURLConnection): Map<String, String> {
            val headers = LinkedHashMap<String, String>()
            for ((name, values) in connection.headerFields) {
                if (name != null) headers[name] = values.joinToString(",")
            }
            return headers
        }
    }
}
