package vela.core.curation

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.isActive

/**
 * 同一时间只跑一批：新请求到来时取消在途批次，等它收尾后再开新批。
 * 旧批次的调用方改拿最新一批的结果。旧批次是否还能写回看 [isCurrent]。
 *
 * Swift 版隔离在 MainActor 上。这里用锁把登记做成原子的，工作本身跑在独立作用域里，
 * 不跟调用方的协程一起被结构化取消（对应 Swift 的非结构化 `Task`）。
 */
class SupersedingRunner<Value> {
    private val lock = ReentrantLock()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var seq = 0
    private var latestId = 0
    private var latest: Deferred<Value>? = null
    private var latestDone = true

    /** 最新一批还没跑完。 */
    val isRunning: Boolean
        get() = lock.withLock { !latestDone }

    fun isCurrent(id: Int): Boolean = lock.withLock { latestId == id }

    suspend fun run(work: suspend (Int) -> Value): Value {
        val gate = CompletableDeferred<Unit>()
        val mine: Deferred<Value>
        val id: Int
        lock.withLock {
            seq += 1
            id = seq
            val previous = latest
            previous?.cancel()
            latestDone = false
            latestId = id
            val task = scope.async {
                try {
                    gate.await()
                    if (previous != null) {
                        try {
                            previous.await()
                        } catch (cancelled: CancellationException) {
                            if (!isActive) throw cancelled
                        }
                    }
                    work(id)
                } finally {
                    lock.withLock {
                        if (latestId == id) latestDone = true
                    }
                }
            }
            latest = task
            gate.complete(Unit)
            mine = task
        }
        var waitingId = id
        var waiting = mine
        while (true) {
            try {
                val value = waiting.await()
                val newer = newerThan(waitingId) ?: return value
                waiting = newer.first
                waitingId = newer.second
            } catch (cancelled: CancellationException) {
                if (!coroutineContext.isActive) throw cancelled
                val newer = newerThan(waitingId) ?: throw cancelled
                waiting = newer.first
                waitingId = newer.second
            }
        }
    }

    private fun newerThan(waitingId: Int): Pair<Deferred<Value>, Int>? = lock.withLock {
        val current = latest
        if (current != null && latestId > waitingId) current to latestId else null
    }
}
