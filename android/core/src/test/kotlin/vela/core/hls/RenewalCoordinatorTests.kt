package vela.core.hls

import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.yield
import vela.core.base.play.StreamRendition
import java.net.URI

class RenewalCoordinatorTests {
    private fun rendition(n: Int): StreamRendition =
        StreamRendition(video = URI.create("https://example.com/$n.m3u8"))

    /**
     * 视频与音轨共用一次续期，加入方醒来时新地址一定已写入。
     * Swift 用睡眠把加入方塞进 fetch 窗口；这里用通道握手，避免慢机器错过窗口。断言不变。
     */
    @Test
    fun testJoinerSeesInstalledRenditionWhenItReturns() = runBlocking {
        repeat(10) {
            val coordinator = RenewalCoordinator()
            val box = Box()
            val fresh = rendition(2)
            val started = Channel<Unit>()
            val release = Channel<Unit>()
            val fetches = AtomicInteger()
            val fetch: suspend () -> StreamRendition? = {
                fetches.incrementAndGet()
                started.send(Unit)
                release.receive()
                fresh
            }
            val apply: (StreamRendition) -> Boolean = { box.set(it); true }
            val starter = async { coordinator.renew(fetch = fetch, apply = apply) }
            started.receive()
            val joiner = async {
                val ok = coordinator.renew(fetch = fetch, apply = apply)
                ok to box.value
            }
            repeat(5) { yield() }
            assertEquals(1, fetches.get(), "加入方不应另起一次 fetch")
            release.send(Unit)
            assertTrue(starter.await())
            val (joinedOk, seen) = joiner.await()
            assertTrue(joinedOk)
            assertEquals(fresh, seen)
            assertEquals(1, box.applies)
            coordinator.cancel()
        }
    }

    /** apply 失败时发起方也返回 false，而不是「解析结果非空」。 */
    @Test
    fun testFailedApplyIsReportedAsFailure() = runBlocking {
        val coordinator = RenewalCoordinator()
        val ok = coordinator.renew(
            fetch = { StreamRendition(video = URI.create("https://example.com/a.m3u8")) },
            apply = { false },
        )
        assertFalse(ok)
        val none = RenewalCoordinator().renew(fetch = { null }, apply = { true })
        assertFalse(none)
    }

    /** 续期完成但续期后的重取还没结束时仍算「续期中」。 */
    @Test
    fun testRecoveryCoversRefetchAfterRenewal() = runBlocking {
        val coordinator = RenewalCoordinator()
        assertFalse(coordinator.isActive)
        val gate = AsyncGate()
        val entered = CompletableDeferred<Unit>()
        val refetching = launch {
            coordinator.recovering {
                coordinator.renew(
                    fetch = { StreamRendition(video = URI.create("https://example.com/b.m3u8")) },
                    apply = { true },
                )
                entered.complete(Unit)
                gate.wait()
            }
        }
        entered.await()
        assertTrue(coordinator.isActive, "续期已完成、重取未完成")
        gate.open()
        refetching.join()
        assertFalse(coordinator.isActive)
    }

    @Test
    fun testRecoveryEndsWhenBodyThrows() = runBlocking {
        val coordinator = RenewalCoordinator()
        class Boom : Exception()
        try {
            coordinator.recovering { throw Boom() }
        } catch (_: Boom) {
        }
        assertFalse(coordinator.isActive)
    }

    /** 取消后在途续期的结果不再写入，也不再占着「续期中」。 */
    @Test
    fun testCancelDropsInFlightRenewal() = runBlocking {
        val coordinator = RenewalCoordinator()
        val box = Box()
        val gate = AsyncGate()
        val result = async {
            coordinator.renew(
                fetch = {
                    gate.wait()
                    StreamRendition(video = URI.create("https://example.com/c.m3u8"))
                },
                apply = { box.set(it); true },
            )
        }
        delay(50)
        assertTrue(coordinator.isActive)
        coordinator.cancel()
        assertFalse(coordinator.isActive)
        gate.open()
        val ok = result.await()
        assertFalse(ok)
        assertEquals(0, box.applies)
    }

    /** 取消后新的续期注册被拒绝，直到 resume。 */
    @Test
    fun testCancelRejectsNewRegistrationUntilResume() = runBlocking {
        val coordinator = RenewalCoordinator()
        coordinator.cancel()
        val fetched = Box()
        val rejected = coordinator.renew(
            fetch = {
                fetched.set(StreamRendition(video = URI.create("https://example.com/x.m3u8")))
                fetched.value
            },
            apply = { true },
        )
        assertFalse(rejected)
        assertEquals(0, fetched.applies, "被拒绝时不发起 fetch")
        coordinator.resume()
        val ok = coordinator.renew(
            now = Instant.now().plusSeconds(10.0),
            fetch = { StreamRendition(video = URI.create("https://example.com/y.m3u8")) },
            apply = { true },
        )
        assertTrue(ok)
    }

    /** 提交方记下注册时的代次，取消后核对失败。resume 不让旧代次复活。 */
    @Test
    fun testEpochChangesOnCancel() {
        val coordinator = RenewalCoordinator()
        val registered = coordinator.epoch
        assertTrue(coordinator.isCurrent(registered))
        coordinator.cancel()
        assertFalse(coordinator.isCurrent(registered))
        coordinator.resume()
        assertFalse(coordinator.isCurrent(registered), "resume 不让旧代次复活")
        assertTrue(coordinator.isCurrent(coordinator.epoch))
    }

    /** 取消的续期退回额度，恢复后立刻还能续期。 */
    @Test
    fun testCancelledRenewalRefundsBudget() = runBlocking {
        val coordinator = RenewalCoordinator(budget = RenewalBudget(limit = 1, window = 60.0, minGap = 3.0))
        val gate = AsyncGate()
        val now = Instant.now()
        val first = async {
            coordinator.renew(now = now, fetch = { gate.wait(); null }, apply = { true })
        }
        delay(50)
        coordinator.cancel()
        gate.open()
        val cancelled = first.await()
        assertFalse(cancelled)
        coordinator.resume()
        val again = coordinator.renew(
            now = now.plusSeconds(1.0),
            fetch = { StreamRendition(video = URI.create("https://example.com/z.m3u8")) },
            apply = { true },
        )
        assertTrue(again, "额度已退回")
    }

    /** 真正完成（哪怕拿不到地址）的续期照常计数。 */
    @Test
    fun testCompletedFailureStillConsumesBudget() = runBlocking {
        val coordinator = RenewalCoordinator(budget = RenewalBudget(limit = 1, window = 60.0, minGap = 3.0))
        val now = Instant.now()
        coordinator.renew(now = now, fetch = { null }, apply = { true })
        val second = coordinator.renew(
            now = now.plusSeconds(5.0),
            fetch = { StreamRendition(video = URI.create("https://example.com/z.m3u8")) },
            apply = { true },
        )
        assertFalse(second)
    }

    @Test
    fun testRenewalFreshnessRejectsStaleResults() {
        fun current(gen: Int, room: String?, cancelled: Boolean = false): Boolean =
            RenewalFreshness.isCurrent(
                startedGeneration = 3,
                currentGeneration = gen,
                startedRoom = "a",
                currentRoom = room,
                cancelled = cancelled,
            )
        assertTrue(current(3, "a"))
        assertFalse(current(4, "a"), "换档 / 重新解析 / rejoin 后代次已变")
        assertFalse(current(3, "b"))
        assertFalse(current(3, null))
        assertFalse(current(3, "a", cancelled = true))
    }

    /** 续期失败是 false，不是「还没好」；不能把 false 当成超时继续等。 */
    @Test
    fun testRenewBlockingReportsFailedApplyImmediately() {
        val coordinator = RenewalCoordinator()
        val startedAt = System.nanoTime()
        val ok = coordinator.renewBlocking(
            timeoutMs = 2_000,
            fetch = { rendition(1) },
            apply = { false },
        )
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L
        assertFalse(ok)
        assertTrue(elapsedMs < 500, "elapsed=$elapsedMs")
        val emptyStarted = System.nanoTime()
        val empty = RenewalCoordinator().renewBlocking(timeoutMs = 2_000, fetch = { null }, apply = { true })
        val emptyMs = (System.nanoTime() - emptyStarted) / 1_000_000L
        assertFalse(empty)
        assertTrue(emptyMs < 500, "elapsed=$emptyMs")
    }

    @Test
    fun testRenewBlockingReturnsAppliedResult() {
        val coordinator = RenewalCoordinator()
        val box = Box()
        val fresh = rendition(7)
        val ok = coordinator.renewBlocking(
            timeoutMs = 2_000,
            fetch = {
                delay(30)
                fresh
            },
            apply = { box.set(it); true },
        )
        assertTrue(ok)
        assertEquals(fresh, box.value)
        assertEquals(1, box.applies)
    }

    /** 超时只停止等待。共享续期还在，直到调用方 cancel。 */
    @Test
    fun testRenewBlockingTimesOutWithoutWaitingOutTheFetch() {
        val coordinator = RenewalCoordinator()
        val fetches = AtomicInteger()
        try {
            val startedAt = System.nanoTime()
            val ok = coordinator.renewBlocking(
                timeoutMs = 150,
                fetch = {
                    fetches.incrementAndGet()
                    delay(5_000)
                    rendition(1)
                },
                apply = { true },
            )
            val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L
            assertFalse(ok)
            assertEquals(1, fetches.get())
            assertTrue(coordinator.isActive, "超时只是不再等，续期任务还在")
            assertTrue(elapsedMs in 100..400, "elapsed=$elapsedMs")
        } finally {
            coordinator.cancel()
        }
    }

    @Test
    fun testRenewBlockingInterruptReturnsWithin200ms() {
        val coordinator = RenewalCoordinator()
        val fetchEntered = CountDownLatch(1)
        val result = AtomicReference<Boolean?>(null)
        val failure = AtomicReference<Throwable?>(null)
        val thread = Thread {
            try {
                result.set(
                    coordinator.renewBlocking(
                        timeoutMs = 8_000,
                        fetch = {
                            fetchEntered.countDown()
                            delay(5_000)
                            rendition(1)
                        },
                        apply = { true },
                    ),
                )
            } catch (error: Throwable) {
                failure.set(error)
            }
        }
        thread.isDaemon = true
        try {
            thread.start()
            assertTrue(fetchEntered.await(2, TimeUnit.SECONDS), "fetch 没有开始")
            val startedAt = System.nanoTime()
            thread.interrupt()
            thread.join(1_000)
            assertFalse(thread.isAlive, "中断后线程没有返回")
            val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L
            assertEquals(null, failure.get()?.toString())
            assertEquals(false, result.get())
            assertTrue(elapsedMs < 200, "elapsed=$elapsedMs")
        } finally {
            coordinator.cancel()
            thread.interrupt()
            thread.join(1_000)
        }
    }

    @Test
    fun testRenewBlockingCancelReturnsWithin200ms() {
        val coordinator = RenewalCoordinator()
        val box = Box()
        val fetchEntered = CountDownLatch(1)
        val result = AtomicReference<Boolean?>(null)
        val thread = Thread {
            result.set(
                coordinator.renewBlocking(
                    timeoutMs = 8_000,
                    fetch = {
                        fetchEntered.countDown()
                        delay(5_000)
                        rendition(3)
                    },
                    apply = { box.set(it); true },
                ),
            )
        }
        thread.isDaemon = true
        try {
            thread.start()
            assertTrue(fetchEntered.await(2, TimeUnit.SECONDS), "fetch 没有开始")
            assertTrue(coordinator.isActive)
            val startedAt = System.nanoTime()
            coordinator.cancel()
            thread.join(1_000)
            assertFalse(thread.isAlive, "cancel 后线程没有返回")
            val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L
            assertEquals(false, result.get())
            assertTrue(elapsedMs < 200, "elapsed=$elapsedMs")
            assertFalse(coordinator.isActive)
            Thread.sleep(50)
            assertEquals(0, box.applies)
        } finally {
            coordinator.cancel()
            thread.join(1_000)
        }
    }
}

private class Box {
    private val lock = Any()
    private var stored: StreamRendition? = null
    private var count = 0
    val value: StreamRendition?
        get() = synchronized(lock) { stored }
    val applies: Int
        get() = synchronized(lock) { count }

    fun set(rendition: StreamRendition) {
        synchronized(lock) {
            stored = rendition
            count += 1
        }
    }
}

/** 不在取消时自动打开，和 Swift 的 CheckedContinuation 门一样由测试显式 open。取消会结束等待。 */
private class AsyncGate {
    private val lock = Any()
    private var opened = false
    private val waiters = mutableListOf<kotlinx.coroutines.CancellableContinuation<Unit>>()

    suspend fun wait() {
        suspendCancellableCoroutine { cont ->
            synchronized(lock) {
                if (opened) {
                    cont.resume(Unit)
                    return@suspendCancellableCoroutine
                }
                waiters.add(cont)
            }
            cont.invokeOnCancellation {
                synchronized(lock) { waiters.remove(cont) }
            }
        }
    }

    fun open() {
        val pending = synchronized(lock) {
            opened = true
            waiters.toList().also { waiters.clear() }
        }
        pending.forEach { cont ->
            cont.resume(Unit) { _, _, _ -> }
        }
    }
}
