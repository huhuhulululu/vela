import XCTest
@testable import VelaCore

final class UpstreamRenewalTests: XCTestCase {
    let t0 = Date(timeIntervalSince1970: 1_800_000_000)

    func testOnlyExpiryStatusesTriggerRenewal() {
        XCTAssertTrue(RenewalPolicy.isExpiry(.http(403)))
        XCTAssertTrue(RenewalPolicy.isExpiry(.http(410)))
        XCTAssertFalse(RenewalPolicy.isExpiry(.http(404)))
        XCTAssertFalse(RenewalPolicy.isExpiry(.http(503)))
        XCTAssertFalse(RenewalPolicy.isExpiry(.badBody))
        XCTAssertFalse(RenewalPolicy.isExpiry(.transport(domain: "NSURLErrorDomain", code: -1001)))
        XCTAssertFalse(RenewalPolicy.isExpiry(nil))
    }

    func testBudgetLimitsAttemptsInWindow() {
        var budget = RenewalBudget(limit: 3, window: 60, minGap: 3)
        XCTAssertTrue(budget.take(now: t0))
        XCTAssertTrue(budget.take(now: t0.addingTimeInterval(4)))
        XCTAssertTrue(budget.take(now: t0.addingTimeInterval(8)))
        XCTAssertFalse(budget.take(now: t0.addingTimeInterval(12)), "窗口内用满 3 次后不再续期")
        XCTAssertFalse(budget.canRenew(now: t0.addingTimeInterval(59)))
        // 最早一次滑出窗口后恢复一次额度。
        XCTAssertTrue(budget.take(now: t0.addingTimeInterval(61)))
    }

    func testBudgetEnforcesMinGapWithoutConsuming() {
        var budget = RenewalBudget(limit: 3, window: 60, minGap: 3)
        XCTAssertTrue(budget.take(now: t0))
        XCTAssertFalse(budget.take(now: t0.addingTimeInterval(1)), "间隔太近")
        XCTAssertFalse(budget.take(now: t0.addingTimeInterval(2)), "被拒的不记次数")
        XCTAssertTrue(budget.take(now: t0.addingTimeInterval(3)))
        XCTAssertTrue(budget.take(now: t0.addingTimeInterval(6)))
        XCTAssertFalse(budget.take(now: t0.addingTimeInterval(9)))
    }

    func testLongWatchingKeepsRenewingAtSteadyPace() {
        // 若地址有绝对寿命（比如每几分钟过期一次），长时间观看不会被限次误伤。
        var budget = RenewalBudget()
        for minute in 0..<10 {
            XCTAssertTrue(budget.take(now: t0.addingTimeInterval(Double(minute) * 300)))
        }
    }

    /// G1：因取消而未完成的续期退回额度，不占最短间隔与次数。
    func testRefundFreesAttemptAndGap() {
        var budget = RenewalBudget(limit: 3, window: 60, minGap: 3)
        XCTAssertTrue(budget.take(now: t0))
        budget.refund(at: t0)
        XCTAssertTrue(budget.take(now: t0.addingTimeInterval(1)), "退回后不再受最短间隔限制")
        var full = RenewalBudget(limit: 1, window: 60, minGap: 3)
        XCTAssertTrue(full.take(now: t0))
        full.refund(at: t0)
        XCTAssertTrue(full.take(now: t0))
    }

    func testIsCancellationRecognizesCancelErrors() {
        XCTAssertTrue(RenewalPolicy.isCancellation(CancellationError()))
        XCTAssertTrue(RenewalPolicy.isCancellation(URLError(.cancelled)))
        XCTAssertTrue(RenewalPolicy.isCancellation(NSError(domain: NSURLErrorDomain, code: NSURLErrorCancelled)))
        XCTAssertFalse(RenewalPolicy.isCancellation(URLError(.timedOut)))
        XCTAssertFalse(RenewalPolicy.isCancellation(RelayFailure.http(403)))
    }
}
