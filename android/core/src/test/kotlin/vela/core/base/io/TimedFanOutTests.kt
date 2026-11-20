package vela.core.base.io

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TimedFanOutTests {
    private class Boom : Exception()

    @Test
    fun testTimedFanOutDropsSlowAndFailedKeepsOrder() = runBlocking {
        val started = System.nanoTime()
        val results = TimedFanOut.run(listOf(0, 1, 2, 3), timeout = 0.3) { n ->
            when (n) {
                1 -> {
                    delay(5_000)
                    n
                }
                2 -> throw Boom()
                else -> n * 10
            }
        }
        assertEquals(listOf(0, null, null, 30), results)
        val elapsed = (System.nanoTime() - started) / 1_000_000_000.0
        assertTrue(elapsed < 2.0, "慢请求被超时截断")
    }

    @Test
    fun testTimedFanOutRunsConcurrently() = runBlocking {
        val started = System.nanoTime()
        val results = TimedFanOut.run(listOf(1, 2, 3), timeout = 2.0) { n ->
            delay(300)
            n
        }
        assertEquals(listOf(1, 2, 3), results)
        val elapsed = (System.nanoTime() - started) / 1_000_000_000.0
        assertTrue(elapsed < 0.8, "三个 0.3 秒的请求并发，不串行")
    }

    @Test
    fun testTimedFanOutEmpty() = runBlocking {
        val results = TimedFanOut.run(emptyList<Int>(), timeout = 1.0) { it }
        assertTrue(results.isEmpty())
    }
}
