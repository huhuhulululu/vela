import SwiftUI

/// 垫底：当前房间的模糊缩略图，没有图时是柔和渐变。放在播放层下面，留边区自然露出它。
/// 尺寸只取父视图给的大小（`Color.clear` 打底，图放在叠加层里再裁掉），铺满的缩略图不会把父视图撑大（裁决 5）。
struct SwapBackdrop: View {
    let room: Room
    /// 带房间标识的下载结果：切房复用同一视图时，只有标识与当前房间一致才显示，迟到的旧图不会盖住新图。
    @State private var loaded: (username: String, image: CGImage)?

    /// 当前房间的缩略图：先用同房间的下载结果，其次用缓存；都没有时为 nil（显示柔和渐变，不显示上一间的图）。
    private var image: CGImage? {
        if let loaded, loaded.username == room.ref { return loaded.image }
        return RoomThumbnailCache.shared.image(for: room.usableThumbnail())
    }

    var body: some View {
        Color.clear
            .overlay { backdropLayer }
            .overlay { Theme.backdropScrim }
            .clipped()
            .task(id: room.ref) {
                let name = room.ref
                if loaded?.username != name { loaded = nil }
                guard let fetched = await RoomThumbnailCache.shared.imageOrFetch(for: room.usableThumbnail()),
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
        } else {
            ZStack {
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
}
