package vela.core.hls

import java.time.Instant
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import vela.core.base.play.StreamRendition

/**
 * 一个播放源的续期协调。视频和音轨同时 403 时共用一次续期。
 *
 * 互斥用 [Mutex]。在途的那次是共享的 [CompletableDeferred]：加入方等到它结束时，
 * `apply` 已经跑完。`cancel()` 作废在途结果并不再接受注册，直到 `resume()`。
 * 预算不随取消清零，但取消而没有完成的那一次会退回。
 *
 * [renewBlocking] 给加载线程用。线程中断或 [cancel] 都会在 200 毫秒内返回，
 * 不必等 `fetch` 自己结束。超时只停止等待，不取消共享续期。
 */
class RenewalCoordinator(
    private val budget: RenewalBudget = RenewalBudget(),
) {
    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var generation = 0
    private var accepting = true
    private var recoveries = 0
    private var task: CompletableDeferred<Boolean>? = null
    private var renewalJob: Job? = null

    /** 有续期在途，或有等续期的重取还没结束。 */
    val isActive: Boolean
        get() = withMutex { task != null || recoveries > 0 }

    /** 当前取消代次。提交方注册前读取，写入前用 [isCurrent] 核对。 */
    val epoch: Int
        get() = withMutex { generation }

    fun isCurrent(epoch: Int): Boolean = withMutex { generation == epoch && accepting }

    /**
     * 在途就等它，否则预算允许时发起。返回时 `apply` 已结束：成功表示新地址已写入。
     * 预算不允许、或已停止接受注册，返回 false。
     */
    suspend fun renew(
        now: Instant = Instant.now(),
        fetch: suspend () -> StreamRendition?,
        apply: (StreamRendition) -> Boolean,
    ): Boolean {
        val attempt = begin(now, fetch, apply) ?: return false
        return attempt.deferred.await()
    }

    /**
     * 阻塞等到续期结束，最多 [timeoutMs] 毫秒。
     * 当前线程已被中断，或等待期间被中断，返回 false，并保持中断标志。
     * [cancel] 使本次注册失效时也返回 false，不等 `fetch` 跑完。
     * 超时返回 false，共享续期继续，后面的加入方还能等到结果。
     */
    fun renewBlocking(
        timeoutMs: Long,
        now: Instant = Instant.now(),
        fetch: suspend () -> StreamRendition?,
        apply: (StreamRendition) -> Boolean,
    ): Boolean {
        if (Thread.currentThread().isInterrupted) return false
        val attempt = try {
            runBlocking { begin(now, fetch, apply) }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return false
        }
        if (attempt == null) return false
        return wait(attempt, timeoutMs)
    }

    /** 包住「续期 + 续期后重取」。这段时间 [isActive] 为真，包括 body 抛错。 */
    suspend fun <T> recovering(body: suspend () -> T): T {
        mutex.withLock { recoveries += 1 }
        try {
            return body()
        } finally {
            withContext(NonCancellable) {
                mutex.withLock { recoveries -= 1 }
            }
        }
    }

    /** 代次加一，停止接受新注册，并取消在途任务。在途任务若还没写入，结果作废并退回预算。 */
    fun cancel() {
        val job = withMutex {
            generation += 1
            accepting = false
            val current = renewalJob
            renewalJob = null
            task = null
            current
        }
        job?.cancel()
    }

    /** 源重新开始刷新：恢复接受续期。不把旧代次救回来。 */
    fun resume() {
        withMutex { accepting = true }
    }

    private suspend fun begin(
        now: Instant,
        fetch: suspend () -> StreamRendition?,
        apply: (StreamRendition) -> Boolean,
    ): Attempt? = mutex.withLock {
        if (!accepting) return@withLock null
        val existing = task
        if (existing != null) return@withLock Attempt(existing, generation)
        if (!budget.take(now)) return@withLock null
        val mine = generation
        val deferred = CompletableDeferred<Boolean>()
        val job = scope.launch {
            runRenewal(now, mine, deferred, fetch, apply)
        }
        task = deferred
        renewalJob = job
        Attempt(deferred, mine)
    }

    private suspend fun runRenewal(
        now: Instant,
        mine: Int,
        deferred: CompletableDeferred<Boolean>,
        fetch: suspend () -> StreamRendition?,
        apply: (StreamRendition) -> Boolean,
    ) {
        var applied = false
        var cancelled = false
        try {
            val fresh = fetch()
            cancelled = !running()
            if (!cancelled && fresh != null) applied = apply(fresh)
        } catch (_: CancellationException) {
            cancelled = true
        } catch (_: Exception) {
            cancelled = !running()
            applied = false
        } finally {
            withContext(NonCancellable) {
                try {
                    if (!applied && cancelled) refund(now)
                } finally {
                    try {
                        finish(mine)
                    } finally {
                        if (!deferred.isCompleted) deferred.complete(applied)
                    }
                }
            }
        }
    }

    private fun wait(attempt: Attempt, timeoutMs: Long): Boolean {
        val deadline = System.nanoTime() + timeoutMs.coerceAtLeast(0L) * 1_000_000L
        while (true) {
            if (Thread.currentThread().isInterrupted) return false
            if (attempt.deferred.isCompleted) {
                return completedValue(attempt)
            }
            if (!isCurrent(attempt.registeredEpoch)) return false
            val remainingNs = deadline - System.nanoTime()
            if (remainingNs <= 0L) return false
            val sliceMs = (remainingNs / 1_000_000L).coerceIn(1L, WAIT_SLICE_MS)
            try {
                val value = runBlocking {
                    withTimeoutOrNull(sliceMs) { attempt.deferred.await() }
                }
                if (value != null) return value
                if (attempt.deferred.isCompleted) return completedValue(attempt)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
    }

    private fun completedValue(attempt: Attempt): Boolean = runBlocking { attempt.deferred.await() }

    private suspend fun running(): Boolean = coroutineContext[Job]?.isActive == true

    private suspend fun refund(date: Instant) {
        mutex.withLock { budget.refund(date) }
    }

    private suspend fun finish(mine: Int) {
        mutex.withLock {
            if (generation == mine) {
                task = null
                renewalJob = null
            }
        }
    }

    private fun <T> withMutex(block: () -> T): T = runBlocking { mutex.withLock { block() } }

    private class Attempt(
        val deferred: CompletableDeferred<Boolean>,
        val registeredEpoch: Int,
    )

    private companion object {
        const val WAIT_SLICE_MS: Long = 50L
    }
}
