import SwiftUI

/// 收藏夹弹窗的状态装配：把收藏、状态缓存、当前目录整理成 `FavoritesBoard`，接上回调。
struct CurationPanel: View {
    @ObservedObject var model: StageModel
    /// 导出、导入走平台回调：macOS 弹文件面板，iOS 用 `.fileExporter` / `.fileImporter`。
    var exportFile: () -> Void
    var importFile: () -> Void
    /// 面板宽度：macOS 固定 `Theme.panelWidth`；iOS 按可用宽度自适应（与口味面板一致）。
    var width: CGFloat? = CurationPanel.platformWidth

    #if os(iOS)
    static let platformWidth: CGFloat? = nil
    #else
    static let platformWidth: CGFloat? = Theme.panelWidth
    #endif

    var body: some View {
        FavoritesPanel(
            current: model.feed.current.map { ($0.displayName, model.currentIsFavorite) },
            board: board,
            blocked: model.curation.blocked,
            checking: model.favoritesChecking,
            listMaxHeight: Theme.favoritesListHeight(windowHeight: model.pointHeight),
            toggleCurrent: { model.toggleFavorite(model.target()) },
            blockCurrent: { model.blockCurrent(model.target()) },
            play: { model.playFavorite($0) },
            removeFavorite: { model.removeFavorite($0) },
            restoreFavorite: { model.restoreFavorite($0) },
            canRestore: { model.curation.canRestoreFavorite($0) },
            unblock: { model.unblock($0) },
            add: { model.addFavorite($0) },
            refresh: {
                Task {
                    await model.refreshFavorites()
                    await model.refreshLastBroadcastsIfStale()
                }
            },
            exportFile: exportFile,
            importFile: importFile,
            width: width
        )
        .task {
            await model.refreshFavoritesIfStale()
            await model.refreshLastBroadcastsIfStale()
        }
    }

    private var board: FavoritesBoard {
        var statuses: [String: FavoriteStatus] = [:]
        for name in model.curation.favorites {
            if let status = model.favoriteStatus.status(for: name) { statuses[name] = status }
        }
        return FavoritesBoard.make(
            favorites: model.curation.favorites, statuses: statuses, catalog: model.feed.catalog,
            lastBroadcast: model.lastBroadcasts.dates, now: Date()
        )
    }
}
