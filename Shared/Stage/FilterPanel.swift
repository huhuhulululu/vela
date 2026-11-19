import SwiftUI

/// 搜索与筛选面板（两个平台共用）：macOS 在控制栏弹层里，iOS 在 sheet 里。
struct FilterPanel: View {
    @ObservedObject var model: StageModel
    /// macOS 弹层固定 300 宽；iOS sheet 传 nil，随屏幕宽度。
    var width: CGFloat? = 300

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            ChoiceBar(options: siteOptions, selected: model.siteMode, choose: { model.chooseSite($0) })
            TextField("房间名或关键词，回车搜索全站", text: $model.searchText)
                .textFieldStyle(.roundedBorder)
                .onSubmit { model.submitSearch() }
            ChoiceBar(options: genderOptions, selected: model.gender, choose: { model.chooseGender($0) })
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

/// 胶囊分段选择（站点、性别共用）。
private struct ChoiceBar<Value: Equatable>: View {
    var options: [(String, Value)]
    var selected: Value
    var choose: (Value) -> Void

    var body: some View {
        HStack(spacing: 4) {
            ForEach(options, id: \.0) { option in
                Button(option.0) { choose(option.1) }
                    .buttonStyle(.plain)
                    .font(.system(size: 12, weight: .semibold))
                    .lineLimit(1)
                    .foregroundStyle(selected == option.1 ? Color.black.opacity(0.86) : .white.opacity(0.78))
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 7)
                    .background(selected == option.1 ? Theme.champagne : Color.clear, in: Capsule())
            }
        }
        .padding(3)
        .background(.white.opacity(0.06), in: Capsule())
    }
}

/// 站点切换：Chaturbate / Cam4 / 混合，选择记住（切换等同切来源，重新加载目录）。
private let siteOptions: [(String, SiteMode)] = [("Chaturbate", .chaturbate), ("Cam4", .cam4), ("混合", .mixed)]
private let genderOptions: [(String, Gender?)] = [("全部", nil)] + Gender.allCases.map { (Copy.gender($0), $0) }
