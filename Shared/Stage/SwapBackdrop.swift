import SwiftUI

/// 垫底：当前房间的模糊缩略图，没有图时是柔和渐变。放在播放层下面，留边区自然露出它。
struct SwapBackdrop: View {
    let room: Room
    /// 带房间标识的下载结果：切房复用同一视图时，只有标识与当前房间一致才显示，迟到的旧图不会盖住新图。
    @State private var loaded: (username: String, image: CGImage)?

    /// 当前房间的缩略图：先用同房间的下载结果，其次用缓存；都没有时为 nil（显示柔和渐变，不显示上一间的图）。
    private var image: CGImage? {
        if let loaded, loaded.username == room.username { return loaded.image }
        return RoomThumbnailCache.shared.image(for: room.thumbnailURL)
    }

    var body: some View {
        ZStack {
            backdropLayer
            Theme.backdropScrim
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .clipped()
        .task(id: room.username) {
            let name = room.username
            if loaded?.username != name { loaded = nil }
            guard let fetched = await RoomThumbnailCache.shared.imageOrFetch(for: room.thumbnailURL),
                  !Task.isCancelled else { return }
            loaded = (name, fetched)
        }
    }

    @ViewBuilder
    private var backdropLayer: some View {
        if let image {
            Image(decorative: image, scale: 1)
                .resizable()
                .scaledToFill()
                .scaleEffect(Theme.backdropScale)
                .blur(radius: Theme.backdropBlurRadius)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
        } else {
            LinearGradient(
                colors: [Theme.ink, Theme.backdropWarm, Theme.ink.opacity(0.92)],
                startPoint: .topLeading,
                endPoint: .bottomTrailing
            )
            Text(room.displayName.isEmpty ? room.username : room.displayName)
                .font(.system(size: 13, weight: .medium))
                .foregroundStyle(Theme.textSecondary)
        }
    }
}
