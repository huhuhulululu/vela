import XCTest
@testable import VelaCore

/// 全站搜索相关修复：本地真实匹配数、操作代次。
final class SearchFix13Tests: XCTestCase {
    private func room(_ name: String) -> Room { Room(username: name, show: .publicShow) }

    func testRetainedRoomDoesNotCountAsLocalMatch() {
        var feed = FeedStore(catalog: [room("alpha"), room("beta")], currentUsername: "alpha")
        feed.previewQuery(RoomQuery(text: "zzz-nobody"))
        XCTAssertEqual(feed.rooms.map(\.username), ["alpha"], "当前房间因 retained 仍在可见列表")
        XCTAssertEqual(feed.localMatches, 0, "本地其实没有匹配")
        XCTAssertEqual(
            SiteSearch.onSubmit(text: "zzz-nobody", localMatches: feed.localMatches, active: nil), .remote("zzz-nobody"),
            "播放中回车搜不存在的名字要走全站搜索"
        )
    }

    func testLocalMatchesCountsRealMatches() {
        var feed = FeedStore(catalog: [room("alpha"), room("alpine"), room("beta")], currentUsername: "beta")
        feed.previewQuery(RoomQuery(text: "alp"))
        XCTAssertEqual(feed.localMatches, 2)
        XCTAssertEqual(feed.rooms.count, 3, "beta 是 retained")
    }

    /// previewSearch（搜索框每次文字变化）走 onEdit 的判定：清空退出、非空输入期间不过滤也不换房。
    func testPreviewPathExitsOnClearAndKeepsWhileTyping() {
        XCTAssertEqual(SiteSearch.onEdit(text: "", active: "jazz"), .exit)
        XCTAssertEqual(SiteSearch.onEdit(text: " \n", active: "jazz"), .exit)
        XCTAssertEqual(SiteSearch.onEdit(text: "jazzy", active: "jazz"), .keep)
        XCTAssertEqual(SiteSearch.onEdit(text: "", active: nil), .filter)
    }

    func testSearchEpochInvalidatesOlderSearches() {
        var epoch = SearchEpoch()
        let first = epoch.begin()
        XCTAssertTrue(epoch.isCurrent(first))
        epoch.invalidate()
        XCTAssertFalse(epoch.isCurrent(first), "本地提交 / 收藏选择 / 退出搜索后旧搜索作废")
        let second = epoch.begin()
        let third = epoch.begin()
        XCTAssertFalse(epoch.isCurrent(second), "新搜索取代旧搜索")
        XCTAssertTrue(epoch.isCurrent(third))
    }

    func testChallengeResumeMergingKeepsSiteSearch() {
        XCTAssertEqual(ChallengeResume.favorites.merging(.siteSearch("a")), .siteSearch("a"))
        XCTAssertEqual(ChallengeResume.siteSearch("a").merging(.favorites), .siteSearch("a"), "补查收藏不顶掉要重跑的搜索")
        XCTAssertEqual(ChallengeResume.siteSearch("a").merging(.siteSearch("b")), .siteSearch("b"))
        XCTAssertEqual(ChallengeResume.reload.merging(.siteSearch("a")), .reload)
        XCTAssertEqual(ChallengeResume.siteSearch("a").merging(.reload), .reload)
        XCTAssertEqual(ChallengeResume.favorites.merging(.favorites), .favorites)
    }

    func testMissWithPreviousSearchRestoresSourceFirst() {
        XCTAssertEqual(SiteSearch.missRecovery(active: "old"), .restoreSource)
        XCTAssertEqual(SiteSearch.missRecovery(active: nil), .none)
    }

    func testMissOutcomeAbandonsWhenSupersededDuringRestore() {
        let room = Room(username: "bob")
        XCTAssertEqual(
            SiteSearch.missOutcome(keyword: "bob", fallback: room, fallbackBlocked: false, failed: false, stillCurrent: false),
            .abandon, "恢复原来源期间用户发起新搜索或选了收藏：不再直达、不提示"
        )
        XCTAssertEqual(
            SiteSearch.missOutcome(keyword: "bob", fallback: room, fallbackBlocked: false, failed: false, stillCurrent: true),
            .direct(room)
        )
        XCTAssertEqual(
            SiteSearch.missOutcome(keyword: "bob", fallback: room, fallbackBlocked: true, failed: true, stillCurrent: true),
            .notify("全站搜索失败，稍后再试")
        )
        XCTAssertEqual(
            SiteSearch.missOutcome(keyword: "x y", fallback: nil, fallbackBlocked: false, failed: false, stillCurrent: true),
            .notify("全站没有找到「x y」")
        )
    }

    func testBlankEditCancelsInflightFirstSearch() {
        XCTAssertTrue(SiteSearch.cancelsInflight(text: ""), "首次搜索在途（active 为 nil）时清空搜索框要取消它")
        XCTAssertTrue(SiteSearch.cancelsInflight(text: "  \n"))
        XCTAssertFalse(SiteSearch.cancelsInflight(text: "jazz"))
    }

    func testDroppingSiteSearchIntent() {
        XCTAssertNil(ChallengeResume.siteSearch("a").droppingSiteSearch())
        XCTAssertEqual(ChallengeResume.reload.droppingSiteSearch(), .reload)
        XCTAssertEqual(ChallengeResume.favorites.droppingSiteSearch(), .favorites)
    }

    func testCancelledOrStaleSearchMustNotStart() {
        var epoch = SearchEpoch()
        let token = epoch.begin()
        XCTAssertTrue(epoch.canStart(token, cancelled: false))
        XCTAssertFalse(epoch.canStart(token, cancelled: true), "Task 开始前已被取消：不改 loadGeneration / loading")
        epoch.invalidate()
        XCTAssertFalse(epoch.canStart(token, cancelled: false))
    }

    func testLoadingIsOnlyReleasedByItsOwner() {
        var epoch = SearchEpoch()
        XCTAssertFalse(epoch.releaseLoading(), "没认领过加载中：不复位别人的")
        let token = epoch.begin()
        epoch.claimLoading(token)
        XCTAssertTrue(epoch.releaseLoading())
        XCTAssertFalse(epoch.releaseLoading(), "只复位一次")
        let old = epoch.begin()
        epoch.claimLoading(old)
        let new = epoch.begin()
        XCTAssertFalse(epoch.releaseLoading(token: old), "过期的旧搜索不能复位新搜索认领的加载中")
        epoch.claimLoading(new)
        XCTAssertTrue(epoch.releaseLoading(token: new))
    }
}
