package vela.core.cross

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import vela.core.base.model.Room
import vela.core.base.model.RoomQuery
import vela.core.base.model.ShowState
import vela.core.feed.FeedStore
import vela.core.feed.SearchEpoch
import vela.core.feed.SiteSearch
import vela.core.lifecycle.ChallengeResume

class SearchFix13Tests {
    private fun room(name: String): Room = Room(username = name, show = ShowState.publicShow)

    @Test
    fun testRetainedRoomDoesNotCountAsLocalMatch() {
        val feed = FeedStore(catalog = listOf(room("alpha"), room("beta")), currentUsername = "alpha")
        feed.previewQuery(RoomQuery(text = "zzz-nobody"))
        assertEquals(listOf("alpha"), feed.rooms.map { it.username }, "当前房间因 retained 仍在可见列表")
        assertEquals(0, feed.localMatches, "本地其实没有匹配")
        assertEquals(
            SiteSearch.Submit.Remote("zzz-nobody"),
            SiteSearch.onSubmit(text = "zzz-nobody", localMatches = feed.localMatches, active = null),
            "播放中回车搜不存在的名字要走全站搜索",
        )
    }

    @Test
    fun testLocalMatchesCountsRealMatches() {
        val feed = FeedStore(catalog = listOf(room("alpha"), room("alpine"), room("beta")), currentUsername = "beta")
        feed.previewQuery(RoomQuery(text = "alp"))
        assertEquals(2, feed.localMatches)
        assertEquals(3, feed.rooms.size, "beta 是 retained")
    }

    @Test
    fun testPreviewPathExitsOnClearAndKeepsWhileTyping() {
        assertEquals(SiteSearch.Edit.Exit, SiteSearch.onEdit(text = "", active = "jazz"))
        assertEquals(SiteSearch.Edit.Exit, SiteSearch.onEdit(text = " \n", active = "jazz"))
        assertEquals(SiteSearch.Edit.Keep, SiteSearch.onEdit(text = "jazzy", active = "jazz"))
        assertEquals(SiteSearch.Edit.Filter, SiteSearch.onEdit(text = "", active = null))
    }

    @Test
    fun testSearchEpochInvalidatesOlderSearches() {
        val epoch = SearchEpoch()
        val first = epoch.begin()
        assertTrue(epoch.isCurrent(first))
        epoch.invalidate()
        assertFalse(epoch.isCurrent(first), "本地提交 / 收藏选择 / 退出搜索后旧搜索作废")
        val second = epoch.begin()
        val third = epoch.begin()
        assertFalse(epoch.isCurrent(second), "新搜索取代旧搜索")
        assertTrue(epoch.isCurrent(third))
    }

    @Test
    fun testChallengeResumeMergingKeepsSiteSearch() {
        assertEquals(ChallengeResume.SiteSearch("a"), ChallengeResume.Favorites.merging(ChallengeResume.SiteSearch("a")))
        assertEquals(
            ChallengeResume.SiteSearch("a"),
            ChallengeResume.SiteSearch("a").merging(ChallengeResume.Favorites),
            "补查收藏不顶掉要重跑的搜索",
        )
        assertEquals(ChallengeResume.SiteSearch("b"), ChallengeResume.SiteSearch("a").merging(ChallengeResume.SiteSearch("b")))
        assertEquals(ChallengeResume.Reload, ChallengeResume.Reload.merging(ChallengeResume.SiteSearch("a")))
        assertEquals(ChallengeResume.Reload, ChallengeResume.SiteSearch("a").merging(ChallengeResume.Reload))
        assertEquals(ChallengeResume.Favorites, ChallengeResume.Favorites.merging(ChallengeResume.Favorites))
    }

    @Test
    fun testMissWithPreviousSearchRestoresSourceFirst() {
        assertEquals(SiteSearch.MissRecovery.RestoreSource, SiteSearch.missRecovery(active = "old"))
        assertEquals(SiteSearch.MissRecovery.None, SiteSearch.missRecovery(active = null))
    }

    @Test
    fun testMissOutcomeAbandonsWhenSupersededDuringRestore() {
        val bob = Room(username = "bob")
        assertEquals(
            SiteSearch.MissOutcome.Abandon,
            SiteSearch.missOutcome(keyword = "bob", fallback = bob, fallbackBlocked = false, failed = false, stillCurrent = false),
            "恢复原来源期间用户发起新搜索或选了收藏：不再直达、不提示",
        )
        assertEquals(
            SiteSearch.MissOutcome.Direct(bob),
            SiteSearch.missOutcome(keyword = "bob", fallback = bob, fallbackBlocked = false, failed = false, stillCurrent = true),
        )
        assertEquals(
            SiteSearch.MissOutcome.Notify("全站搜索失败，稍后再试"),
            SiteSearch.missOutcome(keyword = "bob", fallback = bob, fallbackBlocked = true, failed = true, stillCurrent = true),
        )
        assertEquals(
            SiteSearch.MissOutcome.Notify("全站没有找到「x y」"),
            SiteSearch.missOutcome(keyword = "x y", fallback = null, fallbackBlocked = false, failed = false, stillCurrent = true),
        )
    }

    @Test
    fun testBlankEditCancelsInflightFirstSearch() {
        assertTrue(SiteSearch.cancelsInflight(""), "首次搜索在途（active 为 nil）时清空搜索框要取消它")
        assertTrue(SiteSearch.cancelsInflight("  \n"))
        assertFalse(SiteSearch.cancelsInflight("jazz"))
    }

    @Test
    fun testDroppingSiteSearchIntent() {
        assertNull(ChallengeResume.SiteSearch("a").droppingSiteSearch())
        assertEquals(ChallengeResume.Reload, ChallengeResume.Reload.droppingSiteSearch())
        assertEquals(ChallengeResume.Favorites, ChallengeResume.Favorites.droppingSiteSearch())
    }

    @Test
    fun testCancelledOrStaleSearchMustNotStart() {
        val epoch = SearchEpoch()
        val token = epoch.begin()
        assertTrue(epoch.canStart(token, cancelled = false))
        assertFalse(epoch.canStart(token, cancelled = true), "Task 开始前已被取消：不改 loadGeneration / loading")
        epoch.invalidate()
        assertFalse(epoch.canStart(token, cancelled = false))
    }

    @Test
    fun testLoadingIsOnlyReleasedByItsOwner() {
        val epoch = SearchEpoch()
        assertFalse(epoch.releaseLoading(), "没认领过加载中：不复位别人的")
        val token = epoch.begin()
        epoch.claimLoading(token)
        assertTrue(epoch.releaseLoading())
        assertFalse(epoch.releaseLoading(), "只复位一次")
        val old = epoch.begin()
        epoch.claimLoading(old)
        val fresh = epoch.begin()
        assertFalse(epoch.releaseLoading(token = old), "过期的旧搜索不能复位新搜索认领的加载中")
        epoch.claimLoading(fresh)
        assertTrue(epoch.releaseLoading(token = fresh))
    }
}
