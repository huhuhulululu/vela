package vela.core.cross

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import vela.core.base.model.RoomQuery
import vela.core.feed.FeedStore
import vela.core.feed.PagingState

/** C2 标成 deferred:C9 的翻页回滚与空页上限。 */
class DeferredPlaybackRefreshTests {
    @Test
    fun testOverlappingReloadsRollBackToLoadedCursor() {
        val paging = PagingState()
        paging.commit(80)
        paging.restart()
        val second = paging.restart()
        assertEquals(0, paging.offset)
        paging.restore(second)
        assertEquals(80, paging.offset)
        assertEquals(80, paging.committed)
    }

    @Test
    fun testCommitMovesSnapshotOnlyOnSuccess() {
        val paging = PagingState(offset = 40)
        val previous = paging.restart()
        paging.commit(120)
        assertEquals(120, paging.restart().offset)
        assertEquals(40, previous.offset)
    }

    @Test
    fun testFillStopsAtBlankPageCap() {
        val feed = FeedStore(query = RoomQuery(publicOnly = false, blocked = emptySet()))
        assertTrue(feed.shouldLoadMore(blankPages = FeedStore.maxBlankPages - 1))
        assertFalse(feed.shouldLoadMore(blankPages = FeedStore.maxBlankPages))
    }
}
