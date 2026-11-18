import SwiftUI

/// 搜索与筛选面板（两个平台共用）：macOS 在控制栏弹层里，iOS 在 sheet 里。
struct FilterPanel: View {
    @ObservedObject var model: StageModel
    /// macOS 弹层固定 300 宽；iOS sheet 传 nil，随屏幕宽度。
    var width: CGFloat? = 300

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            TextField("房间名或关键词，回车搜索全站", text: $model.searchText)
                .textFieldStyle(.roundedBorder)
                .onSubmit { model.submitSearch() }
            GenderBar(model: model)
            Picker("地区", selection: Binding(
                get: { model.region?.rawValue ?? "" },
                set: { model.chooseRegion(Region(rawValue: $0)) }
            )) {
                Text("全部地区").tag("")
                ForEach(Region.allCases, id: \.rawValue) { region in
                    Text(Copy.region(region)).tag(region.rawValue)
                }
            }
            .labelsHidden()
            Toggle("仅公开", isOn: Binding(get: { model.publicOnly }, set: { model.setPublicOnly($0) }))
            HStack {
                TextField("标签", text: $model.tagText)
                    .textFieldStyle(.roundedBorder)
                Button("应用") { model.applyTag() }
                    .buttonStyle(ChampagneButton())
            }
            TagHints(tags: model.tagHints) { tag in
                model.tagText = tag
                model.applyTag()
            }
        }
        .padding(16)
        .frame(width: width)
        .onChange(of: model.searchText) { _, _ in
            model.previewSearch()
        }
        .task { await model.loadHotTagsIfStale() }
    }
}

/// 标签输入下方的热门标签提示：点一下填入并应用。没有可提示的标签时不占位置。
private struct TagHints: View {
    var tags: [String]
    var choose: (String) -> Void

    var body: some View {
        Group {
            if !tags.isEmpty {
                VStack(alignment: .leading, spacing: 6) {
                    Text("热门标签")
                        .font(.system(size: 11))
                        .foregroundStyle(.secondary)
                    LazyVGrid(columns: [GridItem(.adaptive(minimum: 76), spacing: 6)], alignment: .leading, spacing: 6) {
                        ForEach(tags, id: \.self) { tag in
                            Button(tag) { choose(tag) }
                                .buttonStyle(.plain)
                                .font(.system(size: 11, weight: .medium))
                                .lineLimit(1)
                                .foregroundStyle(.white.opacity(0.82))
                                .frame(maxWidth: .infinity)
                                .padding(.vertical, 5)
                                .background(.white.opacity(0.07), in: Capsule())
                                .help("按「\(tag)」筛选")
                        }
                    }
                }
            }
        }
    }
}

private struct GenderBar: View {
    @ObservedObject var model: StageModel
    private let options: [(String, Gender?)] = [("全部", nil)] + Gender.allCases.map { (Copy.gender($0), $0) }

    var body: some View {
        HStack(spacing: 4) {
            ForEach(options, id: \.0) { option in
                Button(option.0) { model.chooseGender(option.1) }
                    .buttonStyle(.plain)
                    .font(.system(size: 12, weight: .semibold))
                    .lineLimit(1)
                    .foregroundStyle(model.gender == option.1 ? Color.black.opacity(0.86) : .white.opacity(0.78))
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 7)
                    .background(model.gender == option.1 ? Theme.champagne : Color.clear, in: Capsule())
            }
        }
        .padding(3)
        .background(.white.opacity(0.06), in: Capsule())
    }
}
