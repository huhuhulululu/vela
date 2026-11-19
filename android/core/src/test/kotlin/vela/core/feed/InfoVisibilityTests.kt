package vela.core.feed

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InfoVisibilityTests {
    @Test
    fun testCurrentPageFollowsChrome() {
        assertTrue(InfoVisibility.visible(isCurrent = true, alwaysShow = false, chromeOn = true))
        assertFalse(InfoVisibility.visible(isCurrent = true, alwaysShow = false, chromeOn = false))
    }

    @Test
    fun testAlwaysShowKeepsCurrentPageInfo() {
        assertTrue(InfoVisibility.visible(isCurrent = true, alwaysShow = true, chromeOn = false))
    }

    @Test
    fun testNeighbourPagesKeepInfo() {
        assertTrue(InfoVisibility.visible(isCurrent = false, alwaysShow = false, chromeOn = false))
    }
}
