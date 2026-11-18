package vela.core.base.io

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.nanoseconds

/**
 * 并发执行一组请求，每个请求各自带超时：失败、超时或被取消的项返回 null，其余照常返回；
 * 结果顺序与输入一致。整体耗时不超过 `timeout`（再加取消收尾），不会被最慢的一个拖住。
 */
object TimedFanOut {
    suspend fun <Input, Output> run(
        inputs: List<Input>,
        timeout: Double,
        work: suspend (Input) -> Output,
    ): List<Output?> = coroutineScope {
        inputs.map { input ->
            async {
                withTimeoutOrNull(timeoutNanos(timeout).nanoseconds) {
                    try {
                        work(input)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        null
                    }
                }
            }
        }.awaitAll()
    }

    /** 对齐 Swift `UInt64(max(seconds, 0) * 1_000_000_000)` 的向零截断。 */
    private fun timeoutNanos(seconds: Double): Long {
        if (seconds <= 0.0) return 0L
        val raw = seconds * 1_000_000_000.0
        if (raw >= Long.MAX_VALUE.toDouble()) return Long.MAX_VALUE
        return raw.toLong()
    }
}
