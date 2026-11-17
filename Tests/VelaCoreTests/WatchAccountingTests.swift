import XCTest
@testable import VelaCore

/// 观看账本的口径：暂停学习的时段不计、真实开始时刻、第三次观看的 60 秒检查点、作废后的停留。
final class WatchAccountingTests: XCTestCase {
    let t0 = Date(timeIntervalSince1970: 1_800_000_000)

    func at(_ seconds: TimeInterval) -> Date { t0.addingTimeInterval(seconds) }

    func room(_ name: String = "a") -> Room {
        Room(username: name, viewers: 10, gender: .female, tags: ["x"], show: .publicShow)
    }

    func playing(_ tracker: inout DwellTracker, at start: TimeInterval = 0) {
        _ = tracker.switchTo(room(), now: at(start))
        tracker.setActive(true, now: at(start))
    }

    // MARK: M2 暂停学习的时段不入账

    func testPausedSegmentIsExcludedFromLedgerSeconds() {
        var tracker = DwellTracker()
        playing(&tracker)
        tracker.setLedgerExcluded(true, now: at(30))
        XCTAssertEqual(tracker.ledgerDwell(now: at(100)), 30, accuracy: 0.001, "排除段内冻结")
        tracker.setLedgerExcluded(false, now: at(100))
        XCTAssertEqual(tracker.ledgerDwell(now: at(120)), 50, accuracy: 0.001)
        let settled = tracker.finish(now: at(130))
        XCTAssertEqual(settled?.dwell ?? 0, 130, accuracy: 0.001)
        XCTAssertEqual(settled?.ledgerSeconds ?? 0, 60, accuracy: 0.001)
    }

    func testStillExcludedWhenSettledKeepsOnlyPreSegment() {
        var tracker = DwellTracker()
        playing(&tracker)
        tracker.setLedgerExcluded(true, now: at(20))
        let settled = tracker.switchTo(room("b"), now: at(500))
        XCTAssertEqual(settled?.ledgerSeconds ?? 0, 20, accuracy: 0.001)
    }

    func testExclusionResetsOnRoomSwitchAndIsIdempotent() {
        var tracker = DwellTracker()
        playing(&tracker)
        tracker.setLedgerExcluded(true, now: at(10))
        tracker.setLedgerExcluded(true, now: at(50))
        tracker.setLedgerExcluded(false, now: at(60))
        tracker.setLedgerExcluded(false, now: at(70))
        XCTAssertEqual(tracker.ledgerDwell(now: at(70)), 20, accuracy: 0.001)
        _ = tracker.switchTo(room("b"), now: at(70))
        tracker.setActive(true, now: at(70))
        XCTAssertEqual(tracker.ledgerDwell(now: at(80)), 10, accuracy: 0.001)
    }

    func testExclusionAcrossInactiveGap() {
        var tracker = DwellTracker()
        playing(&tracker)
        tracker.setActive(false, now: at(40))
        tracker.setLedgerExcluded(true, now: at(40))
        tracker.setActive(true, now: at(100))
        tracker.setLedgerExcluded(false, now: at(130))
        XCTAssertEqual(tracker.ledgerDwell(now: at(130)), 40, accuracy: 0.001)
    }

    // MARK: M4 真实开始时刻

    func testStartedAtIsFirstActiveAndSurvivesPauses() {
        var tracker = DwellTracker()
        _ = tracker.switchTo(room(), now: at(0))
        XCTAssertNil(tracker.startedAt)
        tracker.setActive(true, now: at(5))
        tracker.setActive(false, now: at(65))
        tracker.setActive(true, now: at(300))
        XCTAssertEqual(tracker.startedAt, at(5))
        let settled = tracker.finish(now: at(400))
        XCTAssertEqual(settled?.start, at(5))
        XCTAssertNil(tracker.startedAt)
    }

    func testNeverPlayedHasNoStart() {
        var tracker = DwellTracker()
        _ = tracker.switchTo(room(), now: at(0))
        XCTAssertNil(tracker.finish(now: at(10))?.start)
    }

    func testLedgerVisitGapUsesRealStart() {
        // 真实开始在 31 分钟前，结算在暂停很久之后：按开始时刻算间隔，不被「结算时刻减累计」带偏。
        let first = WatchLedger().recording(username: "a", seconds: 70, start: t0.timeIntervalSince1970, now: t0.timeIntervalSince1970)
        let start = t0.timeIntervalSince1970 + 31 * 60
        let second = first.recording(username: "a", seconds: 70, start: start, now: start + 3 * 3600)
        XCTAssertEqual(second.entries["a"]?.visitCount, 2)
        XCTAssertEqual(second.entries["a"]?.lastVisitStart, start)
    }

    // MARK: M5 作废后的停留

    func testDiscardedIsExposedAndSettlesNothing() {
        var tracker = DwellTracker()
        playing(&tracker)
        XCTAssertFalse(tracker.isDiscarded)
        tracker.discard()
        XCTAssertTrue(tracker.isDiscarded)
        XCTAssertNil(tracker.finish(now: at(900)))
    }

    // MARK: M3 第三次观看的 60 秒检查点

    func ledgerWithVisits(_ count: Int) -> WatchLedger {
        var ledger = WatchLedger()
        for i in 0..<count {
            let start = t0.timeIntervalSince1970 + Double(i) * 3600
            ledger = ledger.recording(username: "a", seconds: 70, start: start, now: start + 70)
        }
        return ledger
    }

    func testWakesAtSixtySecondsWhenThirdVisitWouldQualify() {
        let ledger = ledgerWithVisits(2)
        let liveStart = t0.timeIntervalSince1970 + 3 * 3600
        let wait = ledger.secondsUntilNextSuggestCheck(
            username: "a", favorited: false, blocked: false, liveSeconds: 15, liveStart: liveStart
        )
        XCTAssertEqual(wait ?? 0, 45, accuracy: 0.001)
        XCTAssertTrue(ledger.shouldSuggest(username: "a", favorited: false, blocked: false, liveSeconds: 60, liveStart: liveStart))
    }

    func testNoVisitCheckpointWhenOnlyOneVisitRecorded() {
        let ledger = ledgerWithVisits(1)
        let wait = ledger.secondsUntilNextSuggestCheck(
            username: "a", favorited: false, blocked: false, liveSeconds: 15,
            liveStart: t0.timeIntervalSince1970 + 3 * 3600
        )
        XCTAssertEqual(wait ?? 0, 600 - 70 - 15, accuracy: 0.001, "只剩累计 10 分钟这个点")
    }

    func testNoVisitCheckpointInsideTheGapWindow() {
        let ledger = ledgerWithVisits(2)
        // 与上一次有效观看间隔不足 30 分钟：不会再多算一次，不为它安排唤醒。
        let liveStart = t0.timeIntervalSince1970 + 3600 + 10 * 60
        let wait = ledger.secondsUntilNextSuggestCheck(
            username: "a", favorited: false, blocked: false, liveSeconds: 5, liveStart: liveStart
        )
        XCTAssertEqual(wait ?? 0, 600 - 140 - 5, accuracy: 0.001)
    }

    func testNoCheckpointAfterSixtySecondsOrWhenSettled() {
        let ledger = ledgerWithVisits(2)
        let liveStart = t0.timeIntervalSince1970 + 3 * 3600
        XCTAssertNil(ledger.secondsUntilNextSuggestCheck(username: "a", favorited: false, blocked: false, liveSeconds: 61, liveStart: liveStart), "已够三次，已可提示")
        XCTAssertNil(ledger.secondsUntilNextSuggestCheck(username: "a", favorited: true, blocked: false, liveSeconds: 5, liveStart: liveStart))
        XCTAssertNil(ledger.markingSuggested("a").secondsUntilNextSuggestCheck(username: "a", favorited: false, blocked: false, liveSeconds: 5, liveStart: liveStart))
    }
}
