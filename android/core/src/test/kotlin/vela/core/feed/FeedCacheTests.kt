package vela.core.feed

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import vela.core.base.model.Gender
import vela.core.base.model.Room
import vela.core.base.model.RoomQuery
import vela.core.base.model.ShowState
import vela.core.base.model.TasteKeys
import vela.core.base.model.dedupeRooms
import vela.core.base.site.CatalogPage

/**
 * `FeedStore.rooms` 改为缓存后，必须始终等于「重新全量过滤」的结果。
 * 随机序列用同一个种子，只断言不变量，不要求与 Swift SplitMix64 逐位相同。
 */
class FeedCacheTests {
    private val names = (0 until 40).map { "user$it" }
    private val tags = listOf("Asian", "latina", "Nope", "cosplay", "music", "chat")
    private val shows = listOf(
        ShowState.publicShow,
        ShowState.publicShow,
        ShowState.publicShow,
        ShowState.privateShow,
        ShowState.away,
    )
    private val texts = listOf("", "", "user1", " Asian ", "hello", "zzz")

    private fun randomRoom(rng: Random): Room {
        var name = names.random(rng)
        if (rng.nextBoolean()) name = name.uppercase()
        val tagCount = rng.nextInt(0, 4)
        return Room(
            username = name,
            subject = if (rng.nextBoolean()) "hello $name" else "",
            viewers = rng.nextInt(0, 501),
            gender = Gender.entries.random(rng),
            tags = (0 until tagCount).map { tags.random(rng) },
            show = shows.random(rng),
        )
    }

    private fun randomRooms(rng: Random): List<Room> =
        (0 until rng.nextInt(0, 16)).map { randomRoom(rng) }

    private fun randomNames(rng: Random): Set<String> =
        (0 until rng.nextInt(0, 7)).map { names.random(rng) }.toSet()

    private fun randomHidden(rng: Random): Set<String> {
        val keys = HashSet<String>()
        if (rng.nextBoolean()) keys.add(TasteKeys.tagKey(tags.random(rng)))
        if (rng.nextInt(0, 4) == 0) keys.add(TasteKeys.genderKey(Gender.entries.random(rng)))
        return keys
    }

    private fun randomQuery(rng: Random): RoomQuery =
        RoomQuery(
            text = texts.random(rng),
            publicOnly = rng.nextBoolean(),
            blocked = randomNames(rng),
            only = if (rng.nextBoolean()) null else randomNames(rng),
            hidden = randomHidden(rng),
        )

    private fun randomPage(rng: Random): CatalogPage {
        val rooms = randomRooms(rng)
        return CatalogPage(
            rooms = rooms,
            total = rng.nextInt(0, 61),
            nextOffset = rng.nextInt(0, 71),
        )
    }

    private fun assertCacheMatches(feed: FeedStore, op: String) {
        val expected = feed.catalog.filter { feed.query.matches(it) }
        assertEquals(expected, feed.rooms, "缓存与全量过滤不一致：$op")

        val refIndex = if (expected.isEmpty() || feed.currentUsername == null) {
            0
        } else {
            val found = expected.indexOfFirst { it.username == feed.currentUsername }
            if (found >= 0) found else 0
        }
        assertEquals(refIndex, feed.index, "index：$op")
        val refCurrent = if (expected.isEmpty()) null else expected[minOf(refIndex, expected.size - 1)]
        assertEquals(refCurrent, feed.current, "current：$op")

        if (dedupeRooms(feed.catalog) != feed.catalog) return
        val rebuilt = FeedStore(
            catalog = feed.catalog,
            query = feed.query,
            currentUsername = feed.currentUsername,
            reachedEnd = feed.reachedEnd,
            total = feed.total,
        )
        assertEquals(feed, rebuilt, "重建后应相等：$op")
        assertEquals(feed.rooms, rebuilt.rooms, "重建后可见列表：$op")
    }

    @Test
    fun testCachedRoomsMatchFullFilterAcrossRandomOperations() {
        for (seed in 1L..40L) {
            val rng = Random(seed)
            val feed = FeedStore(catalog = randomRooms(rng), query = randomQuery(rng))
            assertCacheMatches(feed, "seed $seed init")
            for (step in 0 until 150) {
                val op = when (rng.nextInt(0, 11)) {
                    0 -> {
                        feed.replace(randomPage(rng))
                        "replace"
                    }
                    1 -> {
                        feed.append(randomPage(rng))
                        "append"
                    }
                    2 -> {
                        feed.step(rng.nextInt(-3, 4))
                        "step"
                    }
                    3 -> {
                        val name = names.random(rng)
                        feed.focus(if (rng.nextBoolean()) name.uppercase() else name)
                        "focus"
                    }
                    4 -> {
                        feed.insertDirect(randomRoom(rng))
                        "insertDirect"
                    }
                    5 -> {
                        feed.promoteAfterCurrent(randomNames(rng).sorted().shuffled(rng))
                        "promoteAfterCurrent"
                    }
                    6 -> {
                        val local = Random(rng.nextLong())
                        feed.reorderAfterCurrent { it.shuffled(local) }
                        "reorderAfterCurrent"
                    }
                    7 -> {
                        feed.setQuery(randomQuery(rng))
                        "setQuery"
                    }
                    8 -> {
                        feed.setBlocked(randomNames(rng))
                        "setBlocked"
                    }
                    9 -> {
                        val blocked = randomNames(rng)
                        val only = if (rng.nextBoolean()) null else randomNames(rng)
                        val hidden = if (rng.nextBoolean()) null else randomHidden(rng)
                        feed.setFilters(blocked = blocked, only = only, hidden = hidden)
                        "setFilters"
                    }
                    else -> {
                        feed.reveal(randomRoom(rng))
                        "reveal"
                    }
                }
                assertCacheMatches(feed, "seed $seed step $step $op")
            }
        }
    }

    @Test
    fun testEachMutatingMethodKeepsCacheInSync() {
        fun r(name: String, tags: List<String> = emptyList(), show: ShowState = ShowState.publicShow): Room =
            Room(username = name, subject = "s-$name", gender = Gender.female, tags = tags, show = show)

        val feed = FeedStore(catalog = listOf(r("a"), r("b", show = ShowState.away), r("c"), r("d", listOf("nope"))))
        assertCacheMatches(feed, "init")
        assertEquals(listOf("a", "c", "d"), feed.rooms.map { it.username })

        feed.replace(CatalogPage(rooms = listOf(r("a"), r("b"), r("c"), r("A")), total = 10, nextOffset = 3))
        assertCacheMatches(feed, "replace")
        feed.append(
            CatalogPage(
                rooms = listOf(r("e"), r("f", show = ShowState.privateShow), r("g", listOf("nope"))),
                total = 10,
                nextOffset = 6,
            ),
        )
        assertCacheMatches(feed, "append")
        feed.step(2)
        assertCacheMatches(feed, "step")
        feed.focus("E")
        assertCacheMatches(feed, "focus")
        feed.insertDirect(r("z"))
        assertCacheMatches(feed, "insertDirect")
        feed.promoteAfterCurrent(listOf("g", "a"))
        assertCacheMatches(feed, "promoteAfterCurrent")
        feed.reorderAfterCurrent { it.reversed() }
        assertCacheMatches(feed, "reorderAfterCurrent")
        feed.setQuery(RoomQuery(text = "s-", publicOnly = false))
        assertCacheMatches(feed, "setQuery")
        assertTrue(feed.rooms.any { it.username == "f" })
        feed.setBlocked(setOf("z", "e"))
        assertCacheMatches(feed, "setBlocked")
        assertFalse(feed.rooms.any { it.username == "z" })
        feed.setFilters(blocked = emptySet(), only = setOf("a", "g", "c"), hidden = setOf(TasteKeys.tagKey("nope")))
        assertCacheMatches(feed, "setFilters")
        assertEquals(setOf("a", "c"), feed.rooms.map { it.username }.toSet())
        feed.setFilters(blocked = emptySet(), only = null)
        assertCacheMatches(feed, "setFilters hidden nil")
        feed.setQuery(RoomQuery(text = "nobody-matches"))
        assertCacheMatches(feed, "setQuery empty")
        assertTrue(feed.rooms.isEmpty())
        assertTrue(feed.reveal(r("q")))
        assertCacheMatches(feed, "reveal")
    }

    @Test
    fun testEqualityIgnoresCache() {
        val rooms = listOf(Room(username = "a", show = ShowState.publicShow), Room(username = "b", show = ShowState.away))
        val lhs = FeedStore(catalog = rooms)
        val rhs = FeedStore(catalog = rooms, query = RoomQuery(publicOnly = false))
        assertNotEquals(lhs, rhs)
        rhs.setQuery(RoomQuery())
        lhs.setQuery(RoomQuery(publicOnly = false))
        lhs.setQuery(RoomQuery())
        assertEquals(lhs, rhs)
        assertEquals(lhs.rooms, rhs.rooms)
    }

    /**
     * 2000 个房间连续读 1000 次 index。JVM 先预热两边，避免第一次测量吃掉 JIT，
     * 再断言缓存明显快于每次全量过滤。阈值与 Swift 一样是 2 倍。
     */
    @Test
    fun testCachedIndexIsMuchFasterThanFullFilter() {
        val rooms = (0 until 2000).map { i ->
            Room(
                username = "room$i",
                subject = "subject $i",
                gender = Gender.female,
                tags = listOf("tag${i % 7}", "Music"),
                show = if (i % 5 == 0) ShowState.away else ShowState.publicShow,
            )
        }
        val feed = FeedStore(
            catalog = rooms,
            query = RoomQuery(blocked = setOf("room3", "room9"), hidden = setOf(TasteKeys.tagKey("tag6"))),
        )
        assertTrue(feed.focus("room1001"))
        val iterations = 1000

        fun fullIndex(): Int {
            val list = feed.catalog.filter { feed.query.matches(it) }
            val cur = feed.currentUsername ?: return 0
            val found = list.indexOfFirst { it.username == cur }
            return if (found >= 0) found else 0
        }

        repeat(50) {
            feed.index
            fullIndex()
        }

        fun measure(body: () -> Int): Pair<Double, Int> {
            val start = System.nanoTime()
            var sum = 0
            repeat(iterations) { sum += body() }
            return (System.nanoTime() - start) / 1_000_000_000.0 to sum
        }

        val (cached, cachedSum) = measure { feed.index }
        val (full, fullSum) = measure { fullIndex() }
        assertEquals(fullSum, cachedSum)
        assertTrue(cached * 2 < full, "缓存 ${cached}s 应明显快于全量过滤 ${full}s")
    }
}
