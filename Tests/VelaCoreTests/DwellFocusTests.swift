import XCTest
@testable import VelaCore

/// 停留里的焦点时间（§4.3）：双窗时每一路的停留要分得出「焦点在这一路」的秒数。
/// 焦点默认在这一路（单窗恒如此），此时焦点秒等于原始秒，与原来的口径一致。
final class DwellFocusTests: XCTestCase {
    let t0 = Date(timeIntervalSince1970: 1_800_000_000)

    func room(_ name: String) -> Room {
        Room(username: name, viewers: 10, gender: .female, tags: ["x"], show: .publicShow, secondsOnline: nil)
    }

    func at(_ seconds: TimeInterval) -> Date { t0.addingTimeInterval(seconds) }

    func testFocusedByDefaultSoFocusedSecondsEqualDwell() {
        var tracker = DwellTracker()
        _ = tracker.switchTo(room("a"), now: at(0))
        tracker.setActive(true, now: at(0))
        XCTAssertEqual(tracker.focusedDwell(now: at(12)), 12, accuracy: 0.001)
        let settled = tracker.switchTo(room("b"), now: at(30))
        XCTAssertEqual(settled?.dwell ?? 0, 30, accuracy: 0.001)
        XCTAssertEqual(settled?.focusedDwell ?? -1, 30, accuracy: 0.001)
    }

    func testUnfocusedFromTheStartHasNoFocusedSeconds() {
        var tracker = DwellTracker()
        tracker.setFocused(false, now: at(0))
        _ = tracker.switchTo(room("a"), now: at(0))
        tracker.setActive(true, now: at(0))
        let settled = tracker.finish(now: at(10))
        XCTAssertEqual(settled?.dwell ?? 0, 10, accuracy: 0.001)
        XCTAssertEqual(settled?.focusedDwell ?? -1, 0, accuracy: 0.001)
    }

    func testFocusTogglingDuringPlaybackSplitsSeconds() {
        var tracker = DwellTracker()
        _ = tracker.switchTo(room("a"), now: at(0))
        tracker.setActive(true, now: at(0))
        tracker.setFocused(false, now: at(10))
        tracker.setFocused(true, now: at(16))
        let settled = tracker.finish(now: at(20))
        XCTAssertEqual(settled?.dwell ?? 0, 20, accuracy: 0.001)
        XCTAssertEqual(settled?.focusedDwell ?? -1, 14, accuracy: 0.001)
    }

    func testFocusChangeWhileInactiveOnlyChangesFlag() {
        var tracker = DwellTracker()
        _ = tracker.switchTo(room("a"), now: at(0))
        tracker.setFocused(false, now: at(5))
        tracker.setActive(true, now: at(8))
        tracker.setFocused(true, now: at(12))
        tracker.setActive(false, now: at(20))
        XCTAssertEqual(tracker.dwell(now: at(30)), 12, accuracy: 0.001)
        XCTAssertEqual(tracker.focusedDwell(now: at(30)), 8, accuracy: 0.001)
    }

    func testPausedSecondsAreNotCountedAsFocused() {
        var tracker = DwellTracker()
        _ = tracker.switchTo(room("a"), now: at(0))
        tracker.setActive(true, now: at(0))
        tracker.setActive(false, now: at(5))
        XCTAssertEqual(tracker.focusedDwell(now: at(50)), 5, accuracy: 0.001)
        tracker.setActive(true, now: at(60))
        XCTAssertEqual(tracker.focusedDwell(now: at(65)), 10, accuracy: 0.001)
    }

    func testSwitchRoomResetsSecondsButKeepsFocusFlag() {
        var tracker = DwellTracker()
        tracker.setFocused(false, now: at(0))
        _ = tracker.switchTo(room("a"), now: at(0))
        tracker.setActive(true, now: at(0))
        _ = tracker.switchTo(room("b"), now: at(10))
        tracker.setActive(true, now: at(10))
        XCTAssertEqual(tracker.focusedDwell(now: at(20)), 0, accuracy: 0.001, "焦点标志保留：这一路仍是非焦点")
        tracker.setFocused(true, now: at(20))
        XCTAssertEqual(tracker.focusedDwell(now: at(25)), 5, accuracy: 0.001)
    }

    func testRepeatedSameFocusIsNoOp() {
        var tracker = DwellTracker()
        _ = tracker.switchTo(room("a"), now: at(0))
        tracker.setActive(true, now: at(0))
        tracker.setFocused(true, now: at(5))
        tracker.setFocused(true, now: at(9))
        XCTAssertEqual(tracker.focusedDwell(now: at(10)), 10, accuracy: 0.001)
    }
}
