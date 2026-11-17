import XCTest
@testable import VelaCore

final class DwellTests: XCTestCase {
    let t0 = Date(timeIntervalSince1970: 1_800_000_000)

    func room(_ name: String, secs: Int? = nil) -> Room {
        Room(username: name, viewers: 10, gender: .female, tags: ["x"], show: .publicShow, secondsOnline: secs)
    }

    func at(_ seconds: TimeInterval) -> Date { t0.addingTimeInterval(seconds) }

    /// 与 StageModel.updateFeed 同一判定：改动前后各取当前房间，用户名不同才结算并换记账。
    func change(_ feed: inout FeedStore, _ tracker: inout DwellTracker, now: Date, _ body: (inout FeedStore) -> Void) -> DwellTracker.Settled? {
        let before = feed.current?.username
        body(&feed)
        guard feed.current?.username != before else { return nil }
        return tracker.switchTo(feed.current, now: now)
    }

    // MARK: H1

    func testSwitchSettlesPreviousRoomNotNewOne() {
        var feed = FeedStore()
        var tracker = DwellTracker()
        let page = CatalogPage(rooms: [room("a"), room("b")], total: 2, nextOffset: 2)
        XCTAssertNil(change(&feed, &tracker, now: at(0)) { $0.replace(with: page) })
        XCTAssertEqual(tracker.room?.username, "a")
        tracker.setActive(true, now: at(0))
        let settled = change(&feed, &tracker, now: at(25)) { $0.step(1) }
        XCTAssertEqual(settled?.room.username, "a")
        XCTAssertEqual(settled?.dwell ?? 0, 25, accuracy: 0.001)
        XCTAssertEqual(tracker.room?.username, "b")
    }

    func testFirstRoomIsRecordedWhenLeft() {
        var tracker = DwellTracker()
        XCTAssertNil(tracker.switchTo(room("first"), now: at(0)))
        tracker.setActive(true, now: at(0))
        let settled = tracker.switchTo(room("second"), now: at(70))
        XCTAssertEqual(settled?.room.username, "first")
        XCTAssertEqual(Taste().recording(room: settled!.room, watched: settled!.dwell, source: .guess, now: at(70)).lastSeen("first"), at(70))
    }

    func testRelatedFiresOnlyAfterThresholdInSameRoom() {
        var tracker = DwellTracker()
        _ = tracker.switchTo(room("a"), now: at(0))
        tracker.setActive(true, now: at(0))
        XCTAssertEqual(tracker.relatedPlan(for: "a", now: at(10)), .wait(20))
        XCTAssertEqual(tracker.relatedPlan(for: "A", now: at(30)), .due)
        XCTAssertEqual(tracker.relatedPlan(for: "b", now: at(30)), .none)
        tracker.markRelatedDone()
        XCTAssertEqual(tracker.relatedPlan(for: "a", now: at(31)), .none)
        _ = tracker.switchTo(room("b"), now: at(31))
        XCTAssertEqual(tracker.relatedPlan(for: "a", now: at(40)), .none)
    }

    // MARK: M2

    func testOnlyActiveTimeCounts() {
        var tracker = DwellTracker()
        _ = tracker.switchTo(room("a"), now: at(0))
        tracker.setActive(true, now: at(0))
        tracker.setActive(false, now: at(10))
        tracker.setActive(false, now: at(50))
        tracker.setActive(true, now: at(110))
        tracker.setActive(true, now: at(120))
        XCTAssertEqual(tracker.dwell(now: at(135)), 35, accuracy: 0.001)
        let settled = tracker.switchTo(nil, now: at(135))
        XCTAssertEqual(settled?.dwell ?? 0, 35, accuracy: 0.001)
    }

    func testPausedRoomDoesNotReachRelatedThreshold() {
        var tracker = DwellTracker()
        _ = tracker.switchTo(room("a"), now: at(0))
        tracker.setActive(true, now: at(0))
        tracker.setActive(false, now: at(5))
        // 暂停中不排唤醒（D3）：既不空转，也不会越过阈值。
        XCTAssertEqual(tracker.relatedPlan(for: "a", now: at(600)), .idle)
        tracker.setActive(true, now: at(600))
        XCTAssertEqual(tracker.relatedPlan(for: "a", now: at(600)), .wait(25))
    }

    // MARK: D1 / D2

    func testDwellOnlyCountsWhileCurrentRoomActuallyPlays() {
        XCTAssertTrue(PlaybackPolicy.dwellActive(allowed: true, playing: "A", current: "a"))
        // 新房间解析中 / 缓冲中：播放器没在播这个房间。
        XCTAssertFalse(PlaybackPolicy.dwellActive(allowed: true, playing: nil, current: "a"))
        // 上一个房间的播放项还在播，当前房间已换：不算。
        XCTAssertFalse(PlaybackPolicy.dwellActive(allowed: true, playing: "old", current: "a"))
        XCTAssertFalse(PlaybackPolicy.dwellActive(allowed: false, playing: "a", current: "a"))
        XCTAssertFalse(PlaybackPolicy.dwellActive(allowed: true, playing: "a", current: nil))
    }

    func testRoomThatNeverPlayedSettlesNothingAndNeverTriggersRelated() {
        var tracker = DwellTracker()
        _ = tracker.switchTo(room("a"), now: at(0))
        // 一直没出画面：从不 setActive，等多久都不到阈值，也不会触发关联。
        XCTAssertEqual(tracker.relatedPlan(for: "a", now: at(120)), .idle)
        XCTAssertEqual(tracker.dwell(now: at(120)), 0)
        // 播放失败：即使中途播过，结算也不记信号。
        tracker.setActive(true, now: at(120))
        tracker.markFailed()
        XCTAssertNil(tracker.switchTo(room("b"), now: at(200)))
    }

    func testDiscardedRoomGivesNoPositiveSignalEvenAfterPlaybackReady() {
        var tracker = DwellTracker()
        _ = tracker.switchTo(room("a"), now: at(0))
        tracker.setActive(true, now: at(0))
        tracker.discard()
        tracker.clearFailure()
        XCTAssertNil(tracker.switchTo(room("b"), now: at(90)))
        // 换到新房间后恢复正常。
        tracker.setActive(true, now: at(90))
        XCTAssertEqual(tracker.switchTo(nil, now: at(100))?.dwell ?? 0, 10, accuracy: 0.001)
    }

    // MARK: L5

    func testFailedRoomLeavesNoLearningSignal() {
        var tracker = DwellTracker()
        _ = tracker.switchTo(room("bad"), now: at(0))
        tracker.markFailed()
        XCTAssertNil(tracker.switchTo(room("next"), now: at(1.6)))
        tracker.setActive(true, now: at(2))
        tracker.markFailed()
        tracker.clearFailure()
        XCTAssertEqual(tracker.finish(now: at(10))?.room.username, "next")
    }

    func testFinishSettlesOnceAndEmpties() {
        var tracker = DwellTracker()
        _ = tracker.switchTo(room("a"), now: at(0))
        tracker.setActive(true, now: at(0))
        XCTAssertEqual(tracker.finish(now: at(4))?.dwell ?? 0, 4, accuracy: 0.001)
        XCTAssertNil(tracker.finish(now: at(9)))
    }
}
