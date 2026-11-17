import SwiftUI

/// 16:9 小缩略图：加载中与失败都给同一个占位。
struct FavoriteThumbnail: View {
    var url: URL?

    var body: some View {
        Group {
            if let url {
                AsyncImage(url: url) { phase in
                    if let image = phase.image { image.resizable().scaledToFill() } else { placeholder }
                }
            } else {
                placeholder
            }
        }
        .frame(width: Theme.thumbSize.width, height: Theme.thumbSize.height)
        .clipShape(RoundedRectangle(cornerRadius: Theme.thumbRadius, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: Theme.thumbRadius, style: .continuous).strokeBorder(.white.opacity(0.1), lineWidth: 0.5))
        .accessibilityHidden(true)
    }

    private var placeholder: some View {
        ZStack {
            Rectangle().fill(.white.opacity(0.08))
            Image(systemName: "person.fill")
                .font(.system(size: 13))
                .foregroundStyle(Theme.textSecondary.opacity(0.6))
        }
    }
}

/// 在线收藏：缩略图、用户名、人数与时长、右侧「观看」；整行可点，行尾常驻弱化的「移除」（悬停加强），右键菜单也能移除。
struct OnlineFavoriteRow: View {
    var entry: FavoriteEntry
    var play: () -> Void
    var remove: () -> Void
    @State private var hovering = false

    var body: some View {
        ZStack(alignment: .trailing) {
            Button(action: play) {
                HStack(spacing: 10) {
                    FavoriteThumbnail(url: entry.thumbnailURL)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(entry.username)
                            .font(Theme.bodyFont)
                            .foregroundStyle(Theme.textPrimary)
                        Text(entry.detail)
                            .font(Theme.captionFont.monospacedDigit())
                            .foregroundStyle(Theme.textSecondary)
                    }
                    .lineLimit(1)
                    Spacer(minLength: 8)
                    // 给行尾的「移除」留位，名字过长时先截断，不与它重叠。
                    Color.clear.frame(width: Self.removeWidth, height: 1)
                    watchPill
                }
                .padding(.horizontal, 8)
                .frame(height: Theme.onlineRowHeight)
                .background(hovering ? Theme.hoverFill : Color.clear, in: RoundedRectangle(cornerRadius: Theme.rowRadius, style: .continuous))
                .contentShape(Rectangle())
            }
            .buttonStyle(QuietButtonStyle())
            .help("切换到 \(entry.username)")
            Button(action: remove) {
                Text("移除")
                    .font(Theme.captionFont)
                    .lineLimit(1)
                    .fixedSize()
                    .foregroundStyle(hovering ? Theme.textPrimary : Theme.textSecondary.opacity(0.75))
                    .padding(.horizontal, 8)
                    .frame(height: 22)
                    .background(hovering ? Theme.ink.opacity(0.92) : Color.clear, in: Capsule())
                    .contentShape(Capsule())
            }
            .buttonStyle(QuietButtonStyle())
            .accessibilityLabel("移除 \(entry.username)")
            .padding(.trailing, 8 + Self.watchWidth + 8)
        }
        .onHover { hovering = $0 }
        .contextMenu { Button("移除收藏", action: remove) }
        .accessibilityAction(named: "移除收藏", remove)
    }

    static let watchWidth: CGFloat = 46
    static let removeWidth: CGFloat = 44

    private var watchPill: some View {
        Text("观看")
            .font(.system(size: 11, weight: .semibold))
            .lineLimit(1)
            .foregroundStyle(Color.black.opacity(0.86))
            .frame(width: Self.watchWidth, height: 24)
            .background(Theme.champagne, in: Capsule())
    }
}

/// 离线、未查询、不存在：行更紧凑、颜色弱化，不可点播放；状态文字始终保留，行尾常驻弱化的「移除」（悬停加强）。
struct OtherFavoriteRow: View {
    var entry: FavoriteEntry
    var remove: () -> Void
    @State private var hovering = false

    var body: some View {
        HStack(spacing: 8) {
            Circle().fill(dot).frame(width: 6, height: 6)
            Text(entry.username)
                .font(Theme.bodyFont)
                .foregroundStyle(Theme.textSecondary)
                .lineLimit(1)
            Spacer(minLength: 8)
            Text(entry.detail)
                .font(Theme.captionFont)
                .foregroundStyle(entry.state == .missing ? Theme.live.opacity(0.85) : Theme.textSecondary.opacity(0.8))
                .lineLimit(1)
            Button(action: remove) {
                Text("移除")
                    .font(Theme.captionFont)
                    .lineLimit(1)
                    .fixedSize()
                    .foregroundStyle(hovering ? Theme.champagne : Theme.textSecondary.opacity(0.75))
                    .frame(minHeight: 22)
                    .contentShape(Rectangle())
            }
            .buttonStyle(QuietButtonStyle())
            .accessibilityLabel("移除 \(entry.username)")
        }
        .padding(.horizontal, 8)
        .frame(height: Theme.otherRowHeight)
        .background(hovering ? Theme.hoverFill : Color.clear, in: RoundedRectangle(cornerRadius: Theme.rowRadius, style: .continuous))
        .onHover { hovering = $0 }
        .contextMenu { Button("移除收藏", action: remove) }
        .accessibilityElement(children: .combine)
        .accessibilityAction(named: "移除收藏", remove)
    }

    private var dot: Color {
        entry.state == .missing ? Theme.live.opacity(0.7) : Theme.textSecondary.opacity(0.4)
    }
}

/// 屏蔽名单二级页：返回、标题、名单与「解除屏蔽」。
struct BlockedPage: View {
    var blocked: [String]
    var listMaxHeight: CGFloat
    var back: () -> Void
    var unblock: (String) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 6) {
                Button(action: back) {
                    HStack(spacing: 4) {
                        Image(systemName: "chevron.left").font(.system(size: 11, weight: .semibold))
                        Text("收藏夹").font(Theme.bodyFont)
                    }
                    .foregroundStyle(Theme.champagne)
                    .frame(minHeight: Theme.tapHeight)
                    .contentShape(Rectangle())
                }
                .buttonStyle(QuietButtonStyle())
                Spacer()
            }
            VStack(alignment: .leading, spacing: 3) {
                Text("屏蔽名单（\(blocked.count)）").font(Theme.titleFont)
                Text("被屏蔽的房间不会出现在任何来源里。").font(Theme.captionFont).foregroundStyle(Theme.textSecondary).lineLimit(1)
            }
            if blocked.isEmpty {
                Text("没有屏蔽的房间。").font(Theme.captionFont).foregroundStyle(Theme.textSecondary)
            } else {
                ScrollView { rows }.frame(maxHeight: listMaxHeight)
            }
        }
    }

    private var rows: some View {
        VStack(spacing: 2) {
            ForEach(blocked, id: \.self) { name in
                HStack {
                    Text(name).font(Theme.bodyFont).lineLimit(1)
                    Spacer(minLength: 8)
                    Button(action: { unblock(name) }) {
                        Text("解除屏蔽")
                            .font(Theme.captionFont)
                            .lineLimit(1)
                            .fixedSize()
                            .foregroundStyle(Theme.champagne)
                            .frame(minHeight: 24)
                            .contentShape(Rectangle())
                    }
                    .buttonStyle(QuietButtonStyle())
                }
                .padding(.horizontal, 8)
                .frame(height: Theme.otherRowHeight)
            }
        }
    }
}
