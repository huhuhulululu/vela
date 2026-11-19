package vela.core.feed

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import vela.core.base.model.Gender
import vela.core.base.model.Room
import vela.core.base.model.ShowState
import vela.core.base.site.CatalogPage

/** 冷启动：首页先入选并保持当前项，后页再并入；目录请求与 bootstrap 各只做一次。 */
class StartupCatalogTests {
    private fun room(name: String): Room =
        Room(username = name, viewers = 10, gender = Gender.female, tags = listOf("x"), show = ShowState.publicShow)

    private fun names(rooms: List<Room>): List<String> = rooms.map { it.username }

    @Test
    fun testContinuesOnlyWhilePageIsFullAndCatalogRemains() {
        assertTrue(
            CatalogWindow.shouldContinue(
                fetched = 1, pages = 3, lastCount = 90, pageSize = 90, nextOffset = 90, total = 1000,
            ),
        )
        assertFalse(
            CatalogWindow.shouldContinue(
                fetched = 3, pages = 3, lastCount = 90, pageSize = 90, nextOffset = 270, total = 1000,
            ),
            "三页取满就停",
        )
        assertFalse(
            CatalogWindow.shouldContinue(
                fetched = 1, pages = 3, lastCount = 30, pageSize = 90, nextOffset = 30, total = 1000,
            ),
            "短页就停",
        )
        assertFalse(
            CatalogWindow.shouldContinue(
                fetched = 1, pages = 3, lastCount = 90, pageSize = 90, nextOffset = 90, total = 90,
            ),
            "到底就停",
        )
    }

    @Test
    fun testPageSizeIsClampedToTheCatalogCap() {
        assertTrue(
            CatalogWindow.shouldContinue(
                fetched = 1, pages = 3, lastCount = 90, pageSize = 500, nextOffset = 90, total = 1000,
            ),
            "请求条数超过接口上限时，按 90 判断满页",
        )
    }

    @Test
    fun testLaterPageKeepsTheRoomChosenFromTheFirstPage() {
        val first = listOf(room("a"), room("b"), room("c"))
        val folded = PageMerge.keepingCurrent(
            existing = first,
            incoming = listOf(room("c"), room("z"), room("Z")),
            current = "a",
        ) { rooms ->
            rooms.sortedBy { it.username }
        }
        assertEquals(1, folded.added, "c / Z 与已有房间重复，只新增 z")
        assertEquals(listOf("a", "b", "c", "z"), names(folded.rooms))
        assertEquals("a", folded.rooms.first().username, "首个房间只从首页候选里选，后页不能把它换掉")
    }

    @Test
    fun testLaterPageKeepsASelectionThatIsNotFirst() {
        val folded = PageMerge.keepingCurrent(
            existing = listOf(room("a"), room("b"), room("c")),
            incoming = listOf(room("d")),
            current = "b",
        ) { rooms ->
            rooms.sortedByDescending { it.username }
        }
        assertEquals(listOf("a", "b", "d", "c"), names(folded.rooms))
        assertEquals(1, folded.added)
    }

    @Test
    fun testNothingNewLeavesTheListUntouched() {
        var ordered = false
        val existing = listOf(room("a"), room("b"))
        val folded = PageMerge.keepingCurrent(existing = existing, incoming = listOf(room("A")), current = "a") { rooms ->
            ordered = true
            rooms.reversed()
        }
        assertFalse(ordered, "没有新增就不重排，避免把正在看的列表抖一下")
        assertEquals(0, folded.added)
        assertEquals(listOf("a", "b"), names(folded.rooms))
    }

    @Test
    fun testNoCurrentOrdersTheWholePool() {
        val folded = PageMerge.keepingCurrent(
            existing = listOf(room("b")),
            incoming = listOf(room("a")),
            current = null,
        ) { rooms ->
            rooms.sortedBy { it.username }
        }
        assertEquals(listOf("a", "b"), names(folded.rooms))
        assertEquals(1, folded.added)
    }

    @Test
    fun testAppendedPageDedupesAndKeepsTheLaterCursor() {
        val base = CatalogPage(rooms = listOf(room("a"), room("b")), total = 10, nextOffset = 2)
        val next = CatalogPage(rooms = listOf(room("B"), room("c")), total = 12, nextOffset = 4)
        val merged = PageMerge.appended(base, next)
        assertEquals(listOf("a", "b", "c"), names(merged.rooms))
        assertEquals(12, merged.total)
        assertEquals(4, merged.nextOffset)
    }

    @Test
    fun testFeedKeepsPlayingRoomWhenRestPagesArrive() {
        val feed = FeedStore()
        feed.replace(CatalogPage(rooms = listOf(room("a"), room("m")), total = 10, nextOffset = 2))
        val playing = feed.currentUsername
        assertEquals("a", playing)
        feed.mergePageAfterCurrent(
            CatalogPage(rooms = listOf(room("m"), room("z")), total = 10, nextOffset = 4),
            offset = 2,
        ) { rooms ->
            rooms.sortedBy { it.username }
        }
        feed.mergePageAfterCurrent(
            CatalogPage(rooms = listOf(room("y")), total = 10, nextOffset = 6),
            offset = 4,
        ) { rooms ->
            rooms.sortedBy { it.username }
        }
        assertEquals(playing, feed.currentUsername, "后页并入不改当前选中项")
        assertEquals("a", feed.catalog.first().username)
        assertEquals(listOf("a", "m", "y", "z"), names(feed.catalog))
        assertEquals(10, feed.total)
        assertFalse(feed.reachedEnd)
    }

    @Test
    fun testRestPageAtTheEndDoesNotMoveTheCurrentRoom() {
        val feed = FeedStore()
        feed.replace(CatalogPage(rooms = listOf(room("a"), room("b")), total = 4, nextOffset = 2))
        feed.mergePageAfterCurrent(
            CatalogPage(rooms = listOf(room("c")), total = 4, nextOffset = 4),
            offset = 2,
        ) { it }
        assertTrue(feed.reachedEnd)
        assertEquals("a", feed.currentUsername)
        assertEquals(listOf("a", "b", "c"), names(feed.catalog))
    }

    @Test
    fun testEmptyRestPageMarksTheEndWithoutReordering() {
        val feed = FeedStore()
        feed.replace(CatalogPage(rooms = listOf(room("a")), total = 9, nextOffset = 1))
        var ordered = false
        feed.mergePageAfterCurrent(CatalogPage(rooms = emptyList(), total = 9, nextOffset = 1), offset = 1) { rooms ->
            ordered = true
            rooms.reversed()
        }
        assertFalse(ordered)
        assertTrue(feed.reachedEnd)
        assertEquals("a", feed.currentUsername)
        assertEquals(listOf("a"), names(feed.catalog))
    }

    @Test
    fun testBootstrapEntersOnce() {
        val gate = StartupGate()
        assertTrue(gate.enterBootstrap())
        assertFalse(gate.enterBootstrap(), "onAppear 再次触发不再进入")
        assertTrue(gate.bootstrapped)
    }

    @Test
    fun testCatalogClaimBeforeBootstrapDoesNotRepeat() {
        val gate = StartupGate()
        assertTrue(gate.claimCatalog(ageConfirmed = true), "初始化时已确认年龄，先发目录")
        assertTrue(gate.enterBootstrap(), "视图出现仍做一次启动副作用")
        assertFalse(gate.claimCatalog(ageConfirmed = true), "bootstrap 不再发第二次目录请求")
        assertFalse(gate.enterBootstrap())
    }

    @Test
    fun testUnconfirmedAgeDoesNotClaim() {
        val gate = StartupGate()
        assertFalse(gate.claimCatalog(ageConfirmed = false))
        assertFalse(gate.catalogRequested, "未确认时不领取，确认前保持不加载")
        assertTrue(gate.enterBootstrap())
        assertFalse(gate.claimCatalog(ageConfirmed = false))
        assertFalse(gate.catalogRequested)
        assertTrue(gate.claimCatalog(ageConfirmed = true), "确认之后可以领唯一一次")
        assertFalse(gate.claimCatalog(ageConfirmed = true))
    }

    @Test
    fun testConfirmAgeAndBootstrapShareOneClaim() {
        val gate = StartupGate()
        assertTrue(gate.claimCatalog(ageConfirmed = true), "先到的一方领取")
        assertFalse(gate.claimCatalog(ageConfirmed = true), "后到的一方（confirmAge 或 bootstrap）不重复")
        assertTrue(gate.catalogRequested)
    }
}
