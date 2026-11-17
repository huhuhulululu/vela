import XCTest
@testable import VelaCore

final class PointerPollingTests: XCTestCase {
    func testPollsOnlyWhileFadeIsOnAndTheWindowCanBePointedAt() {
        XCTAssertTrue(PointerPolling.needed(
            conditionalOpacity: true, windowVisible: true, hasWindow: true,
            bossHidden: false, systemInactive: false, appHidden: false
        ))
        XCTAssertFalse(PointerPolling.needed(
            conditionalOpacity: false, windowVisible: true, hasWindow: true,
            bossHidden: false, systemInactive: false, appHidden: false
        ))
        XCTAssertFalse(PointerPolling.needed(
            conditionalOpacity: true, windowVisible: false, hasWindow: true,
            bossHidden: false, systemInactive: false, appHidden: false
        ))
        XCTAssertFalse(PointerPolling.needed(
            conditionalOpacity: true, windowVisible: true, hasWindow: false,
            bossHidden: false, systemInactive: false, appHidden: false
        ))
    }

    func testSleepBossKeyAndHiddenAppStopPolling() {
        let stopped = [
            PointerPolling.needed(
                conditionalOpacity: true, windowVisible: true, hasWindow: true,
                bossHidden: true, systemInactive: false, appHidden: false
            ),
            PointerPolling.needed(
                conditionalOpacity: true, windowVisible: true, hasWindow: true,
                bossHidden: false, systemInactive: true, appHidden: false
            ),
            PointerPolling.needed(
                conditionalOpacity: true, windowVisible: true, hasWindow: true,
                bossHidden: false, systemInactive: false, appHidden: true
            ),
        ]
        XCTAssertEqual(stopped, [false, false, false])
    }
}
