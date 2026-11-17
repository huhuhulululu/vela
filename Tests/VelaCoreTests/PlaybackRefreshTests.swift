import XCTest
@testable import VelaCore

final class PlaybackRefreshTests: XCTestCase {
    // MARK: E1 待刷新必须能结束

    func testFailureClearsPendingAndReportsReason() {
        var tracker = RefreshTracker()
        tracker.begin()
        XCTAssertEqual(tracker.fail(reason: "需要确认访问后才能播放"), "需要确认访问后才能播放")
        XCTAssertFalse(tracker.pending)
    }

    func testFailureWithoutReasonStillGivesText() {
        var tracker = RefreshTracker()
        tracker.begin()
        XCTAssertEqual(tracker.fail(reason: nil), "画面没能刷新")
    }

    func testNewItemOrStopClearsSoFailureIsSilent() {
        var tracker = RefreshTracker()
        tracker.begin()
        tracker.clear()
        XCTAssertNil(tracker.fail(reason: "x"))
        XCTAssertNil(tracker.fail(reason: "x"))
    }

    func testSupersededTaskDoesNotOwnOutcome() {
        XCTAssertTrue(RefreshTracker.ownsOutcome(taskGeneration: 4, currentGeneration: 5))
        XCTAssertTrue(RefreshTracker.ownsOutcome(taskGeneration: 4, currentGeneration: 4))
        XCTAssertFalse(RefreshTracker.ownsOutcome(taskGeneration: 4, currentGeneration: 6))
    }

    // MARK: E4 看门狗与失败文案

    func testWatchdogIgnoresItemWhileRefreshing() {
        XCTAssertTrue(PlaybackPolicy.startupStalled(sameItem: true, allowed: true, itemPlayed: false, isPlaying: false))
        XCTAssertFalse(PlaybackPolicy.startupStalled(sameItem: true, allowed: true, itemPlayed: false, isPlaying: false, refreshing: true))
    }

    func testRecoveryClearsOnlyPlayerNote() {
        XCTAssertTrue(PlaybackNoteRule.clearOnRecovery(note: "迟迟没有开始", playerNote: "迟迟没有开始"))
        XCTAssertFalse(PlaybackNoteRule.clearOnRecovery(note: "连续几个房间都无法播放", playerNote: "迟迟没有开始"))
        XCTAssertFalse(PlaybackNoteRule.clearOnRecovery(note: nil, playerNote: "x"))
        XCTAssertFalse(PlaybackNoteRule.clearOnRecovery(note: "x", playerNote: nil))
    }

    // MARK: E2 重叠重新加载的回滚点

    func testOverlappingReloadsRollBackToLoadedCursor() {
        var paging = PagingState()
        paging.commit(80)
        _ = paging.restart()
        let second = paging.restart()
        XCTAssertEqual(paging.offset, 0)
        paging.restore(second)
        XCTAssertEqual(paging.offset, 80)
        XCTAssertEqual(paging.committed, 80)
    }

    func testCommitMovesSnapshotOnlyOnSuccess() {
        var paging = PagingState(offset: 40)
        let previous = paging.restart()
        paging.commit(120)
        XCTAssertEqual(paging.restart().offset, 120)
        XCTAssertEqual(previous.offset, 40)
    }

    // MARK: E3 翻页期间被屏蔽光

    func testFillContinuesWhenListEmptyAndSameGeneration() {
        XCTAssertTrue(LoadFollowUp.needsFill(tokenUnchanged: true, visibleRooms: 0))
        XCTAssertFalse(LoadFollowUp.needsFill(tokenUnchanged: false, visibleRooms: 0))
        XCTAssertFalse(LoadFollowUp.needsFill(tokenUnchanged: true, visibleRooms: 3))
    }

    func testFillStopsAtBlankPageCap() {
        let feed = FeedStore(query: RoomQuery(publicOnly: false, blocked: []))
        XCTAssertTrue(feed.shouldLoadMore(blankPages: FeedStore.maxBlankPages - 1))
        XCTAssertFalse(feed.shouldLoadMore(blankPages: FeedStore.maxBlankPages))
    }
}
