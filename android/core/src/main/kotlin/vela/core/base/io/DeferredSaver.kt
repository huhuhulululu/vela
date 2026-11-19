package vela.core.base.io

import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * 把频繁的保存合并成一次后台串行写：[schedule] 只记下最新值，延迟后在专用线程里写一次；
 * [flush] 同步写出未落盘的最新值（退出时用）。所有写都在同一个线程上，不会并发。
 */
class DeferredSaver<Value>(
    private val delay: Double = 2.0,
    private val write: (Value) -> Unit,
) {
    private class Slot<V>(val value: V)

    private val lock = ReentrantLock()
    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "vela.deferred-saver").apply { isDaemon = true }
    }
    private var pending: Slot<Value>? = null
    private var timerArmed = false

    fun schedule(value: Value) {
        val arm = lock.withLock {
            pending = Slot(value)
            val shouldArm = !timerArmed
            timerArmed = true
            shouldArm
        }
        if (!arm) return
        executor.schedule(::drain, delayNanos(delay), TimeUnit.NANOSECONDS)
    }

    /** 同步写出尚未落盘的最新值；没有待写内容时什么都不做。 */
    fun flush() {
        executor.submit(::drain).get()
    }

    private fun drain() {
        val slot = lock.withLock {
            val current = pending
            pending = null
            timerArmed = false
            current
        }
        if (slot != null) write(slot.value)
    }

    private fun delayNanos(seconds: Double): Long {
        if (seconds <= 0.0) return 0L
        val raw = seconds * 1_000_000_000.0
        if (raw >= Long.MAX_VALUE.toDouble()) return Long.MAX_VALUE
        return raw.toLong()
    }
}
