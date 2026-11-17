import XCTest
@testable import VelaCore

/// 竖屏直播自动完整画面：判定、只对当前房间生效、离开恢复、手动撤销不改全局设置。
final class PortraitFitTests: XCTestCase {
    func testPortraitNeedsClearlyTallerFrame() {
        XCTAssertTrue(PortraitFit.isPortrait(width: 720, height: 1280))
        XCTAssertFalse(PortraitFit.isPortrait(width: 1280, height: 720))
        XCTAssertFalse(PortraitFit.isPortrait(width: 1000, height: 1010))
        XCTAssertFalse(PortraitFit.isPortrait(width: 0, height: 0))
        XCTAssertFalse(PortraitFit.isPortrait(width: .nan, height: 100))
    }

    func testPortraitRoomUsesFitOnlyWhileCurrent() {
        var fit = PortraitFit()
        fit.noteReady("Tall", width: 720, height: 1280)
        XCTAssertFalse(fit.fill(global: true, current: "tall"))
        XCTAssertTrue(fit.fill(global: true, current: "wide"))
        XCTAssertTrue(fit.fill(global: true, current: nil))
    }

    func testGlobalFitIsNeverTurnedIntoFill() {
        var fit = PortraitFit()
        fit.noteReady("tall", width: 720, height: 1280)
        XCTAssertFalse(fit.fill(global: false, current: "tall"))
        XCTAssertFalse(fit.fill(global: false, current: "wide"))
    }

    func testLandscapeOrUnknownSizeClearsThatRoom() {
        var fit = PortraitFit()
        fit.noteReady("tall", width: 720, height: 1280)
        fit.noteReady("wide", width: 1280, height: 720)
        XCTAssertEqual(fit.room, "tall")
        fit.noteReady("tall", width: 0, height: 0)
        XCTAssertNil(fit.room)
        XCTAssertTrue(fit.fill(global: true, current: "tall"))
    }

    func testToggleInsideAutoFitOnlyDismissesForThisRoom() {
        var fit = PortraitFit()
        fit.noteReady("tall", width: 720, height: 1280)
        XCTAssertTrue(fit.dismiss(global: true, current: "tall"))
        XCTAssertTrue(fit.fill(global: true, current: "tall"))
        // 同一房间重试、换档再出画面，不再自动套用。
        fit.noteReady("tall", width: 720, height: 1280)
        XCTAssertTrue(fit.fill(global: true, current: "tall"))
        // 换到别的房间后再回来，重新判定。
        fit.noteReady("wide", width: 1280, height: 720)
        fit.noteReady("tall", width: 720, height: 1280)
        XCTAssertFalse(fit.fill(global: true, current: "tall"))
    }

    func testToggleOutsideAutoFitIsLeftToGlobalSetting() {
        var fit = PortraitFit()
        XCTAssertFalse(fit.dismiss(global: true, current: "wide"))
        fit.noteReady("tall", width: 720, height: 1280)
        XCTAssertFalse(fit.dismiss(global: false, current: "tall"))
        XCTAssertFalse(fit.dismiss(global: true, current: "wide"))
        XCTAssertEqual(fit.room, "tall")
    }
}
