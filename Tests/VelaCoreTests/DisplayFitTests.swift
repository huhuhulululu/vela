import Foundation
import XCTest
@testable import VelaCore

/// 画面按原始比例放进窗格：完全落在窗格内，且宽高比与片源相差不超过 1%，才算没裁切。
final class DisplayFitTests: XCTestCase {
    /// 16:9 放进竖屏整屏（393×852）和双窗半屏（393×410）都是上下留边，uncropped 为真。
    func testSixteenByNineFitsPhoneAndHalfPaneUncropped() {
        let presentation = CGSize(width: 1920, height: 1080)
        let phone = CGRect(x: 0, y: 0, width: 393, height: 852)
        let pane = CGRect(x: 0, y: 0, width: 393, height: 410)
        for bounds in [phone, pane] {
            let fitted = DisplayFit.fitted(presentation: presentation, bounds: bounds)
            XCTAssertTrue(
                DisplayFit.isUncropped(videoRect: fitted, bounds: bounds, presentation: presentation),
                "bounds \(bounds.width)x\(bounds.height)"
            )
            XCTAssertGreaterThan(fitted.width, 0)
            XCTAssertGreaterThan(fitted.height, 0)
            XCTAssertLessThan(fitted.height, bounds.height, "16:9 在竖屏窗格里应当上下留边")
            XCTAssertGreaterThanOrEqual(fitted.minX, bounds.minX)
            XCTAssertGreaterThanOrEqual(fitted.minY, bounds.minY)
            XCTAssertLessThanOrEqual(fitted.maxX, bounds.maxX)
            XCTAssertLessThanOrEqual(fitted.maxY, bounds.maxY)
        }
    }

    /// aspectFill 会有一条边超出窗格，不能当成未裁切。
    func testAspectFillSizedVideoRectIsNotUncropped() {
        let presentation = CGSize(width: 16, height: 9)
        let boundsList = [
            CGRect(x: 0, y: 0, width: 393, height: 852),
            CGRect(x: 0, y: 0, width: 393, height: 410)
        ]
        for bounds in boundsList {
            let scale = max(bounds.width / presentation.width, bounds.height / presentation.height)
            let width = presentation.width * scale
            let height = presentation.height * scale
            let videoRect = CGRect(
                x: bounds.midX - width / 2,
                y: bounds.midY - height / 2,
                width: width,
                height: height
            )
            XCTAssertFalse(
                DisplayFit.isUncropped(videoRect: videoRect, bounds: bounds, presentation: presentation),
                "bounds \(bounds.width)x\(bounds.height)"
            )
        }
    }

    func testAspectToleranceIsOnePercent() {
        let presentation = CGSize(width: 100, height: 100)
        let bounds = CGRect(x: 0, y: 0, width: 200, height: 200)
        let onLimit = CGRect(x: 0, y: 0, width: 101, height: 100)
        let overLimit = CGRect(x: 0, y: 0, width: 102, height: 100)
        XCTAssertTrue(DisplayFit.isUncropped(videoRect: onLimit, bounds: bounds, presentation: presentation))
        XCTAssertFalse(DisplayFit.isUncropped(videoRect: overLimit, bounds: bounds, presentation: presentation))
    }
}
