import AppKit
import UniformTypeIdentifiers

/// macOS 专用的模型入口：菜单栏收藏把主窗口带到前面、收藏导出导入的文件面板。
extension StageModel {
    /// 菜单栏用：把主窗口带到前面。老板键隐藏中走老板键的恢复路径（取消隐藏、恢复解码），
    /// 其余情况取消应用隐藏、还原最小化并置前。
    func revealMainWindow() {
        if bossHidden { toggleBoss() }
        platform.revealMain()
    }

    func playFromMenuBar(_ name: String) {
        revealMainWindow()
        playFavorite(name)
    }

    func openFavoritesFromMenuBar() {
        revealMainWindow()
        dismissSurfaces()
        showCuration = true
    }

    func exportFavorites() {
        let panel = NSSavePanel()
        panel.allowedContentTypes = [.json]
        panel.nameFieldStringValue = "vela-favorites.json"
        panel.begin { [weak self] response in
            guard response == .OK, let url = panel.url else { return }
            MainActor.assumeIsolated { self?.writeFavorites(to: url) }
        }
    }

    private func writeFavorites(to url: URL) {
        do {
            try exportFavoritesData().write(to: url, options: .atomic)
            showToast("已导出 \(curation.favorites.count) 个收藏")
        } catch {
            showToast("导出失败")
        }
    }

    func importFavorites() {
        let panel = NSOpenPanel()
        panel.allowedContentTypes = [.json]
        panel.allowsMultipleSelection = false
        panel.begin { [weak self] response in
            guard response == .OK, let url = panel.url else { return }
            MainActor.assumeIsolated { self?.importFavorites(url) }
        }
    }
}
