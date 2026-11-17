import XCTest
@testable import VelaCore

final class QualityGovernorTests: XCTestCase {
    let t0 = Date(timeIntervalSince1970: 1_800_000_000)
    func at(_ s: TimeInterval) -> Date { t0.addingTimeInterval(s) }

    func testStaysFullWhileEngagedOrDisabled() {
        var g = QualityGovernor(tier: .full, now: at(0))
        g.observe(enabled: true, engaged: true, now: at(0))
        XCTAssertEqual(g.desired(now: at(100)), .full)
        XCTAssertNil(g.delay(now: at(100)))
        g.observe(enabled: false, engaged: false, now: at(100))
        XCTAssertEqual(g.desired(now: at(200)), .full)
        XCTAssertNil(g.delay(now: at(200)))
    }

    func testDropsOnlyAfterRestDelay() {
        var g = QualityGovernor(tier: .full, now: at(0))
        g.observe(enabled: true, engaged: false, now: at(20))
        XCTAssertEqual(g.desired(now: at(25)), .full)
        XCTAssertEqual(g.delay(now: at(25))!, 5, accuracy: 0.001)
        XCTAssertEqual(g.desired(now: at(30)), .low)
        XCTAssertEqual(g.delay(now: at(30))!, 0, accuracy: 0.001)
    }

    func testBriefPassDoesNotSwitch() {
        var g = QualityGovernor(tier: .full, now: at(0))
        g.observe(enabled: true, engaged: false, now: at(20))
        g.observe(enabled: true, engaged: true, now: at(26))
        XCTAssertEqual(g.desired(now: at(40)), .full)
        XCTAssertNil(g.delay(now: at(40)))
    }

    func testRestoreWaitsMinIntervalAfterSwitch() {
        var g = QualityGovernor(tier: .full, now: at(0))
        g.observe(enabled: true, engaged: false, now: at(20))
        g.didSwitch(to: .low, now: at(30))
        XCTAssertNil(g.delay(now: at(31)))
        g.observe(enabled: true, engaged: true, now: at(32))
        XCTAssertEqual(g.desired(now: at(32)), .full)
        XCTAssertEqual(g.delay(now: at(32))!, 6, accuracy: 0.001)
        XCTAssertEqual(g.delay(now: at(40))!, 0, accuracy: 0.001)
    }

    func testReenteringRestAfterRestoreWaitsFullDelayAndInterval() {
        var g = QualityGovernor(tier: .low, now: at(0))
        g.observe(enabled: true, engaged: true, now: at(10))
        g.didSwitch(to: .full, now: at(10))
        g.observe(enabled: true, engaged: false, now: at(12))
        XCTAssertEqual(g.delay(now: at(12))!, 10, accuracy: 0.001)
        XCTAssertEqual(g.desired(now: at(21)), .full)
        XCTAssertEqual(g.desired(now: at(22)), .low)
    }

    func testDisablingWhileLowRestoresFull() {
        var g = QualityGovernor(tier: .low, now: at(0))
        g.observe(enabled: false, engaged: false, now: at(50))
        XCTAssertEqual(g.desired(now: at(50)), .full)
        XCTAssertEqual(g.delay(now: at(50))!, 0, accuracy: 0.001)
    }

    func testResetStartsAtGivenTierWithoutImmediateRestore() {
        var g = QualityGovernor(tier: .full, now: at(0))
        g.reset(tier: .low, now: at(100))
        g.observe(enabled: true, engaged: true, now: at(101))
        XCTAssertEqual(g.delay(now: at(101))!, 7, accuracy: 0.001)
    }

    func testLowStaysLowWhileResting() {
        var g = QualityGovernor(tier: .low, now: at(0))
        g.observe(enabled: true, engaged: false, now: at(0))
        XCTAssertEqual(g.desired(now: at(500)), .low)
        XCTAssertNil(g.delay(now: at(500)))
    }
}
