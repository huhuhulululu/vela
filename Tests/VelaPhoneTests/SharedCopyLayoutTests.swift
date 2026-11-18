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

    /// 两端共用的面板文案在 iOS 上不能出现 macOS 专属说法（这台 Mac、⌘ 快捷键、单击、窗口）。
    func testSharedPanelCopyHasNoMacOnlyWording() {
        let copies = [
            ("口味面板页眉", TastePanel.privacyNote),
            ("收藏夹空状态", FavoritesPanel.emptyHint),
            ("访问确认页眉", ChallengeContent.hint),
            ("收藏来源空列表", EmptyListCopy.message(
                catalogError: nil, query: RoomQuery(), catalog: [], source: .favorites, favoriteCount: 0
            ))
        ]
        for (place, text) in copies {
            for word in ["Mac", "⌘", "单击", "窗口", "桌面", "菜单栏"] {
                XCTAssertFalse(text.contains(word), "\(place)含 macOS 专属说法「\(word)」：\(text)")
            }
        }
        XCTAssertTrue(TastePanel.privacyNote.contains("不上传"), "隐私说明的要点不能丢")
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

    /// 收藏夹面板在 iPad 分屏的 320 点窄窗格里不被裁，宽屏不超过 macOS 的固定宽度。
    func testFavoritesPanelFitsNarrowPaneAndCapsOnWide() {
        let name = "vela-phone-layout-\(UUID().uuidString)"
        let stage = StageModel(settings: SettingsStore(defaults: UserDefaults(suiteName: name)!))
        defer { UserDefaults().removePersistentDomain(forName: name) }
        for available in [320.0, 375.0] {
            let width = renderedWidth(CurationPanel(model: stage, exportFile: {}, importFile: {}), proposed: available)
            XCTAssertLessThanOrEqual(width, available + 0.5, "宽 \(available) 时收藏夹面板溢出：\(width)")
        }
        let wide = renderedWidth(CurationPanel(model: stage, exportFile: {}, importFile: {}), proposed: 700)
        XCTAssertLessThanOrEqual(wide, Theme.panelWidth + 0.5)
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
