import XCTest
@testable import VelaCore

final class StageFlowTests: XCTestCase {
    func testAutoMoveKeepsManualPause() {
        XCTAssertTrue(MoveRules.pausedAfterMove(manuallyPaused: true, userInitiated: false))
        XCTAssertFalse(MoveRules.pausedAfterMove(manuallyPaused: false, userInitiated: false))
    }

    func testUserMoveClearsManualPause() {
        XCTAssertFalse(MoveRules.pausedAfterMove(manuallyPaused: true, userInitiated: true))
    }

    func testRestoreBringsBackCursor() {
        var state = PagingState(offset: 80)
        let previous = state.restart()
        XCTAssertEqual(state.offset, 0)
        state.restore(previous)
        XCTAssertEqual(state.offset, 80)
    }

    func testIdleStartFiresOnceWhenRoomAppears() {
        var gate = IdleStart()
        XCTAssertFalse(gate.takeStart(hasCurrent: true))
        gate.markIdle()
        XCTAssertFalse(gate.takeStart(hasCurrent: false))
        XCTAssertTrue(gate.takeStart(hasCurrent: true))
        XCTAssertFalse(gate.takeStart(hasCurrent: true))
    }

    func testClearDropsIdle() {
        var gate = IdleStart()
        gate.markIdle()
        gate.clear()
        XCTAssertFalse(gate.takeStart(hasCurrent: true))
    }
}

final class CatalogRestIdleTests: XCTestCase {
    private func room(_ name: String) -> Room {
        Room(username: name, viewers: 10, gender: .female, tags: [], show: .publicShow)
    }

    /// 首页只有一个房间并已起播；续页未返回时用户屏蔽它 → 空列表停播并标记空闲。
    /// 续页并入后出现可见房间，必须消耗空闲标记并要求起播。
    func testRestMergeAfterBlockingLastRoomResumesIdleStart() {
        var feed = FeedStore()
        feed.replace(with: CatalogPage(rooms: [room("a")], total: 3, nextOffset: 1))
        feed.setBlocked(["a"])
        XCTAssertNil(feed.current)
        var gate = IdleStart()
        gate.markIdle()
        XCTAssertFalse(gate.takeStart(hasCurrent: feed.current != nil))

        feed.mergePageAfterCurrent(CatalogPage(rooms: [room("b"), room("c")], total: 3, nextOffset: 3), from: 1) { $0 }
        XCTAssertEqual(feed.current?.username, "b")
        XCTAssertTrue(gate.takeStart(hasCurrent: feed.current != nil))
    }

    /// 已有房间在播（未空闲）时，续页并入不得重复起播。
    func testRestMergeDoesNotRestartWhenPlaying() {
        var feed = FeedStore()
        feed.replace(with: CatalogPage(rooms: [room("a")], total: 3, nextOffset: 1))
        var gate = IdleStart()
        feed.mergePageAfterCurrent(CatalogPage(rooms: [room("b")], total: 3, nextOffset: 2), from: 1) { $0 }
        XCTAssertEqual(feed.current?.username, "a")
        XCTAssertFalse(gate.takeStart(hasCurrent: feed.current != nil))
    }
}
