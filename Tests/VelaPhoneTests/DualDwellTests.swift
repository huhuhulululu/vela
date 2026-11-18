import XCTest
@testable import VelaPhone

/// 双窗的停留口径（§4.3，T19）：原始秒、加权秒（焦点 1.0、非焦点 0.5）、焦点秒，每路带自己的来源，
/// `.background` 与 `.dualOff` 不产生落空，热门关联只看焦点路。单窗焦点恒在，口径与原来一致。
/// 口味文件在测试进程的沙盒里，各用例会累加：命中与落空只比较前后增量。
@MainActor
final class DualDwellTests: XCTestCase {
    private var suites: [String] = []
    private let t0 = Date(timeIntervalSince1970: 1_800_000_000)

    override func setUp() {
        super.setUp()
        TestPrefs.reset()
    }

    override func tearDown() {
        TestPrefs.reset()
        for name in suites { UserDefaults().removePersistentDomain(forName: name) }
        suites = []
        super.tearDown()
    }

    private func makeStage(dual: Bool = true) -> StageModel {
        let name = "vela-phone-dwell-\(UUID().uuidString)"
        suites.append(name)
        let stage = TestPrefs.stage(settings: SettingsStore(defaults: UserDefaults(suiteName: name)!))
        stage.updateFeed(intent: .reload) {
            $0.replace(with: CatalogPage(rooms: ["alice", "bob", "carol"].map { Room.direct(username: $0)! }, total: 3, nextOffset: 3))
        }
        if dual { stage.setDual(true) }
        return stage
    }

    /// 在 `t0` 开始计时，`raw` 秒后结算；焦点只在前 `focused` 秒里在这一路。
    private func settled(_ name: String, raw: TimeInterval, focused: TimeInterval) -> DwellTracker.Settled {
        var tracker = DwellTracker()
        _ = tracker.switchTo(Room.direct(username: name), now: t0)
        tracker.setActive(true, now: t0)
        if focused < raw { tracker.setFocused(false, now: t0.addingTimeInterval(focused)) }
        return tracker.finish(now: t0.addingTimeInterval(raw))!
    }

    func testSettlementSplitsRawWeightedAndFocusedSeconds() {
        let stage = makeStage()
        let result = stage.settlement(for: settled("bob", raw: 20, focused: 10), lane: stage.secondary!, reason: .swipe)
        XCTAssertEqual(result.rawSeconds, 20, accuracy: 0.001)
        XCTAssertEqual(result.focusedSeconds, 10, accuracy: 0.001)
        XCTAssertEqual(result.weightedSeconds, 15, accuracy: 0.001, "焦点段 1.0、非焦点段 0.5")
        XCTAssertEqual(result.lane, .secondary)
        XCTAssertEqual(result.username, "bob")
        XCTAssertEqual(result.endReason, .swipe)
    }

    func testEachLaneKeepsItsOwnSource() {
        let stage = makeStage()
        stage.primary.dwellSource = .guess
        stage.secondary!.dwellSource = .popular
        let first = stage.settlement(for: settled("alice", raw: 30, focused: 30), lane: stage.primary, reason: .swipe)
        let second = stage.settlement(for: settled("bob", raw: 30, focused: 0), lane: stage.secondary!, reason: .swipe)
        XCTAssertEqual(first.source, .guess)
        XCTAssertEqual(second.source, .popular, "副路用自己的来源，不跟主路串")
    }

    func testSingleWindowSecondsAllEqual() {
        let stage = makeStage(dual: false)
        let result = stage.settlement(for: settled("alice", raw: 45, focused: 45), lane: stage.primary, reason: .swipe)
        XCTAssertEqual(result.rawSeconds, result.weightedSeconds, accuracy: 0.001)
        XCTAssertEqual(result.rawSeconds, result.focusedSeconds, accuracy: 0.001)
    }

    func testBackgroundAndDualOffShortStaysAreNotMisses() {
        let stage = makeStage()
        let short = settled("alice", raw: 1, focused: 1)
        XCTAssertTrue(stage.settlement(for: short, lane: stage.primary, reason: .swipe).learnsTaste, "划走的 1 秒是弱落空")
        XCTAssertFalse(stage.settlement(for: short, lane: stage.primary, reason: .background).learnsTaste)
        XCTAssertFalse(stage.settlement(for: short, lane: stage.primary, reason: .dualOff).learnsTaste)
        XCTAssertFalse(stage.settlement(for: short, lane: stage.primary, reason: .sourceChange).learnsTaste)
    }

    func testDailyHitRateUsesFocusedSecondsOnly() {
        let stage = makeStage()
        let neverFocused = stage.settlement(for: settled("bob", raw: 60, focused: 0), lane: stage.secondary!, reason: .swipe)
        XCTAssertFalse(neverFocused.countsDaily, "一直没有焦点的路不进每日命中率")
        let mostlyFocused = stage.settlement(for: settled("alice", raw: 60, focused: 40), lane: stage.primary, reason: .swipe)
        XCTAssertTrue(mostlyFocused.countsDaily)
        XCTAssertEqual(mostlyFocused.dailySeconds, 40, accuracy: 0.001)
    }

    // MARK: 记账

    private func dailyTotals(_ stage: StageModel) -> (hit: Int, miss: Int) {
        stage.taste.daily.values.reduce((0, 0)) { ($0.0 + $1.hit, $0.1 + $1.miss) }
    }

    func testLedgerRecordsWeightedSeconds() {
        let stage = makeStage()
        stage.record(settled("bob", raw: 20, focused: 10), from: stage.secondary!, reason: .swipe)
        XCTAssertEqual(stage.watchLedger.entries["bob"]?.totalSeconds ?? 0, 15, accuracy: 0.001)
    }

    func testSingleWindowLedgerEqualsRawSeconds() {
        let stage = makeStage(dual: false)
        stage.record(settled("alice", raw: 20, focused: 20), from: stage.primary, reason: .swipe)
        XCTAssertEqual(stage.watchLedger.entries["alice"]?.totalSeconds ?? 0, 20, accuracy: 0.001)
    }

    func testFocusedLaneHitCountsDailyAndNeverFocusedDoesNot() {
        let stage = makeStage()
        let base = dailyTotals(stage)
        stage.record(settled("alice", raw: 30, focused: 30), from: stage.primary, reason: .swipe)
        XCTAssertEqual(dailyTotals(stage).hit, base.hit + 1)
        stage.record(settled("bob", raw: 30, focused: 0), from: stage.secondary!, reason: .swipe)
        XCTAssertEqual(dailyTotals(stage).hit, base.hit + 1, "从没有焦点的路不进每日命中率")
        XCTAssertEqual(dailyTotals(stage).miss, base.miss)
    }

    func testQuickSwipeOnFocusLaneStillCountsDailyMiss() {
        let stage = makeStage()
        let base = dailyTotals(stage)
        // 焦点路上还没出画面（0 秒）就划走：与单窗原来一样是一次落空。
        var tracker = DwellTracker()
        _ = tracker.switchTo(Room.direct(username: "alice"), now: t0)
        let none = tracker.finish(now: t0.addingTimeInterval(1))!
        stage.record(none, from: stage.primary, reason: .swipe)
        XCTAssertEqual(dailyTotals(stage).miss, base.miss + 1)
    }

    func testBackgroundShortStayRecordsNoMiss() {
        let stage = makeStage()
        let base = dailyTotals(stage)
        stage.record(settled("alice", raw: 1, focused: 1), from: stage.primary, reason: .background)
        XCTAssertEqual(dailyTotals(stage).miss, base.miss, "进后台结束的 1 秒不算落空")
    }

    // MARK: 焦点与热门关联

    func testDwellFocusFlagsFollowFocusLane() {
        let stage = makeStage()
        let now = Date()
        for lane in stage.lanes {
            XCTAssertEqual(lane.dwell.focusedDwell(now: now), 0, accuracy: 0.001)
        }
        stage.setFocus(.secondary)
        stage.primary.dwell.setActive(true, now: now)
        stage.secondary!.dwell.setActive(true, now: now)
        let later = now.addingTimeInterval(10)
        XCTAssertEqual(stage.primary.dwell.focusedDwell(now: later), 0, accuracy: 0.001, "焦点已交给副路")
        XCTAssertEqual(stage.secondary!.dwell.focusedDwell(now: later), 10, accuracy: 0.001)
    }

    func testRelatedPromotionOnlyOnFocusLane() {
        let stage = makeStage()
        stage.scheduleRelated(on: stage.secondary!)
        XCTAssertNil(stage.secondary!.relatedTask, "非焦点路不排热门关联")
        stage.setFocus(.secondary)
        stage.scheduleRelated(on: stage.primary)
        XCTAssertNil(stage.primary.relatedTask)
    }

    func testSettleDwellOnBackgroundPassesReasonAndSkipsMiss() {
        let stage = makeStage()
        let base = dailyTotals(stage)
        // 主路在计时但不足 3 秒：进后台结算原因是 .background，不记落空。
        stage.primary.dwell.setActive(true, now: Date())
        stage.settleDwell(.background)
        XCTAssertEqual(dailyTotals(stage).miss, base.miss)
    }

    func testFavoriteSuggestFollowsFocusLane() {
        let stage = makeStage()
        XCTAssertEqual(stage.focusRoom?.username, "alice")
        stage.setFocus(.secondary)
        XCTAssertEqual(stage.focusRoom?.username, "bob")
        XCTAssertEqual(stage.currentIsFavorite, false)
        stage.commit(stage.curation.favoriting("bob"), nil)
        XCTAssertTrue(stage.currentIsFavorite, "收藏状态看焦点路的房间")
    }
}
