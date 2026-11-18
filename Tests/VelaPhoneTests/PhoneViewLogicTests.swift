import AVFoundation
import XCTest
@testable import VelaPhone

/// 单窗视图的纯逻辑（T14）：分页阈值判定、要渲染的邻页、`display-check` 日志拼装。
final class PhoneViewLogicTests: XCTestCase {
    private let height: CGFloat = 800

    // MARK: 翻页判定

    func testShortDragSnapsBack() {
        XCTAssertEqual(decide(-100, predicted: -150), 0)
        XCTAssertEqual(decide(100, predicted: 150), 0)
    }

    func testDragPastThresholdTurnsPage() {
        // 阈值是高度的 20%：800 × 0.2 = 160。
        XCTAssertEqual(decide(-160, predicted: -160), 1, "向上拖到下一间")
        XCTAssertEqual(decide(160, predicted: 160), -1, "向下拖到上一间")
        XCTAssertEqual(decide(-159, predicted: -159), 0)
    }

    func testFlingTurnsPageEvenWhenShort() {
        XCTAssertEqual(decide(-30, predicted: -420), 1)
        XCTAssertEqual(decide(30, predicted: 420), -1)
    }

    func testFlingNeedsMinimumTravel() {
        XCTAssertEqual(decide(-20, predicted: -600), 0, "轻触带出的速度不算甩动")
    }

    func testFlingAgainstDragDirectionDoesNotTurn() {
        // 先往上拖，松手前又往回甩：预计终点在反方向，不翻页。
        XCTAssertEqual(decide(-40, predicted: 500), 0)
    }

    func testNoNeighbourMeansSnapBack() {
        XCTAssertEqual(decide(-400, predicted: -600, hasNext: false), 0, "最后一间往上拖回弹")
        XCTAssertEqual(decide(400, predicted: 600, hasPrevious: false), 0, "第一间往下拖回弹")
        XCTAssertEqual(decide(-400, predicted: -600, hasPrevious: false), 1, "第一间仍可往下一间翻")
    }

    func testZeroHeightOrNoMovementNeverTurns() {
        XCTAssertEqual(PageSwipe.decision(translation: -400, predictedEnd: -600, height: 0, hasPrevious: true, hasNext: true), 0)
        XCTAssertEqual(decide(0, predicted: -600), 0)
    }

    func testEdgeDragIsDamped() {
        XCTAssertEqual(PageSwipe.offset(translation: -90, hasPrevious: true, hasNext: true), -90)
        XCTAssertEqual(PageSwipe.offset(translation: -90, hasPrevious: true, hasNext: false), -30, accuracy: 0.001)
        XCTAssertEqual(PageSwipe.offset(translation: 90, hasPrevious: false, hasNext: true), 30, accuracy: 0.001)
    }

    // MARK: 只渲染三页

    func testSlotsRenderAtMostThreePages() {
        XCTAssertEqual(PageSwipe.slots(count: 10, index: 4), [
            .init(index: 3, offset: -1), .init(index: 4, offset: 0), .init(index: 5, offset: 1)
        ])
    }

    func testSlotsAtEdges() {
        XCTAssertEqual(PageSwipe.slots(count: 10, index: 0), [.init(index: 0, offset: 0), .init(index: 1, offset: 1)])
        XCTAssertEqual(PageSwipe.slots(count: 10, index: 9), [.init(index: 8, offset: -1), .init(index: 9, offset: 0)])
        XCTAssertEqual(PageSwipe.slots(count: 1, index: 0), [.init(index: 0, offset: 0)])
        XCTAssertEqual(PageSwipe.slots(count: 0, index: 0), [])
        XCTAssertEqual(PageSwipe.slots(count: 3, index: 7), [.init(index: 1, offset: -1), .init(index: 2, offset: 0)], "越界下标夹到末尾")
    }

    // MARK: display-check

    func testDisplayCheckLineForLetterboxedLandscape() {
        let bounds = CGSize(width: 390, height: 844)
        let presentation = CGSize(width: 1920, height: 1080)
        let rect = DisplayFit.fitted(presentation: presentation, bounds: CGRect(origin: .zero, size: bounds))
        let line = DisplayCheck.line(
            lane: "primary", gravity: DisplayCheck.gravityName(.resizeAspect),
            presentation: presentation, bounds: bounds, videoRect: rect
        )
        XCTAssertEqual(
            line,
            "display-check lane=primary gravity=resizeAspect presentation=1920x1080 bounds=390x844 videoRect=390x219 uncropped=true"
        )
    }

    func testDisplayCheckFlagsCroppedRect() {
        // 铺满式的矩形超出窗格：必须判为裁切（负样本）。
        let line = DisplayCheck.line(
            lane: "secondary", gravity: DisplayCheck.gravityName(.resizeAspectFill),
            presentation: CGSize(width: 1920, height: 1080), bounds: CGSize(width: 390, height: 844),
            videoRect: CGRect(x: -555, y: 0, width: 1500, height: 844)
        )
        XCTAssertTrue(line.hasPrefix("display-check lane=secondary gravity=fill "))
        XCTAssertTrue(line.hasSuffix("videoRect=1500x844 uncropped=false"))
    }

    func testGravityNames() {
        XCTAssertEqual(DisplayCheck.gravityName(.resizeAspect), "resizeAspect")
        XCTAssertEqual(DisplayCheck.gravityName(.resize), "resize")
    }

    func testDisplaySizeUsesVideoRectOrEstimatesSixteenByNine() {
        let bounds = CGSize(width: 390, height: 844)
        XCTAssertEqual(DisplayCheck.displaySize(videoRect: CGRect(x: 0, y: 100, width: 390, height: 693), bounds: bounds),
                       CGSize(width: 390, height: 693), "竖屏流按实际矩形")
        let estimated = DisplayCheck.displaySize(videoRect: .zero, bounds: bounds)
        XCTAssertEqual(estimated.width, 390, accuracy: 0.001)
        XCTAssertEqual(estimated.height, 219.375, accuracy: 0.001, "还没有画面时按 16:9 估算")
    }

    func testDimsRoundsAndGuardsNonFinite() {
        XCTAssertEqual(DisplayCheck.dims(CGSize(width: 219.6, height: 0.4)), "220x0")
        XCTAssertEqual(DisplayCheck.dims(CGSize(width: CGFloat.infinity, height: 1)), "0x0")
    }

    private func decide(_ translation: CGFloat, predicted: CGFloat, hasPrevious: Bool = true, hasNext: Bool = true) -> Int {
        PageSwipe.decision(
            translation: translation, predictedEnd: predicted, height: height,
            hasPrevious: hasPrevious, hasNext: hasNext
        )
    }
}
