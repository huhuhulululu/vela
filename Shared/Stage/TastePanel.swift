import SwiftUI

/// 「我的口味」面板：只依赖显示数据与回调，不碰 StageModel，便于单独渲染自查。
struct TastePanel: View {
    enum Confirm { case reset, clear }

    var summary: TasteSummary
    /// 冷启动整理用的候选标签（当前在线房间里最常见、画像里还没有的）。
    var suggestions: [String]
    var paused: Bool
    var boost: (String) -> Void
    var veto: (String) -> Void
    var restore: (String) -> Void
    var remove: (String) -> Void
    var setPaused: (Bool) -> Void
    var resetProfile: () -> Void
    var clearAll: () -> Void

    /// 关掉滚动容器：只给离屏渲染自查用（ImageRenderer 画不出 ScrollView）。
    var scrolls = true

    @State private var confirm: Confirm?
    @State private var likedExpanded = false
    @State private var dislikedExpanded = false

    /// 每组默认显示的行数，超过时折叠。
    static let collapsedRows = 8

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            header
            if scrolls {
                ScrollView { content }
                    .frame(maxHeight: 430)
            } else {
                content
            }
            Divider().opacity(0.4)
            footer
        }
        .padding(Theme.inset + 4)
        .frame(width: 380)
    }

    private var content: some View {
        VStack(alignment: .leading, spacing: 16) {
            if summary.tagCount < Taste.coldStartTags, !suggestions.isEmpty { quickSort }
            HitRateCard(summary: summary)
            likedSection
            dislikedSection
            genderSection
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private var header: some View {
        VStack(alignment: .leading, spacing: 3) {
            Text("我的口味")
                .font(.system(size: 13, weight: .semibold))
            Text("偏好只保存在这台 Mac 上，不上传。")
                .font(.system(size: 11))
                .foregroundStyle(Theme.textSecondary)
                .lineLimit(1)
        }
    }

    // MARK: 冷启动

    private var quickSort: some View {
        VStack(alignment: .leading, spacing: 8) {
            SectionTitle("快速整理", note: "点一下标成喜欢或不想看")
            LazyVGrid(columns: [GridItem(.flexible(), spacing: 8), GridItem(.flexible(), spacing: 8)], spacing: 6) {
                ForEach(suggestions, id: \.self) { tag in
                    HStack(spacing: 6) {
                        Text(tag)
                            .font(.system(size: 12, weight: .medium))
                            .lineLimit(1)
                        Spacer(minLength: 2)
                        iconButton("heart", help: "喜欢「\(tag)」", color: Theme.champagne) { boost(Taste.tagKey(tag)) }
                        iconButton("nosign", help: "不想看「\(tag)」", color: Theme.live) { veto(Taste.tagKey(tag)) }
                    }
                    .padding(.horizontal, 10)
                    .frame(height: 30)
                    .background(.white.opacity(0.07), in: Capsule())
                }
            }
        }
    }

    private func iconButton(_ symbol: String, help: String, color: Color, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: symbol)
                .font(.system(size: 11, weight: .semibold))
                .foregroundStyle(color)
                .frame(width: 22, height: 22)
                .contentShape(Rectangle())
        }
        .buttonStyle(QuietButtonStyle())
        .help(help)
        .accessibilityLabel(help)
    }

    // MARK: 列表

    private var likedSection: some View {
        VStack(alignment: .leading, spacing: 6) {
            SectionTitle("喜欢的标签")
            if summary.liked.isEmpty {
                emptyNote("还没有明确的偏好。看一会儿，或先用上面的整理。")
            }
            ForEach(Self.visible(summary.liked, expanded: likedExpanded)) { row in
                TasteRowView(name: row.name, mean: row.mean, detail: Self.detail(row), actions: [
                    ("加强", Theme.champagne, { boost(row.key) }),
                    ("不想看", Theme.live, { veto(row.key) }),
                    ("移除", Theme.textSecondary, { remove(row.key) })
                ])
            }
            expander(total: summary.liked.count, expanded: $likedExpanded)
        }
    }

    @ViewBuilder
    private var dislikedSection: some View {
        VStack(alignment: .leading, spacing: 6) {
            SectionTitle("不想看的标签")
            if summary.disliked.isEmpty { emptyNote("没有。标成不想看的标签会被过滤。") }
            ForEach(Self.visible(summary.disliked, expanded: dislikedExpanded)) { row in
                TasteRowView(name: row.name, mean: row.mean, detail: row.vetoed ? "已过滤" : "常被划走", actions: [
                    ("恢复", Theme.champagne, { restore(row.key) }),
                    ("移除", Theme.textSecondary, { remove(row.key) })
                ])
            }
            expander(total: summary.disliked.count, expanded: $dislikedExpanded)
        }
    }

    private var genderSection: some View {
        VStack(alignment: .leading, spacing: 6) {
            SectionTitle("性别偏好")
            if summary.genders.isEmpty { emptyNote("还没有性别倾向。") }
            ForEach(summary.genders) { row in
                TasteRowView(
                    name: Gender(rawValue: row.name).map { Copy.gender($0) } ?? row.name,
                    mean: row.mean, detail: row.vetoed ? "已过滤" : Self.detail(row), actions: row.vetoed ? [
                        ("恢复", Theme.champagne, { restore(row.key) }),
                        ("移除", Theme.textSecondary, { remove(row.key) })
                    ] : [
                        ("加强", Theme.champagne, { boost(row.key) }),
                        ("不想看", Theme.live, { veto(row.key) }),
                        ("移除", Theme.textSecondary, { remove(row.key) })
                    ])
            }
        }
    }

    static func visible(_ rows: [TasteRow], expanded: Bool) -> [TasteRow] {
        expanded ? rows : Array(rows.prefix(collapsedRows))
    }

    /// 超过默认行数时显示「展开全部（N）」，展开后显示「收起」。
    @ViewBuilder
    private func expander(total: Int, expanded: Binding<Bool>) -> some View {
        if total > Self.collapsedRows {
            textButton(expanded.wrappedValue ? "收起" : "展开全部（\(total)）", Theme.champagne) {
                expanded.wrappedValue.toggle()
            }
        }
    }

    private func emptyNote(_ text: String) -> some View {
        Text(text)
            .font(.system(size: 11))
            .foregroundStyle(Theme.textSecondary)
            .lineLimit(1)
    }

    static func detail(_ row: TasteRow) -> String {
        row.hits > 0 ? "命中 \(row.hits) 次" : (row.mean < 0.5 ? "常被划走" : "手动加强")
    }

    // MARK: 页脚

    @ViewBuilder
    private var footer: some View {
        if let confirm {
            confirmRow(confirm)
        } else {
            VStack(alignment: .leading, spacing: 8) {
                Toggle("暂停学习", isOn: Binding(get: { paused }, set: setPaused))
                    .font(.system(size: 12, weight: .medium))
                HStack(spacing: 16) {
                    textButton("重置画像", Theme.champagne) { confirm = .reset }
                    textButton("清除全部偏好数据", Theme.live) { confirm = .clear }
                    Spacer(minLength: 0)
                }
            }
        }
    }

    private func confirmRow(_ kind: Confirm) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(kind == .reset ? "重置后标签统计清零，最近看过保留。" : "将清除口味（标签统计、停留记录、最近看过）和观看记录；收藏与屏蔽保留。")
                .font(.system(size: 12))
                .foregroundStyle(Theme.textPrimary)
                .lineLimit(2)
                .fixedSize(horizontal: false, vertical: true)
            HStack(spacing: 16) {
                textButton("确定", Theme.live) {
                    if kind == .reset { resetProfile() } else { clearAll() }
                    confirm = nil
                }
                textButton("取消", Theme.textSecondary) { confirm = nil }
                Spacer(minLength: 0)
            }
        }
    }

    private func textButton(_ title: String, _ color: Color, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(title)
                .font(.system(size: 12, weight: .medium))
                .lineLimit(1)
                .fixedSize()
                .foregroundStyle(color)
                .frame(minHeight: Theme.tapHeight)
                .contentShape(Rectangle())
        }
        .buttonStyle(QuietButtonStyle())
    }
}
