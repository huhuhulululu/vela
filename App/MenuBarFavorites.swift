import AppKit
import SwiftUI

/// 菜单栏图标是否显示：「播放」菜单里的开关，写进设置，默认开。相同值写入不通知，见 `MenuBarVisibility`。
extension MenuBarVisibility {
    static let shared: MenuBarVisibility = {
        let store = SettingsStore()
        return MenuBarVisibility(initial: store.showMenuBar) { store.showMenuBar = $0 }
    }()
}

/// 菜单栏弹出内容：在线收藏列表（与收藏夹弹窗同一数据源与排序）、摘要、刷新与打开收藏夹。
/// 菜单关着时不发请求；每次打开只在状态缓存过期时补查一次。
struct MenuBarFavorites: View {
    @ObservedObject var model: StageModel

    private static let width: CGFloat = 320
    private static let listMaxHeight: CGFloat = 360

    var body: some View {
        let board = model.favoritesBoard
        VStack(alignment: .leading, spacing: 8) {
            Text(board.menuSummary(checking: model.favoritesChecking))
                .font(Theme.titleFont)
                .foregroundStyle(Theme.textPrimary)
                .padding(.horizontal, 8)
            if !board.online.isEmpty {
                ScrollView {
                    VStack(spacing: 2) {
                        ForEach(board.online) { entry in
                            MenuBarFavoriteRow(entry: entry) { model.playFromMenuBar(entry.username) }
                        }
                    }
                }
                .frame(height: min(CGFloat(board.online.count) * Theme.onlineRowHeight, Self.listMaxHeight))
            }
            Divider().opacity(0.4)
            footer
        }
        .padding(10)
        .frame(width: Self.width)
        .background(MenuOpenObserver { Task { @MainActor in await model.refreshFavoritesIfStale() } })
    }

    private var footer: some View {
        HStack(spacing: 8) {
            Button { Task { await model.refreshFavorites() } } label: {
                HStack(spacing: 5) {
                    if model.favoritesChecking { ProgressView().controlSize(.mini) }
                    Text(model.favoritesChecking ? "查询中" : "刷新状态")
                }
                .font(Theme.bodyFont)
                .foregroundStyle(model.favoritesChecking ? Theme.textSecondary : Theme.champagne)
                .frame(minHeight: Theme.tapHeight)
                .contentShape(Rectangle())
            }
            .buttonStyle(QuietButtonStyle())
            .disabled(model.favoritesChecking)
            Spacer(minLength: 8)
            Button { model.openFavoritesFromMenuBar() } label: {
                Text("打开收藏夹")
                    .font(Theme.bodyFont)
                    .foregroundStyle(Theme.champagne)
                    .frame(minHeight: Theme.tapHeight)
                    .contentShape(Rectangle())
            }
            .buttonStyle(QuietButtonStyle())
        }
        .padding(.horizontal, 8)
    }
}

/// 一行在线收藏：缩略图、用户名、人数与开播时长，整行可点。
private struct MenuBarFavoriteRow: View {
    var entry: FavoriteEntry
    var play: () -> Void
    @State private var hovering = false

    var body: some View {
        Button(action: play) {
            HStack(spacing: 10) {
                CachedThumbnail(url: entry.thumbnailURL)
                VStack(alignment: .leading, spacing: 2) {
                    Text(entry.username).font(Theme.bodyFont).foregroundStyle(Theme.textPrimary)
                    Text(entry.detail)
                        .font(Theme.captionFont.monospacedDigit())
                        .foregroundStyle(Theme.textSecondary)
                }
                .lineLimit(1)
                Spacer(minLength: 0)
            }
            .padding(.horizontal, 8)
            .frame(height: Theme.onlineRowHeight)
            .background(hovering ? Theme.hoverFill : Color.clear, in: RoundedRectangle(cornerRadius: Theme.rowRadius, style: .continuous))
            .contentShape(Rectangle())
        }
        .buttonStyle(QuietButtonStyle())
        .onHover { hovering = $0 }
        .help("切换到 \(entry.username)")
    }
}

/// 走 `RoomThumbnailCache` 的小缩略图；加载中与失败都显示占位。
private struct CachedThumbnail: View {
    var url: URL?
    @State private var image: NSImage?

    var body: some View {
        ZStack {
            Rectangle().fill(.white.opacity(0.08))
            if let image {
                Image(nsImage: image).resizable().scaledToFill()
            } else {
                Image(systemName: "person.fill")
                    .font(.system(size: 13))
                    .foregroundStyle(Theme.textSecondary.opacity(0.6))
            }
        }
        .frame(width: Theme.thumbSize.width, height: Theme.thumbSize.height)
        .clipShape(RoundedRectangle(cornerRadius: Theme.thumbRadius, style: .continuous))
        .accessibilityHidden(true)
        .task(id: url) {
            image = RoomThumbnailCache.shared.image(for: url)
            if image == nil, let fetched = await RoomThumbnailCache.shared.imageOrFetch(for: url), !Task.isCancelled {
                image = fetched
            }
        }
    }
}

/// 菜单窗口每次成为关键窗口（即用户打开菜单）时回调一次；首次挂到窗口上也算一次。
private struct MenuOpenObserver: NSViewRepresentable {
    var onOpen: () -> Void

    func makeNSView(context: Context) -> HostView {
        let view = HostView()
        view.onOpen = onOpen
        return view
    }

    func updateNSView(_ view: HostView, context: Context) { view.onOpen = onOpen }

    final class HostView: NSView {
        var onOpen: () -> Void = {}
        private var token: NSObjectProtocol?

        override func viewDidMoveToWindow() {
            super.viewDidMoveToWindow()
            removeObserver()
            guard let window else { return }
            token = NotificationCenter.default.addObserver(forName: NSWindow.didBecomeKeyNotification, object: window, queue: .main) { [weak self] _ in
                self?.onOpen()
            }
            onOpen()
        }

        private func removeObserver() {
            if let token { NotificationCenter.default.removeObserver(token) }
            token = nil
        }

        deinit { removeObserver() }
    }
}
