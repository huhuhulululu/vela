import XCTest
#if SWIFT_PACKAGE
@testable import VelaCore
#else
@testable import VelaPhone
#endif

final class LaneSettlementTests: XCTestCase {
    let now = Date(timeIntervalSince1970: 1_800_000_000)

    func span(_ seconds: TimeInterval, focused: Bool) -> LaneSettlement.Span {
        LaneSettlement.Span(seconds: seconds, focused: focused)
    }

    func settle(
        lane: PlaybackLaneRole = .primary,
        username: String = "a",
        source: FeedSource = .guess,
        _ spans: [LaneSettlement.Span],
        end: LaneSettlement.EndReason,
        userInitiated: Bool = false
    ) -> LaneSettlement {
        LaneSettlement.settle(
            lane: lane, username: username, source: source, spans: spans,
            endReason: end, userInitiated: userInitiated
        )
    }

    func room(_ name: String, tags: [String]) -> Room {
        Room(username: name, viewers: 10, gender: nil, tags: tags, show: .publicShow)
    }

    func hit(_ taste: Taste, _ tag: String) -> Double {
        taste.posterior(Taste.tagKey(tag), now: now).alpha - 1
    }

    func miss(_ taste: Taste, _ tag: String) -> Double {
        taste.posterior(Taste.tagKey(tag), now: now).beta - 1
    }

    func dailyHits(_ taste: Taste) -> Int { taste.daily.values.reduce(0) { $0 + $1.hit } }
    func dailyMisses(_ taste: Taste) -> Int { taste.daily.values.reduce(0) { $0 + $1.miss } }

    /// 后台结束的 5 秒不算落空；账本仍记加权秒。较长的后台停留仍可记命中。
    func testBackgroundFiveSecondsIsNotAMiss() {
        let record = settle([span(5, focused: true)], end: .background, userInitiated: false)
        XCTAssertEqual(record.rawSeconds, 5)
        XCTAssertEqual(record.weightedSeconds, 5)
        XCTAssertEqual(record.focusedSeconds, 5)
        XCTAssertEqual(record.endReason, .background)
        XCTAssertEqual(record.lane, .primary)
        XCTAssertFalse(record.userInitiated)
        XCTAssertTrue(record.resumesAsNewDwell)
        let taste = record.recording(into: Taste(), room: room("a", tags: ["x"]), now: now)
        XCTAssertEqual(hit(taste, "x"), 0, accuracy: 1e-9)
        XCTAssertEqual(miss(taste, "x"), 0, accuracy: 1e-9)
        XCTAssertEqual(dailyHits(taste), 0)
        XCTAssertEqual(dailyMisses(taste), 0)
        let ledger = record.recording(into: WatchLedger(), start: 0, now: 5)
        XCTAssertEqual(ledger.entries[WatchLedger.key("a")]?.totalSeconds ?? -1, 5, accuracy: 1e-9)

        let long = settle([span(60, focused: true)], end: .background)
        let learned = long.recording(into: Taste(), room: room("a", tags: ["x"]), now: now)
        XCTAssertEqual(hit(learned, "x"), Feedback.grade(watched: 60).hit, accuracy: 1e-9)
        XCTAssertEqual(miss(learned, "x"), 0, accuracy: 1e-9)
        XCTAssertEqual(dailyHits(learned), 1)
        XCTAssertEqual(dailyMisses(learned), 0)
        XCTAssertTrue(long.resumesAsNewDwell)
    }

    /// 非焦点 40 秒：加权 20 秒用于学习，不进每日统计；焦点段 1.0、非焦点段 0.5。
    func testUnfocusedFortySecondsStayOutOfDailyStats() {
        let record = settle([span(40, focused: false)], end: .swipe, userInitiated: true)
        XCTAssertEqual(record.rawSeconds, 40)
        XCTAssertEqual(record.focusedSeconds, 0)
        XCTAssertEqual(record.weightedSeconds, 20)
        XCTAssertTrue(record.userInitiated)
        let taste = record.recording(into: Taste(), room: room("a", tags: ["x"]), now: now)
        XCTAssertEqual(dailyHits(taste), 0)
        XCTAssertEqual(dailyMisses(taste), 0)
        XCTAssertEqual(hit(taste, "x"), Feedback.grade(watched: 20).hit, accuracy: 1e-9)
        let ledger = record.recording(into: WatchLedger(), start: 1, now: 41)
        XCTAssertEqual(ledger.entries[WatchLedger.key("a")]?.totalSeconds ?? -1, 20, accuracy: 1e-9)

        let mixed = settle([span(20, focused: true), span(20, focused: false)], end: .swipe, userInitiated: true)
        XCTAssertEqual(mixed.rawSeconds, 40)
        XCTAssertEqual(mixed.focusedSeconds, 20)
        XCTAssertEqual(mixed.weightedSeconds, 30)
        let mixedTaste = mixed.recording(into: Taste(), room: room("a", tags: ["x"]), now: now)
        XCTAssertEqual(hit(mixedTaste, "x"), Feedback.grade(watched: 30).hit, accuracy: 1e-9)
        XCTAssertEqual(dailyHits(mixedTaste), 1)
    }

    /// 两路各记各的来源，学习权重和每日统计都不串。
    func testLaneSourcesDoNotCross() {
        let primary = settle(
            lane: .primary, username: "ann", source: .guess,
            [span(40, focused: true)], end: .swipe, userInitiated: true
        )
        let secondary = settle(
            lane: .secondary, username: "bee", source: .popular,
            [span(40, focused: true)], end: .swipe, userInitiated: true
        )
        XCTAssertEqual(primary.source, .guess)
        XCTAssertEqual(secondary.source, .popular)
        XCTAssertEqual(primary.username, "ann")
        XCTAssertEqual(secondary.username, "bee")
        var taste = Taste()
        taste = secondary.recording(into: taste, room: room("bee", tags: ["beta"]), now: now)
        taste = primary.recording(into: taste, room: room("ann", tags: ["alpha"]), now: now)
        XCTAssertEqual(hit(taste, "alpha"), Feedback.grade(watched: 40).hit, accuracy: 1e-9)
        XCTAssertEqual(hit(taste, "beta"), Feedback.grade(watched: 40).hit / 2, accuracy: 1e-9)
        XCTAssertEqual(dailyHits(taste), 1)
        XCTAssertEqual(dailyMisses(taste), 0)
        XCTAssertFalse(LaneSettlement.tracksHotRelated(isFocusLane: false))
        XCTAssertTrue(LaneSettlement.tracksHotRelated(isFocusLane: true))
    }

    /// 显式不记账：.background / .layoutChange 的落空段不学习、不进每日统计，只留「已看过」标记。
    func testBackgroundAndDualOffDoNotLearnExplicitly() {
        for end in [LaneSettlement.EndReason.background, .layoutChange] {
            let short = settle([span(2, focused: true)], end: end)
            XCTAssertFalse(short.learnsTaste, "\(end)")
            XCTAssertFalse(short.countsDaily, "\(end)")
            let taste = short.recording(into: Taste(), room: room("a", tags: ["x"]), now: now)
            XCTAssertTrue(taste.beliefs.isEmpty, "\(end)")
            XCTAssertTrue(taste.daily.isEmpty, "\(end)")
            XCTAssertNotNil(taste.lastSeen("a"), "\(end)")
        }
        let swipe = settle([span(2, focused: true)], end: .swipe, userInitiated: true)
        XCTAssertTrue(swipe.learnsTaste)
        XCTAssertTrue(swipe.countsDaily)
        let long = settle([span(40, focused: true)], end: .background)
        XCTAssertTrue(long.learnsTaste)
        XCTAssertTrue(long.countsDaily)
    }

    /// 弱落空只在 rawSeconds < 3 且结束原因是 swipe 时记。后台和关掉双窗不产生落空。
    func testWeakMissOnlyWhenUnderThreeSecondsAndSwipe() {
        let swipe = settle([span(2, focused: true)], end: .swipe, userInitiated: true)
        let background = settle([span(2, focused: true)], end: .background)
        let autoSkip = settle([span(2, focused: true)], end: .autoSkip)
        let layoutChange = settle([span(2, focused: true)], end: .layoutChange)
        let blocked = settle([span(2, focused: true)], end: .block, userInitiated: true)
        let atThree = settle([span(3, focused: true)], end: .swipe, userInitiated: true)

        let swipeTaste = swipe.recording(into: Taste(), room: room("a", tags: ["x"]), now: now)
        XCTAssertEqual(miss(swipeTaste, "x"), Feedback.skipMiss, accuracy: 1e-9)
        XCTAssertEqual(dailyMisses(swipeTaste), 1)
        XCTAssertEqual(swipe.learnSeconds, 2, accuracy: 1e-9)

        let backgroundTaste = background.recording(into: Taste(), room: room("a", tags: ["x"]), now: now)
        XCTAssertEqual(miss(backgroundTaste, "x"), 0, accuracy: 1e-9)
        XCTAssertEqual(dailyMisses(backgroundTaste), 0)
        XCTAssertTrue(background.resumesAsNewDwell)

        let autoTaste = autoSkip.recording(into: Taste(), room: room("a", tags: ["x"]), now: now)
        XCTAssertEqual(miss(autoTaste, "x"), 0, accuracy: 1e-9)
        XCTAssertEqual(dailyMisses(autoTaste), 1)

        let dualTaste = layoutChange.recording(into: Taste(), room: room("a", tags: ["x"]), now: now)
        XCTAssertEqual(miss(dualTaste, "x"), 0, accuracy: 1e-9)
        XCTAssertEqual(dailyMisses(dualTaste), 0)
        XCTAssertTrue(layoutChange.resumesAsNewDwell)

        let blockedTaste = blocked.recording(into: Taste(), room: room("a", tags: ["x"]), now: now)
        XCTAssertEqual(miss(blockedTaste, "x"), 0, accuracy: 1e-9)
        XCTAssertEqual(dailyMisses(blockedTaste), 1)

        let boundary = atThree.recording(into: Taste(), room: room("a", tags: ["x"]), now: now)
        XCTAssertEqual(miss(boundary, "x"), 0, accuracy: 1e-9)
        XCTAssertEqual(hit(boundary, "x"), 0, accuracy: 1e-9)
        XCTAssertEqual(dailyHits(boundary), 0)
        XCTAssertEqual(dailyMisses(boundary), 0)
        XCTAssertFalse(atThree.resumesAsNewDwell)
    }

    func testEndReasonLogTextPin() {
        let cases: [(LaneSettlement.EndReason, String)] = [
            (.swipe, "swipe"),
            (.autoSkip, "autoSkip"),
            (.block, "block"),
            (.background, "background"),
            (.layoutChange, "layoutChange"),
            (.sourceChange, "sourceChange"),
            (.failed, "failed"),
            (.notInterested, "notInterested"),
        ]
        for (reason, expected) in cases {
            XCTAssertEqual(String(describing: reason), expected)
        }
    }
}
