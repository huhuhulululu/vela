import SwiftUI
import XCTest
@testable import VelaPhone

/// 两端共享视图在 iOS 上的文案与布局：年龄确认页不出现 macOS 专属说法，口味面板在窄屏不溢出。
@MainActor
final class SharedCopyLayoutTests: XCTestCase {
    func testCommonAgeGateCopyHasNoDesktopWording() {
        let copy = AgeGateCopy.common
        for word in ["桌面", "窗口", "置顶", "Mac", "变淡"] {
            XCTAssertFalse(copy.headline.contains(word), "标题含 macOS 专属说法：\(word)")
            XCTAssertFalse(copy.blurb.contains(word), "正文含 macOS 专属说法：\(word)")
        }
        XCTAssertTrue(copy.blurb.contains("18 岁"), "年龄提示不能丢")
    }

    func testTastePanelFitsNarrowScreen() {
        for available in [320.0, 375.0] {
            let width = renderedWidth(TastePanel.fixture(width: nil), proposed: available)
            XCTAssertLessThanOrEqual(width, available + 0.5, "宽 \(available) 时口味面板溢出：\(width)")
        }
    }

    func testTastePanelCapsWidthOnWideScreen() {
        let width = renderedWidth(TastePanel.fixture(width: nil), proposed: 700)
        XCTAssertLessThanOrEqual(width, TastePanel.preferredWidth + 0.5)
    }

    private func renderedWidth(_ view: some View, proposed: CGFloat) -> CGFloat {
        let renderer = ImageRenderer(content: view)
        renderer.proposedSize = ProposedViewSize(width: proposed, height: nil)
        // 3 倍取样再折回点：ImageRenderer 按像素向上取整，1 倍时亚像素也会读成多出 1 点。
        renderer.scale = 3
        guard let image = renderer.cgImage else {
            XCTFail("渲染失败")
            return .infinity
        }
        return CGFloat(image.width) / 3
    }
}

private extension TastePanel {
    static func fixture(width: CGFloat?) -> TastePanel {
        TastePanel(
            summary: Taste().summary(now: Date()),
            suggestions: ["asian", "couple", "bigboobs", "latina", "teen", "mature"],
            paused: false,
            boost: { _ in }, veto: { _ in }, restore: { _ in }, remove: { _ in },
            setPaused: { _ in }, resetProfile: {}, clearAll: {},
            scrolls: false,
            width: width
        )
    }
}
