package vela.core.base.io

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class DeferredSaverTests {
    @Test
    fun testDeferredSaverCoalescesAndFlushWritesLatest() {
        val box = Box()
        val saver = DeferredSaver<Int>(delay = 0.05) { box.add(it) }
        saver.schedule(1)
        saver.schedule(2)
        saver.schedule(3)
        saver.flush()
        assertEquals(listOf(3), box.snapshot())
        saver.flush()
        assertEquals(listOf(3), box.snapshot())
        saver.schedule(4)
        Thread.sleep(300)
        assertEquals(listOf(3, 4), box.snapshot())
    }

    @Test
    fun testDeferredSaverWritesNeverOverlap() {
        val box = Box()
        val saver = DeferredSaver<Int>(delay = 0.001) { box.enter(it) }
        val ready = CountDownLatch(1)
        val done = CountDownLatch(200)
        val pool = Executors.newFixedThreadPool(32) { runnable ->
            Thread(runnable).apply { isDaemon = true }
        }
        try {
            repeat(200) { index ->
                pool.execute {
                    ready.await()
                    saver.schedule(index)
                    done.countDown()
                }
            }
            ready.countDown()
            done.await()
            saver.flush()
            Thread.sleep(100)
            assertFalse(box.overlapped())
        } finally {
            pool.shutdownNow()
        }
    }
}

private class Box {
    private val lock = java.util.concurrent.locks.ReentrantLock()
    private var inside = false
    private val values = mutableListOf<Int>()
    private var overlapped = false

    fun add(value: Int) {
        lock.lock()
        try {
            values.add(value)
        } finally {
            lock.unlock()
        }
    }

    fun enter(value: Int) {
        lock.lock()
        if (inside) overlapped = true
        inside = true
        lock.unlock()
        Thread.sleep(2)
        lock.lock()
        inside = false
        lock.unlock()
    }

    fun snapshot(): List<Int> {
        lock.lock()
        try {
            return values.toList()
        } finally {
            lock.unlock()
        }
    }

    fun overlapped(): Boolean {
        lock.lock()
        try {
            return overlapped
        } finally {
            lock.unlock()
        }
    }
}
