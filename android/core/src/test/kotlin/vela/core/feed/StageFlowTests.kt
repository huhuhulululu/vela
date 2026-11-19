package vela.core.feed

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import vela.core.base.model.Gender
import vela.core.base.model.Room
import vela.core.base.model.ShowState
import vela.core.base.site.CatalogPage

class StageFlowTests {
    @Test
    fun testAutoMoveKeepsManualPause() {
        assertTrue(MoveRules.pausedAfterMove(manuallyPaused = true, userInitiated = false))
        assertFalse(MoveRules.pausedAfterMove(manuallyPaused = false, userInitiated = false))
    }

    @Test
    fun testUserMoveClearsManualPause() {
        assertFalse(MoveRules.pausedAfterMove(manuallyPaused = true, userInitiated = true))
    }

    @Test
    fun testRestoreBringsBackCursor() {
        val state = PagingState(offset = 80)
        val previous = state.restart()
        assertEquals(0, state.offset)
        state.restore(previous)
        assertEquals(80, state.offset)
    }

    @Test
    fun testIdleStartFiresOnceWhenRoomAppears() {
        val gate = IdleStart()
        assertFalse(gate.takeStart(hasCurrent = true))
        gate.markIdle()
        assertFalse(gate.takeStart(hasCurrent = false))
        assertTrue(gate.takeStart(hasCurrent = true))
        assertFalse(gate.takeStart(hasCurrent = true))
    }

    @Test
    fun testClearDropsIdle() {
        val gate = IdleStart()
        gate.markIdle()
        gate.clear()
        assertFalse(gate.takeStart(hasCurrent = true))
    }

    private fun room(name: String): Room =
        Room(username = name, viewers = 10, gender = Gender.female, tags = emptyList(), show = ShowState.publicShow)

    @Test
    fun testRestMergeAfterBlockingLastRoomResumesIdleStart() {
        val feed = FeedStore()
        feed.replace(CatalogPage(rooms = listOf(room("a")), total = 3, nextOffset = 1))
        feed.setBlocked(setOf("a"))
        assertNull(feed.current)
        val gate = IdleStart()
        gate.markIdle()
        assertFalse(gate.takeStart(hasCurrent = feed.current != null))

        feed.mergePageAfterCurrent(
            CatalogPage(rooms = listOf(room("b"), room("c")), total = 3, nextOffset = 3),
            offset = 1,
        ) { it }
        assertEquals("b", feed.current?.username)
        assertTrue(gate.takeStart(hasCurrent = feed.current != null))
    }

    @Test
    fun testRestMergeDoesNotRestartWhenPlaying() {
        val feed = FeedStore()
        feed.replace(CatalogPage(rooms = listOf(room("a")), total = 3, nextOffset = 1))
        val gate = IdleStart()
        feed.mergePageAfterCurrent(
            CatalogPage(rooms = listOf(room("b")), total = 3, nextOffset = 2),
            offset = 1,
        ) { it }
        assertEquals("a", feed.current?.username)
        assertFalse(gate.takeStart(hasCurrent = feed.current != null))
    }
}
