package vela.core.cross

import kotlin.test.Test
import kotlin.test.assertEquals
import vela.core.base.model.FeedSource
import vela.core.ledger.ListOrigin

/** C7 标成 deferred:C9：停留来源跟已加载列表，不跟刚选中的来源。 */
class DeferredLoadCommitTests {
    @Test
    fun testDwellOriginFollowsLoadedListNotSelection() {
        assertEquals(FeedSource.popular, ListOrigin.dwell(loaded = FeedSource.popular, installing = null))
        assertEquals(FeedSource.guess, ListOrigin.dwell(loaded = FeedSource.popular, installing = FeedSource.guess))
    }
}
