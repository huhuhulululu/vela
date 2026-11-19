package vela.app.infra

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URL
import java.util.concurrent.locks.LockSupport
import kotlin.coroutines.cancellation.CancellationException
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
 * 总期限从进入 [send] 开始，排队、连接、上传、响应头和正文共用这一段。
 */
class HttpClientImpl : HTTPClient {
    override suspend fun send(request: HTTPRequest): HTTPResult {
        val deadline = RequestDeadline.after(resolveTimeoutMillis(request.timeoutSeconds))
        val connection = URL(request.url).openConnection() as HttpURLConnection
        val call = LiveCall(connection, deadline)
        val guard = DeadlineGuard(deadline) { call.expire() }
        guard.start()
        var finished = false
        return try {
            val result = suspendCancellableCoroutine { continuation ->
                continuation.invokeOnCancellation { call.cancel() }
                Dispatchers.IO.asExecutor().execute {
                    try {
                        when (call.claim()) {
                            LiveCall.Claim.CANCELLED -> Unit
                            LiveCall.Claim.EXPIRED -> {
                                if (continuation.isActive) {
                                    continuation.resumeWithException(SocketTimeoutException("deadline"))
                                }
                            }
                            LiveCall.Claim.RUN -> {
                                try {
                                    val value = execute(connection, request, deadline, call)
                                    call.checkpoint()
                                    continuation.resume(value) { _, _, _ -> call.cancel() }
                                } catch (error: Throwable) {
                                    if (continuation.isActive) {
                                        continuation.resumeWithException(mapCallError(error, deadline, call))
                                    } else {
                                        call.cancel()
                                    }
                                }
                            }
                        }
                    } finally {
                        call.finish()
                    }
                }
            }
            finished = true
            result
        } finally {
            guard.close()
            if (finished) {
                try {
                    connection.disconnect()
                } catch (_: Throwable) {
                }
            } else {
                call.cancel()
            }
        }
    }

    private class LiveCall(
        private val connection: HttpURLConnection,
        private val deadline: RequestDeadline,
    ) {
        enum class Claim { RUN, CANCELLED, EXPIRED }

        private val lock = Any()
        private var cancelled = false
        private var expired = false
        private var started = false
        private var finished = false

        fun claim(): Claim = synchronized(lock) {
            if (cancelled) return Claim.CANCELLED
            if (expired || deadline.expired()) return Claim.EXPIRED
            started = true
            Claim.RUN
        }

        fun checkpoint() {
            val failure = synchronized(lock) {
                when {
                    cancelled -> CancellationException("cancelled")
                    expired || deadline.expired() -> SocketTimeoutException("deadline")
                    else -> null
                }
            }
            if (failure != null) throw failure
        }

        fun cancel() {
            val live = synchronized(lock) {
                cancelled = true
                started && !finished
            }
            if (live) abortUntilQuiet(connection) { synchronized(lock) { !finished } }
        }

        fun expire() {
            val live = synchronized(lock) {
                expired = true
                started && !finished && !cancelled
            }
            if (live) abortConnection(connection)
        }

        fun finish() {
            synchronized(lock) { finished = true }
        }

        fun cancelled(): Boolean = synchronized(lock) { cancelled }
    }

    private class RequestDeadline private constructor(private val deadlineNanos: Long) {
        fun remainingMillis(now: Long = System.nanoTime()): Int {
            val left = deadlineNanos - now
            if (left <= 0L) throw SocketTimeoutException("deadline")
            val millis = left / 1_000_000L
            if (millis < 1L) return 1
            return millis.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        }

        fun expired(now: Long = System.nanoTime()): Boolean = now >= deadlineNanos

        fun checkpoint() {
            if (expired()) throw SocketTimeoutException("deadline")
        }

        fun awaitNanos(now: Long = System.nanoTime()): Long = (deadlineNanos - now).coerceAtLeast(0L)

        companion object {
            fun after(timeoutMillis: Int): RequestDeadline =
                RequestDeadline(System.nanoTime() + timeoutMillis.toLong() * 1_000_000L)
        }
    }

    private class DeadlineGuard(
        private val deadline: RequestDeadline,
        private val onElapsed: () -> Unit,
    ) : AutoCloseable {
        private val thread = Thread({
            try {
                while (!deadline.expired()) {
                    if (Thread.interrupted()) return@Thread
                    val left = deadline.awaitNanos()
                    if (left <= 0L) break
                    LockSupport.parkNanos(left)
                    if (Thread.interrupted()) return@Thread
                }
                if (!Thread.currentThread().isInterrupted) onElapsed()
            } catch (_: InterruptedException) {
            }
        }, "vela-http-deadline")

        fun start() {
            thread.isDaemon = true
            thread.start()
        }

        override fun close() {
            thread.interrupt()
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
            val deadline = RequestDeadline.after(resolveTimeoutMillis(request.timeoutSeconds))
            val call = LiveCall(connection, deadline)
            call.claim()
            return execute(connection, request, deadline, call)
        }

        private fun execute(
            connection: HttpURLConnection,
            request: HTTPRequest,
            deadline: RequestDeadline,
            call: LiveCall,
        ): HTTPResult {
            fun arm() {
                call.checkpoint()
                val millis = deadline.remainingMillis()
                connection.connectTimeout = millis
                connection.readTimeout = millis
                applySocketTimeout(connection, millis)
            }
            arm()
            connection.instanceFollowRedirects = true
            connection.useCaches = false
            connection.requestMethod = request.method.uppercase()
            for ((name, value) in request.headers) {
                connection.setRequestProperty(name, value)
            }
            val body = request.body
            if (body != null) {
                arm()
                connection.doOutput = true
                connection.outputStream.use { it.write(body) }
            }
            arm()
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val data = stream?.use { readBounded(it, connection, deadline, call) } ?: ByteArray(0)
            call.checkpoint()
            return HTTPResult(status = status, data = data, headers = readHeaders(connection))
        }

        private fun readBounded(
            stream: InputStream,
            connection: HttpURLConnection,
            deadline: RequestDeadline,
            call: LiveCall,
        ): ByteArray {
            val out = ByteArrayOutputStream()
            val buf = ByteArray(8 * 1024)
            while (true) {
                call.checkpoint()
                val millis = deadline.remainingMillis()
                connection.readTimeout = millis
                applySocketTimeout(connection, millis)
                val n = stream.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
            }
            call.checkpoint()
            return out.toByteArray()
        }

        private fun mapCallError(error: Throwable, deadline: RequestDeadline, call: LiveCall): Throwable {
            if (error is CancellationException) return error
            if (call.cancelled() && !deadline.expired()) {
                return CancellationException("cancelled").apply { initCause(error) }
            }
            if (deadline.expired() || error is SocketTimeoutException) {
                return if (error is SocketTimeoutException) {
                    error
                } else {
                    SocketTimeoutException("deadline").apply { initCause(error) }
                }
            }
            return error
        }

        private fun applySocketTimeout(connection: HttpURLConnection, millis: Int) {
            val socket = underlyingSocket(connection) ?: return
            try {
                socket.soTimeout = millis
            } catch (_: Throwable) {
            }
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

        /**
         * 取消落在「已标记开始、套接字还没建出来」时，单次 disconnect 没有可关的连接。
         * 同一个线程接着关，直到调用结束或套接字被关掉，避免排队任务随后把请求发出去。
         */
        private fun abortUntilQuiet(connection: HttpURLConnection, active: () -> Boolean) {
            val worker = Thread({
                val until = System.nanoTime() + 3_000_000_000L
                var spins = 0
                while (active() && System.nanoTime() < until) {
                    val closed = closeUnderlyingSocket(connection)
                    try {
                        connection.disconnect()
                    } catch (_: Throwable) {
                    }
                    if (closed || !active()) break
                    spins += 1
                    if (spins > 2_000) {
                        try {
                            Thread.sleep(2)
                        } catch (_: InterruptedException) {
                            break
                        }
                    } else {
                        Thread.yield()
                    }
                }
            }, "vela-http-abort")
            worker.isDaemon = true
            worker.start()
        }

        private fun closeUnderlyingSocket(connection: HttpURLConnection): Boolean {
            val socket = underlyingSocket(connection) ?: return false
            return try {
                socket.close()
                true
            } catch (_: Throwable) {
                false
            }
        }

        private fun underlyingSocket(connection: HttpURLConnection): Socket? {
            return try {
                var type: Class<*>? = connection.javaClass
                var httpField: java.lang.reflect.Field? = null
                while (type != null && httpField == null) {
                    httpField = type.declaredFields.firstOrNull { it.name == "http" }
                    type = type.superclass
                }
                val http = httpField?.let {
                    it.isAccessible = true
                    it.get(connection)
                } ?: return null
                var socketType: Class<*>? = http.javaClass
                var socketField: java.lang.reflect.Field? = null
                while (socketType != null && socketField == null) {
                    socketField = socketType.declaredFields.firstOrNull { it.name == "serverSocket" }
                    socketType = socketType.superclass
                }
                socketField?.let {
                    it.isAccessible = true
                    it.get(http)
                } as? Socket
            } catch (_: Throwable) {
                null
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
