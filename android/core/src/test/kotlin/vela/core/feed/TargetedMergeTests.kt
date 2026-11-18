package vela.core.feed

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import vela.core.base.io.TimedFanOut
import vela.core.base.model.Gender
import vela.core.base.model.Room
import vela.core.base.model.ShowState
import vela.core.base.site.CatalogPage

class TargetedMergeTests {
    private fun room(name: String, secs: Int? = null): Room =
        Room(
            username = name,
            viewers = 10,
            gender = Gender.female,
            tags = listOf("x"),
            show = ShowState.publicShow,
            secondsOnline = secs,
        )

    @Test
    fun testFullyDuplicatePageDoesNotEndWhileCursorAdvances() {
        val feed = FeedStore()
        feed.replace(CatalogPage(rooms = listOf(room("a"), room("b"), room("t1"), room("t2")), total = 10, nextOffset = 2))
        feed.append(CatalogPage(rooms = listOf(room("t1"), room("t2")), total = 10, nextOffset = 4), offset = 2)
        assertFalse(feed.reachedEnd)
        assertEquals(listOf("a", "b", "t1", "t2"), feed.catalog.map { it.username })
        assertTrue(feed.shouldLoadMore(threshold = 8))
    }

    @Test
    fun testEndsOnlyByCursorTotalEmptyOrStall() {
        val atTotal = FeedStore()
        atTotal.replace(CatalogPage(rooms = listOf(room("a")), total = 4, nextOffset = 1))
        atTotal.append(CatalogPage(rooms = listOf(room("b")), total = 4, nextOffset = 4), offset = 1)
        assertTrue(atTotal.reachedEnd)

        val empty = FeedStore()
        empty.replace(CatalogPage(rooms = listOf(room("a")), total = 9, nextOffset = 1))
        empty.append(CatalogPage(rooms = emptyList(), total = 9, nextOffset = 1), offset = 1)
        assertTrue(empty.reachedEnd)

        val stalled = FeedStore()
        stalled.replace(CatalogPage(rooms = listOf(room("a")), total = 9, nextOffset = 1))
        stalled.append(CatalogPage(rooms = listOf(room("a")), total = 9, nextOffset = 1), offset = 1)
        assertTrue(stalled.reachedEnd, "游标没有前进：兜底停止，免得原地重复请求")
    }

    @Test
    fun testMergeAfterCurrentKeepsHeadAndDedupes() {
        val feed = FeedStore()
        feed.replace(CatalogPage(rooms = listOf(room("a"), room("b"), room("c"), room("d")), total = 9, nextOffset = 4))
        feed.step(1)
        val added = feed.mergeAfterCurrent(listOf(room("D"), room("x"), room("y"))) { rooms ->
            rooms.sortedByDescending { it.username }
        }
        assertEquals(2, added, "D 与已有的 d 重名（不分大小写）不重复加")
        assertEquals(listOf("a", "b", "y", "x", "d", "c"), feed.catalog.map { it.username })
        assertEquals("b", feed.currentUsername)
        assertEquals(9, feed.total)
        assertFalse(feed.reachedEnd)
    }

    @Test
    fun testMergeNothingNewIsNoOp() {
        val feed = FeedStore()
        feed.replace(CatalogPage(rooms = listOf(room("a"), room("b")), total = 2, nextOffset = 2))
        val before = feed.copy()
        assertEquals(0, feed.mergeAfterCurrent(listOf(room("A"))) { it.reversed() })
        assertEquals(before, feed)
    }

    @Test
    fun testMergeIntoEmptyFeedSelectsFirst() {
        val feed = FeedStore()
        feed.mergeAfterCurrent(listOf(room("a"), room("b"))) { it }
        assertEquals("a", feed.currentUsername)
    }

    private class Boom : Exception()

    @Test
    fun testFanOutDropsSlowAndFailedKeepsOrder() = runBlocking {
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
    fun testFanOutRunsConcurrently() = runBlocking {
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
    fun testFanOutEmpty() = runBlocking {
        val results = TimedFanOut.run(emptyList<Int>(), timeout = 1.0) { it }
        assertTrue(results.isEmpty())
    }
}
