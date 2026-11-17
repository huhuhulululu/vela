import XCTest
@testable import VelaCore

final class SkipTrackerTests: XCTestCase {
    func testSkipDirectionFollowsLastManualStep() {
        var skips = SkipTracker()
        XCTAssertEqual(skips.direction, 1)
        skips.noteManual(-1)
        XCTAssertEqual(skips.direction, -1)
        skips.noteManual(5)
        XCTAssertEqual(skips.direction, 1)
        skips.noteManual(0)
        XCTAssertEqual(skips.direction, 1)
    }

    func testSkipStopsAfterLimitAndResets() {
        var skips = SkipTracker()
        for _ in 0..<SkipTracker.limit { XCTAssertTrue(skips.registerAuto()) }
        XCTAssertFalse(skips.registerAuto())
        XCTAssertFalse(skips.registerAuto())
        skips.reset()
        XCTAssertTrue(skips.registerAuto())
        XCTAssertEqual(skips.direction, 1)
    }

    func testManualStepClearsSkipCountButAutoDoesNot() {
        var skips = SkipTracker()
        _ = skips.registerAuto()
        _ = skips.registerAuto()
        skips.noteManual(-1)
        XCTAssertEqual(skips.count, 0)
        XCTAssertGreaterThanOrEqual(SkipTracker.noticeSeconds, 1.5)
    }
}
