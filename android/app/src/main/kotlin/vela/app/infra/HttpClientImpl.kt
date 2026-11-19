package vela.app.infra

import java.net.HttpURLConnection
import java.net.Socket
import java.net.URL
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.suspendCancellableCoroutine
import vela.core.base.net.HTTPClient
import vela.core.base.net.HTTPRequest
import vela.core.base.net.HTTPResult

/**
 * 进程内的 HTTP 客户端。不安装 [java.net.CookieHandler]，也不在请求之间保留 cookie。
 * 超时上限 12 秒；调用方给出的更短超时（例如聊天上下文的 8 秒）照用。
 */
class HttpClientImpl : HTTPClient {
    override suspend fun send(request: HTTPRequest): HTTPResult {
        val connection = URL(request.url).openConnection() as HttpURLConnection
        var finished = false
        return try {
            val result = suspendCancellableCoroutine { continuation ->
                continuation.invokeOnCancellation { abortConnection(connection) }
                Dispatchers.IO.asExecutor().execute {
                    try {
                        val value = execute(connection, request)
                        continuation.resume(value) { _, _, _ -> abortConnection(connection) }
                    } catch (error: Throwable) {
                        if (continuation.isActive) {
                            continuation.resumeWithException(error)
                        } else {
                            abortConnection(connection)
                        }
                    }
                }
            }
            finished = true
            result
        } finally {
            if (finished) {
                try {
                    connection.disconnect()
                } catch (_: Throwable) {
                }
            } else {
                abortConnection(connection)
            }
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

        fun execute(connection: HttpURLConnection, request: HTTPRequest): HTTPResult {
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
            return HTTPResult(status = status, data = data, headers = readHeaders(connection))
        }

        /**
         * 协程取消不会打断 HttpURLConnection 的阻塞读。
         * 先关掉底层 socket（桌面 JDK 的 disconnect 会去抢读线程正握着的锁），再 disconnect。
         * 都放在单独线程上，取消方不会被这把锁拖住。
         */
        fun abortConnection(connection: HttpURLConnection) {
            val worker = Thread({
                closeUnderlyingSocket(connection)
                try {
                    connection.disconnect()
                } catch (_: Throwable) {
                }
            }, "vela-http-abort")
            worker.isDaemon = true
            worker.start()
        }

        private fun closeUnderlyingSocket(connection: HttpURLConnection) {
            try {
                var type: Class<*>? = connection.javaClass
                var httpField: java.lang.reflect.Field? = null
                while (type != null && httpField == null) {
                    httpField = type.declaredFields.firstOrNull { it.name == "http" }
                    type = type.superclass
                }
                val http = httpField?.let {
                    it.isAccessible = true
                    it.get(connection)
                } ?: return
                var socketType: Class<*>? = http.javaClass
                var socketField: java.lang.reflect.Field? = null
                while (socketType != null && socketField == null) {
                    socketField = socketType.declaredFields.firstOrNull { it.name == "serverSocket" }
                    socketType = socketType.superclass
                }
                val socket = socketField?.let {
                    it.isAccessible = true
                    it.get(http)
                } as? Socket ?: return
                socket.close()
            } catch (_: Throwable) {
                // Android 没有这些字段。disconnect 仍然会关掉连接。
            }
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
