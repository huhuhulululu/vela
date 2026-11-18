import SwiftUI

/// 收藏夹弹窗：在线的在前（缩略图、人数、时长，整行点击观看），其余弱化显示；屏蔽名单在二级页。
/// 只依赖显示数据与回调，不碰 StageModel，便于单独渲染自查。
struct FavoritesPanel: View {
    enum Page { case list, blocked }

    var current: (name: String, isFavorite: Bool)?
    var board: FavoritesBoard
    var blocked: [String]
    var checking: Bool
    var listMaxHeight: CGFloat = Theme.listMaxHeight
    var toggleCurrent: () -> Void
    var blockCurrent: () -> Void
    var play: (String) -> Void
    var removeFavorite: (String) -> Void
    var restoreFavorite: (String) -> Void
    /// 撤销条是否仍有效：名字之后被屏蔽或重新收藏就作废。
    var canRestore: (String) -> Bool
    var unblock: (String) -> Void
    /// 返回错误文案；nil 表示已添加。
    var add: (String) -> String?
    var refresh: () -> Void
    var exportFile: () -> Void
    var importFile: () -> Void

    @State private var page = Page.list
    @State private var draft = ""
    @State private var draftError: String?
    /// 刚移除的名字：底部显示「撤销」，几秒后自动消失；`undoToken` 让连续移除各自重新计时。
    @State private var removed: String?
    @State private var undoToken = 0

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            switch page {
            case .list: listPage
            case .blocked:
                BlockedPage(blocked: blocked, listMaxHeight: listMaxHeight, back: { page = .list }, unblock: unblock)
            }
        }
        .padding(Theme.panelPadding)
        .frame(width: Theme.panelWidth)
    }

    private var listPage: some View {
        VStack(alignment: .leading, spacing: 12) {
            header
            currentButton
            ScrollView { rows }.frame(maxHeight: listMaxHeight)
            if let removed, canRestore(removed) { undoBar(removed) }
            Divider().opacity(0.4)
            footer
        }
    }

    // MARK: 顶部

    private var header: some View {
        HStack(alignment: .top, spacing: 8) {
            VStack(alignment: .leading, spacing: 3) {
                Text("收藏夹").font(Theme.titleFont)
                Text(summary)
                    .font(Theme.captionFont)
                    .foregroundStyle(Theme.textSecondary)
                    .lineLimit(1)
            }
            Spacer(minLength: 8)
            Button(action: refresh) {
                HStack(spacing: 5) {
                    if checking { ProgressView().controlSize(.mini) }
                    Text(checking ? "查询中" : "刷新状态")
                }
                .font(Theme.bodyFont)
                .lineLimit(1)
                .fixedSize()
                .foregroundStyle(checking ? Theme.textSecondary : Theme.champagne)
                .frame(minHeight: Theme.tapHeight)
                .contentShape(Rectangle())
            }
            .buttonStyle(QuietButtonStyle())
            .disabled(checking)
        }
    }

    var summary: String {
        let total = board.online.count + board.others.count
        if total == 0 { return "还没有收藏" }
        return "\(board.onlineCount) 位在线 · 共 \(total) 个收藏"
    }

    @ViewBuilder
    private var currentButton: some View {
        let favorite = current?.isFavorite ?? false
        Button(action: toggleCurrent) {
            HStack(spacing: 6) {
                Image(systemName: favorite ? "heart.fill" : "heart")
                    .font(.system(size: 12, weight: .semibold))
                    .foregroundStyle(favorite ? Theme.favorite : Color.black.opacity(0.86))
                Text(favorite ? "已收藏当前房间" : "收藏当前房间").font(.system(size: 13, weight: .semibold))
            }
            .lineLimit(1)
            .foregroundStyle(favorite ? Theme.champagne : Color.black.opacity(0.86))
            .frame(maxWidth: .infinity, minHeight: 34)
            .background(favorite ? Theme.selectedFill : Theme.champagne, in: Capsule())
            .overlay(Capsule().strokeBorder(Theme.champagne.opacity(favorite ? 0.6 : 0), lineWidth: 1))
            .contentShape(Capsule())
        }
        .buttonStyle(QuietButtonStyle())
        .disabled(current == nil)
        .opacity(current == nil ? 0.4 : 1)
        .help(current == nil ? "没有当前房间" : (favorite ? "点击取消收藏 \(current?.name ?? "")" : "收藏 \(current?.name ?? "")"))
    }

    // MARK: 列表

    @ViewBuilder
    private var rows: some View {
        if board.isEmpty {
            emptyState
        } else {
            VStack(alignment: .leading, spacing: 14) {
                onlineSection
                if !board.others.isEmpty { othersSection }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
    }

    private var emptyState: some View {
        VStack(spacing: 6) {
            Text("还没有收藏").font(Theme.titleFont)
            Text(Self.emptyHint)
                .font(Theme.captionFont)
                .foregroundStyle(Theme.textSecondary)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 22)
    }

    private var onlineSection: some View {
        VStack(alignment: .leading, spacing: 4) {
            SectionTitle("在线", note: board.online.isEmpty ? nil : "\(board.onlineCount)")
                .padding(.horizontal, 8)
            if board.online.isEmpty {
                Text(onlineEmptyNote)
                    .font(Theme.captionFont)
                    .foregroundStyle(Theme.textSecondary)
                    .padding(.horizontal, 8)
                    .frame(minHeight: Theme.otherRowHeight, alignment: .leading)
            }
            ForEach(board.online) { entry in
                OnlineFavoriteRow(entry: entry,
                                  play: { play(entry.username) }, remove: { remove(entry.username) })
            }
        }
    }

    private var onlineEmptyNote: String {
        if checking { return "正在查询…" }
        if board.others.allSatisfy({ $0.state == .unknown }) { return "还没有查询，点右上角「刷新状态」" }
        return "收藏的人现在都不在线"
    }

    private var othersSection: some View {
        VStack(alignment: .leading, spacing: 2) {
            SectionTitle("其余", note: "\(board.others.count)")
                .padding(.horizontal, 8)
                .padding(.bottom, 2)
            ForEach(board.others) { entry in
                OtherFavoriteRow(entry: entry, remove: { remove(entry.username) })
            }
        }
    }

    private func remove(_ name: String) {
        removeFavorite(name)
        removed = name
        undoToken += 1
    }

    private func undoBar(_ name: String) -> some View {
        HStack(spacing: 8) {
            Text("已移除 \(name)")
                .font(Theme.captionFont)
                .foregroundStyle(Theme.textPrimary)
                .lineLimit(1)
            Spacer(minLength: 8)
            Button {
                restoreFavorite(name)
                removed = nil
            } label: {
                Text("撤销")
                    .font(Theme.bodyFont)
                    .foregroundStyle(Theme.champagne)
                    .frame(minHeight: 24)
                    .contentShape(Rectangle())
            }
            .buttonStyle(QuietButtonStyle())
        }
        .padding(.horizontal, 10)
        .frame(height: 32)
        .background(Theme.hoverFill, in: Capsule())
        .task(id: undoToken) {
            try? await Task.sleep(nanoseconds: 5_000_000_000)
            if !Task.isCancelled { removed = nil }
        }
    }

    // MARK: 底部

    private var footer: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 8) {
                nameField
                menu
            }
            if let draftError {
                Text(draftError).font(Theme.captionFont).foregroundStyle(Theme.live)
            }
            Button { page = .blocked } label: {
                HStack(spacing: 4) {
                    Text("屏蔽名单（\(blocked.count)）").font(Theme.captionFont)
                    Image(systemName: "chevron.right").font(.system(size: 9, weight: .semibold))
                }
                .foregroundStyle(Theme.textSecondary)
                .frame(minHeight: 24)
                .contentShape(Rectangle())
            }
            .buttonStyle(QuietButtonStyle())
        }
    }

    private var nameField: some View {
        TextField("输入用户名添加，回车确认", text: $draft)
            .textFieldStyle(.plain)
            .font(.system(size: 12))
            .padding(.horizontal, 12)
            .frame(height: Theme.tapHeight)
            .background(Theme.fieldFill, in: Capsule())
            .onSubmit {
                draftError = add(draft)
                if draftError == nil { draft = "" }
            }
            .onChange(of: draft) { _, _ in draftError = nil }
    }

    private var moreLabel: some View {
        Text("更多").font(Theme.bodyFont).foregroundStyle(Theme.champagne)
    }

    private var menu: some View {
        Menu {
            Button("导入收藏…", action: importFile)
            Button("导出收藏…", action: exportFile)
            if current != nil {
                Divider()
                Button("屏蔽当前房间", action: blockCurrent)
            }
        } label: {
            moreLabel
        }
        .menuStyle(.borderlessButton)
        .menuIndicator(.hidden)
        .fixedSize()
    }
}

extension FavoritesPanel {
    /// 空收藏夹的提示。macOS 有单击与 ⌘D，iOS 只说轻点。
    #if os(macOS)
    static let emptyHint = "在房间里单击心形，或按 ⌘D 收藏"
    #else
    static let emptyHint = "在房间里轻点心形收藏"
    #endif
}
