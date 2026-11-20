package vela.core.curation

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.coroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class SupersedingRunnerTests {
    private class Probe {
        val running = AtomicInteger(0)
        val peak = AtomicInteger(0)
        val currentAtEnd = ConcurrentHashMap<Int, Boolean>()
    }

    @Test
    fun testOverlappingRunsNeverRunConcurrentlyAndAllCallersGetLatestResult() = runBlocking {
        withTimeout(5_000) {
            val runner = SupersedingRunner<Int>()
            val probe = Probe()
            val work: suspend (Int) -> Int = { id ->
                val nowRunning = probe.running.incrementAndGet()
                probe.peak.updateAndGet { maxOf(it, nowRunning) }
                if (id == 1) {
                    while (coroutineContext.isActive) {
                        try {
                            delay(2)
                        } catch (_: CancellationException) {
                            break
                        }
                    }
                }
                probe.currentAtEnd[id] = runner.isCurrent(id)
                probe.running.decrementAndGet()
                id * 10
            }
            val first = async { runner.run(work) }
            while (probe.running.get() == 0) delay(1)
            assertTrue(runner.isRunning)
            val second = async { runner.run(work) }
            assertEquals(20, first.await())
            assertEquals(20, second.await())
            assertEquals(1, probe.peak.get())
            assertEquals(false, probe.currentAtEnd[1])
            assertEquals(true, probe.currentAtEnd[2])
            assertFalse(runner.isRunning)
        }
    }

    @Test
    fun testIsRunningStaysTrueUntilLatestFinishes() = runBlocking {
        withTimeout(5_000) {
            val runner = SupersedingRunner<Int>()
            val first = async {
                runner.run {
                    delay(30)
                    1
                }
            }
            while (!runner.isRunning) delay(1)
            assertTrue(runner.isRunning)
            assertEquals(1, first.await())
            assertFalse(runner.isRunning)
        }
    }
}
