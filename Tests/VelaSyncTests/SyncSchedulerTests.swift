import XCTest
#if SWIFT_PACKAGE
import VelaCore
@testable import VelaSync
#endif

/// 触发时机与退避（docs/plans/sync.md 第 11.3 节）：无轮询，只有离散触发；退避期内的触发跳过。
final class SyncSchedulerTests: XCTestCase {
    func testLaunchAndForegroundAlwaysRunPanelsWaitSixtySeconds() {
        var s = SyncScheduler()
        XCTAssertTrue(s.allows(.launch, now: 0))
        XCTAssertTrue(s.allows(.panel, now: 0), "从未联系过服务端时面板触发也跑")
        s.record(.upToDate, now: 1_000)
        XCTAssertTrue(s.allows(.foreground, now: 2_000))
        XCTAssertFalse(s.allows(.panel, now: 61_000))
        XCTAssertTrue(s.allows(.panel, now: 61_001))
        XCTAssertFalse(s.allows(.activate, now: 30_000), "macOS 激活按面板口径")
    }

    func testEditDebounceAndMinimumGapBetweenPuts() {
        var s = SyncScheduler()
        XCTAssertEqual(s.editDelay(now: 0), 5_000)
        s.record(.pushed, now: 100_000)
        XCTAssertEqual(s.editDelay(now: 101_000), 9_000, "两次 PUT 至少间隔 10 秒")
        XCTAssertEqual(s.editDelay(now: 200_000), 5_000)
    }

    func testFailuresBackOffFromThirtySecondsDoublingToOneHour() {
        var s = SyncScheduler()
        s.record(.transient, now: 0)
        XCTAssertFalse(s.allows(.foreground, now: 29_999))
        XCTAssertTrue(s.allows(.foreground, now: 30_000))
        s.record(.transient, now: 30_000)
        XCTAssertFalse(s.allows(.localEdit, now: 89_999))
        XCTAssertTrue(s.allows(.localEdit, now: 90_000))
        for _ in 0..<20 { s.record(.transient, now: 0) }
        XCTAssertEqual(s.backoffUntil, 3_600_000)
        s.record(.pushed, now: 5)
        XCTAssertEqual(s.failures, 0)
        XCTAssertTrue(s.allows(.localEdit, now: 6))
    }

    func testRateLimitAndQuotaBackoff() {
        var s = SyncScheduler()
        s.record(.rateLimited(retryAfter: 42), now: 1_000)
        XCTAssertFalse(s.allows(.manual, now: 42_999), "429 连立即同步也按 Retry-After 等")
        XCTAssertTrue(s.allows(.manual, now: 43_000))
        s.record(.quotaFull, now: 0)
        XCTAssertFalse(s.allows(.launch, now: 3_599_999))
        var t = SyncScheduler()
        t.record(.transient, now: 0)
        XCTAssertFalse(t.allows(.localEdit, now: 1))
        XCTAssertTrue(t.allows(.manual, now: 1), "普通退避不挡用户点「立即同步」")
    }

    func testBackgroundOnlyWhenUnpushed() {
        let s = SyncScheduler()
        XCTAssertFalse(s.allowsBackground(gen: 3, pushedGen: 3, now: 0))
        XCTAssertTrue(s.allowsBackground(gen: 4, pushedGen: 3, now: 0))
    }

    /// 审计 medium：启动时钥匙串暂不可用或恢复加入时断网，之后回前台、激活、打开面板、点重试都要重新跑启动恢复。
    func testUnavailableStartupIsRetriedOnDiscreteTriggers() {
        for t: SyncScheduler.Trigger in [.foreground, .activate, .panel, .manual] {
            XCTAssertTrue(SyncScheduler.retriesStartup(unavailable: true, on: t), "\(t)")
        }
        XCTAssertFalse(SyncScheduler.retriesStartup(unavailable: true, on: .localEdit))
        XCTAssertFalse(SyncScheduler.retriesStartup(unavailable: false, on: .foreground))
        XCTAssertFalse(SyncScheduler.retriesStartup(unavailable: false, on: .foreground))
    }
}
