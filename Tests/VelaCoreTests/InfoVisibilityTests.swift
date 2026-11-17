import XCTest
@testable import VelaCore

final class InfoVisibilityTests: XCTestCase {
    func testCurrentPageFollowsChrome() {
        XCTAssertTrue(InfoVisibility.visible(isCurrent: true, alwaysShow: false, chromeOn: true))
        XCTAssertFalse(InfoVisibility.visible(isCurrent: true, alwaysShow: false, chromeOn: false))
    }

    func testAlwaysShowKeepsCurrentPageInfo() {
        XCTAssertTrue(InfoVisibility.visible(isCurrent: true, alwaysShow: true, chromeOn: false))
    }

    func testNeighbourPagesKeepInfo() {
        XCTAssertTrue(InfoVisibility.visible(isCurrent: false, alwaysShow: false, chromeOn: false))
    }
}
