package vela.app.infra

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.LockSupport
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.suspendCancellableCoroutine
import vela.core.base.net.HTTPClient
import vela.core.base.net.HTTPRequest
import vela.core.base.net.HTTPResult

/**
 * 进程内的 HTTP 客户端。不安装 [java.net.CookieHandler]，也不在请求之间保留 cookie。
 * 超时上限 12 秒；调用方给出的更短超时（例如聊天上下文的 8 秒）照用。
 * 超时是空闲超时（与 iOS URLSession 的 timeoutIntervalForRequest 一致）：从进入 [send] 起，
 * 排队、连接、上传、等响应头共用一个超时；响应头和正文每到一段数据，期限从那一刻起再推后一个超时。
 * 另有总上限 [TOTAL_CAP_FACTOR] 倍超时，从进入 [send] 起算，滴流再慢也到点掐断。
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
                call.bind(continuation)
                continuation.invokeOnCancellation { call.cancel() }
                Dispatchers.IO.asExecutor().execute {
                    try {
                        when (call.claim()) {
                            LiveCall.Claim.CANCELLED -> Unit
                            LiveCall.Claim.EXPIRED -> call.failDeadline(continuation)
                            LiveCall.Claim.RUN -> {
                                try {
                                    val value = execute(connection, request, deadline, call)
                                    call.checkpoint()
                                    call.complete { continuation.resume(value) { _, _, _ -> call.cancel() } }
                                } catch (error: Throwable) {
                                    val delivered = call.complete {
                                        if (continuation.isActive) {
                                            continuation.resumeWithException(mapCallError(error, deadline, call))
                                        } else {
                                            call.cancel()
                                        }
                                    }
                                    if (!delivered) call.cancel()
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
        private val resumed = AtomicBoolean(false)
        private var waiter: CancellableContinuation<HTTPResult>? = null
        private var cancelled = false
        private var expired = false
        private var started = false
        private var finished = false

        fun bind(cont: CancellableContinuation<HTTPResult>) {
            val expireNow = synchronized(lock) {
                waiter = cont
                !cancelled && !started && !finished && (expired || deadline.expired())
            }
            if (expireNow) failDeadline(cont)
        }

        fun claim(): Claim = synchronized(lock) {
            if (cancelled) return Claim.CANCELLED
            if (expired || deadline.expired()) return Claim.EXPIRED
            started = true
            Claim.RUN
        }

        /** 期限到了就在这一刻结束调用方，不等 IO 线程把排队任务取出来。 */
        fun failDeadline(cont: CancellableContinuation<HTTPResult>) {
            if (!resumed.compareAndSet(false, true)) return
            synchronized(lock) { waiter = null }
            if (cont.isActive) cont.resumeWithException(SocketTimeoutException("deadline"))
        }

        fun complete(block: () -> Unit): Boolean {
            if (!resumed.compareAndSet(false, true)) return false
            block()
            return true
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
            val abort: Boolean
            val cont: CancellableContinuation<HTTPResult>?
            synchronized(lock) {
                expired = true
                abort = started && !finished && !cancelled
                cont = if (!cancelled && !finished) waiter else null
            }
            if (abort) abortConnection(connection)
            if (cont != null) failDeadline(cont)
        }

        fun finish() {
            synchronized(lock) { finished = true }
        }

        fun cancelled(): Boolean = synchronized(lock) { cancelled }
    }

    private class RequestDeadline private constructor(
        private val idleNanos: Long,
        private val capNanos: Long,
    ) {
        /** 当前期限。只会往后推，不越过 [capNanos]。看门线程每次醒来重读。 */
        @Volatile
        private var deadlineNanos: Long = minOf(System.nanoTime() + idleNanos, capNanos)

        /** 收到数据：期限推到此刻加一个空闲超时，不越过总上限。已过期的不复活。 */
        fun progress(now: Long = System.nanoTime()) {
            synchronized(this) {
                if (now >= deadlineNanos) return
                val next = minOf(now + idleNanos, capNanos)
                if (next > deadlineNanos) deadlineNanos = next
            }
        }

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
            fun after(timeoutMillis: Int): RequestDeadline {
                val idle = timeoutMillis.toLong() * 1_000_000L
                return RequestDeadline(idle, System.nanoTime() + idle * TOTAL_CAP_FACTOR)
            }
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

        /** 总上限 = 空闲超时 × 这个倍数（12 秒超时即 48 秒）。 */
        const val TOTAL_CAP_FACTOR: Int = 4

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
            deadline.progress()
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
                if (n > 0) deadline.progress()
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
