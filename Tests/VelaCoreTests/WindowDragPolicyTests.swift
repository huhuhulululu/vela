import XCTest
@testable import VelaCore

final class WindowDragPolicyTests: XCTestCase {
    private let button = CGRect(x: 400, y: 300, width: 80, height: 28)

    private func canDrag(_ hit: DragHit, _ x: CGFloat, _ y: CGFloat, clickable: [CGRect]? = nil) -> Bool {
        WindowDragPolicy.canStartDrag(
            hit: hit, point: CGPoint(x: x, y: y), clickable: clickable ?? [button],
            contentHeight: 630, barHeight: 44
        )
    }

    // M1：自定义样式按钮（命中的是宿主视图）内按下，不进入拖窗；按钮之外的空白可以拖。
    func testPressInsideCustomButtonNeverDrags() {
        XCTAssertFalse(canDrag(.background, 440, 314))
        XCTAssertFalse(canDrag(.background, 400, 300), "区域边缘算在按钮内")
        XCTAssertTrue(canDrag(.background, 399, 314), "按钮外一点就是空白")
        XCTAssertTrue(canDrag(.background, 440, 340))
    }

    func testButtonOverVideoStillBlocksDrag() {
        XCTAssertFalse(canDrag(.video, 440, 314))
        XCTAssertTrue(canDrag(.video, 100, 100))
    }

    func testControlsAndDragStripHandleOwnGestures() {
        XCTAssertFalse(canDrag(.control, 100, 100, clickable: []))
        XCTAssertFalse(canDrag(.dragSurface, 100, 100, clickable: []))
    }

    func testBarBandBackgroundDoesNotDragHere() {
        XCTAssertFalse(canDrag(.background, 100, 600, clickable: []), "顶栏空白由拖动条处理")
        XCTAssertTrue(canDrag(.background, 100, 585, clickable: []))
    }

    func testMultipleRegions() {
        let regions = [button, CGRect(x: 10, y: 10, width: 40, height: 40)]
        XCTAssertFalse(canDrag(.background, 20, 20, clickable: regions))
        XCTAssertFalse(canDrag(.background, 450, 310, clickable: regions))
        XCTAssertTrue(canDrag(.background, 200, 200, clickable: regions))
    }
}
