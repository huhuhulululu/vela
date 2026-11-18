import SwiftUI
import UniformTypeIdentifiers

/// 单窗控制栏（按已认可的预览页）：顶部两行（来源切换；右侧搜索、口味、静音），
/// 底部房间名、LIVE 红点与标签，右下心形。整体随单击显示、2.4 秒后收起。
/// 不提供「铺满」开关（裁决 5）。面板一律复用 Shared，以 sheet 呈现。
struct PhoneChrome: View {
    @ObservedObject var model: StageModel
    @ObservedObject var playback: PlaybackFace
    /// 控制栏此刻看得见。收起时红点停止脉冲，免得看不见的动画空转。
    var visible: Bool

    var body: some View {
        ZStack {
            scrims
            VStack(spacing: 0) {
                top
                Spacer(minLength: 0)
                if !paneBars { bottom }
            }
        }
        // 收藏夹由单窗的心形与双窗窗格里的心形共用同一个 sheet。
        .sheet(isPresented: $model.showCuration, onDismiss: { model.noteActivity() }) {
            PhoneFavoritesSheet(model: model)
        }
    }

    /// 竖屏双窗：每个窗格自己带房间信息与心形，这里不再画底栏。
    private var paneBars: Bool { model.dualWindow && !model.landscape }

    private var scrims: some View {
        VStack(spacing: 0) {
            LinearGradient(colors: [.black.opacity(0.6), .clear], startPoint: .top, endPoint: .bottom)
                .frame(height: 200)
            Spacer(minLength: 0)
            if !paneBars {
                LinearGradient(colors: [.clear, .black.opacity(0.72)], startPoint: .top, endPoint: .bottom)
                    .frame(height: 260)
            }
        }
        .ignoresSafeArea()
        .allowsHitTesting(false)
    }

    private var top: some View {
        VStack(alignment: .leading, spacing: 10) {
            PhoneSourceTabs(selected: model.source) { model.select($0) }
            HStack(spacing: Theme.gap) {
                Spacer(minLength: 0)
                PhonePill(title: "搜索", active: model.showFilters) { model.showFilters = true }
                    .sheet(isPresented: $model.showFilters, onDismiss: { model.noteActivity() }) {
                        ScrollView { FilterPanel(model: model, width: nil) }
                            .phoneSheet()
                    }
                PhonePill(title: "口味", active: model.taste.paused) { model.showTaste = true }
                    .sheet(isPresented: $model.showTaste, onDismiss: { model.noteActivity() }) {
                        TasteView(model: model, width: nil)
                            .frame(maxWidth: .infinity)
                            .phoneSheet()
                    }
                if let pip = model.platform.pipCoordinator {
                    PhonePiPPill(pip: pip) { model.platform.setPictureInPictureAutoEnter($0, stage: model) }
                }
                if model.platform.supportsDual {
                    PhoneLanePicker(model: model)
                }
                PhonePill(title: model.muted ? "静音" : "出声", active: !model.muted) { model.toggleMute() }
                    .accessibilityLabel(model.muted ? "已静音，点按出声" : "正在出声，点按静音")
            }
        }
        .padding(.horizontal, 16)
        .padding(.top, 8)
    }

    private var bottom: some View {
        HStack(alignment: .bottom, spacing: Theme.gap) {
            if let room = model.focusRoom {
                PhoneCaption(room: room, pulse: visible && playback.playingUsername == room.username)
            }
            Spacer(minLength: 0)
            PhoneHeart(
                isFavorite: model.currentIsFavorite, canToggle: model.focusRoom != nil,
                toggle: { model.toggleFavorite(model.target(model.focusLane)) }, openList: { model.showCuration = true }
            )
        }
        .padding(.horizontal, 16)
        .padding(.bottom, 12)
    }
}

/// 路数选择 1 / 2 / 3（竖屏上下等高叠放，横屏一律单窗）。放不下三格的屏幕第三路置灰并说明原因（修订 v2 M12）。
struct PhoneLanePicker: View {
    @ObservedObject var model: StageModel

    var body: some View {
        Menu {
            ForEach(1...LaneID.maxCount, id: \.self) { count in
                Button {
                    model.setLaneCount(count)
                } label: {
                    if count == model.laneCount {
                        Label(title(count), systemImage: "checkmark")
                    } else {
                        Text(title(count))
                    }
                }
                .disabled(count > model.maxLaneCount || model.laneCountLocked)
            }
            if model.laneCountLocked {
                Text("画中画进行中，结束后可调整路数")
            }
        } label: {
            Text("\(model.laneCount) 路")
                .font(.system(size: 13, weight: model.dualWindow ? .semibold : .medium))
                .foregroundStyle(model.dualWindow ? Color.black.opacity(0.86) : .white)
                .lineLimit(1)
                .padding(.horizontal, 12)
                .padding(.vertical, 7)
                .background(model.dualWindow ? Theme.champagne : Color(white: 0.08).opacity(0.62), in: Capsule())
                .overlay(Capsule().strokeBorder(model.dualWindow ? Theme.champagne : .white.opacity(0.22), lineWidth: 1))
                .frame(minHeight: 44)
                .contentShape(Capsule())
        }
        .accessibilityLabel("路数，当前 \(model.laneCount) 路")
    }

    private func title(_ count: Int) -> String {
        if count == LaneID.maxCount, !model.threeLanesFit { return "\(count) 路（屏幕高度不够）" }
        return "\(count) 路"
    }
}

/// 来源切换：四个文字标签，选中项白色加粗、下方香槟色细线。
struct PhoneSourceTabs: View {
    var selected: FeedSource
    var select: (FeedSource) -> Void

    var body: some View {
        HStack(spacing: 14) {
            ForEach(FeedSource.allCases, id: \.self) { source in
                let on = source == selected
                Button { select(source) } label: {
                    Text(Copy.source(source))
                        .font(.system(size: 15, weight: on ? .semibold : .regular))
                        .foregroundStyle(on ? Color.white : Color.white.opacity(0.78))
                        .lineLimit(1)
                        .fixedSize()
                        .padding(.vertical, 6)
                        .overlay(alignment: .bottom) {
                            Rectangle()
                                .fill(on ? Theme.champagne : .clear)
                                .frame(height: 2)
                        }
                        .frame(minHeight: 44)
                        .contentShape(Rectangle())
                }
                .buttonStyle(QuietButtonStyle())
                .accessibilityLabel(Copy.source(source))
                .accessibilityAddTraits(on ? .isSelected : [])
            }
        }
    }
}

/// 画中画按钮（修订 A2 的 7a）：点按进入或退出；长按菜单开关「回桌面自动进入画中画」。
/// 不支持画中画的设备不创建控制器，按钮不出现。
struct PhonePiPPill: View {
    @ObservedObject var pip: PictureInPictureCoordinator
    var setAutoEnter: (Bool) -> Void

    var body: some View {
        PhonePill(title: "画中画", active: pip.active) { pip.toggle() }
            .opacity(pip.possible || pip.active ? 1 : 0.5)
            .contextMenu {
                Toggle("回桌面自动进入画中画", isOn: Binding(get: { pip.autoEnter }, set: setAutoEnter))
            }
            .accessibilityLabel(pip.active ? "正在画中画，点按退出" : "画中画")
    }
}

/// 顶栏右侧的文字胶囊按钮；选中态为香槟色实底。
struct PhonePill: View {
    var title: String
    var active = false
    var action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(title)
                .font(.system(size: 13, weight: active ? .semibold : .medium))
                .foregroundStyle(active ? Color.black.opacity(0.86) : .white)
                .lineLimit(1)
                .padding(.horizontal, 12)
                .padding(.vertical, 7)
                .background(active ? Theme.champagne : Color(white: 0.08).opacity(0.62), in: Capsule())
                .overlay(Capsule().strokeBorder(active ? Theme.champagne : .white.opacity(0.22), lineWidth: 1))
                .frame(minHeight: 44)
                .contentShape(Capsule())
        }
        .buttonStyle(QuietButtonStyle())
    }
}

/// 底部房间信息：房间名，下一行 LIVE 红点与最多三个标签。纯展示，不接收点击。
struct PhoneCaption: View {
    var room: Room
    /// 这一间正在播：红点脉冲；否则静止。
    var pulse: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(room.displayName.isEmpty ? room.username : room.displayName)
                .font(.system(size: 17, weight: .semibold))
                .foregroundStyle(.white)
                .lineLimit(1)
            HStack(spacing: Theme.gap) {
                HStack(spacing: 5) {
                    PulseDot(animated: pulse)
                        .id(pulse)
                    Text("LIVE")
                        .font(.system(size: 12, weight: .bold))
                        .tracking(0.6)
                }
                ForEach(Array(room.tags.prefix(3)), id: \.self) { tag in
                    Text(tag)
                        .font(.system(size: 12, weight: .medium))
                        .lineLimit(1)
                        .padding(.horizontal, 7)
                        .padding(.vertical, 2)
                        .background(.white.opacity(0.16), in: Capsule())
                }
            }
            .foregroundStyle(.white.opacity(0.88))
        }
        .accessibilityElement(children: .combine)
        .allowsHitTesting(false)
    }
}

/// 右下心形：单击收藏或取消收藏当前房间（`RoomTarget` 版），长按打开收藏夹。
/// 已收藏为收藏红实心并带红色描边，未收藏为白色描边。
struct PhoneHeart: View {
    var isFavorite: Bool
    /// 没有当前房间时为 false：图标变暗，单击不做事；长按仍可打开收藏夹。
    var canToggle: Bool
    var toggle: () -> Void
    var openList: () -> Void

    var body: some View {
        Image(systemName: isFavorite ? "heart.fill" : "heart")
            .font(.system(size: 20, weight: .semibold))
            .foregroundStyle(isFavorite ? Theme.favorite : .white)
            .frame(width: 44, height: 44)
            .background(Color(white: 0.08).opacity(0.55), in: Circle())
            .overlay(Circle().strokeBorder(isFavorite ? Theme.favorite : .white.opacity(0.25), lineWidth: 1))
            .opacity(canToggle ? 1 : 0.45)
            .contentShape(Circle())
            .onTapGesture { if canToggle { toggle() } }
            .onLongPressGesture(minimumDuration: 0.45, perform: openList)
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(isFavorite ? "取消收藏当前房间" : "收藏当前房间")
            .accessibilityHint("长按打开收藏夹")
            .accessibilityAddTraits(.isButton)
            .accessibilityAction { if canToggle { toggle() } }
            .accessibilityAction(named: "打开收藏夹", openList)
    }
}

/// 收藏夹 sheet：复用共享的 `CurationPanel`，导出导入走 `.fileExporter` / `.fileImporter`。
struct PhoneFavoritesSheet: View {
    @ObservedObject var model: StageModel
    @State private var exportDocument: FavoritesDocument?
    @State private var exporting = false
    @State private var importing = false

    var body: some View {
        ScrollView {
            CurationPanel(model: model, exportFile: beginExport, importFile: { importing = true })
                .frame(maxWidth: .infinity)
        }
        .phoneSheet()
        .fileExporter(
            isPresented: $exporting, document: exportDocument, contentType: .json,
            defaultFilename: "vela-favorites"
        ) { result in
            exportDocument = nil
            switch result {
            case .success:
                model.showToast("已导出 \(model.curation.favorites.count) 个收藏")
            case .failure(let error):
                if !Self.cancelled(error) { model.showToast("导出失败") }
            }
        }
        .fileImporter(isPresented: $importing, allowedContentTypes: [.json]) { result in
            guard case .success(let url) = result else { return }
            // 文件选择器给的是沙盒外的地址：读之前先取得访问权，读完立即交还。
            let granted = url.startAccessingSecurityScopedResource()
            defer { if granted { url.stopAccessingSecurityScopedResource() } }
            model.importFavorites(url)
        }
    }

    private func beginExport() {
        do {
            exportDocument = FavoritesDocument(data: try model.exportFavoritesData())
            exporting = true
        } catch {
            model.showToast("导出失败")
        }
    }

    private static func cancelled(_ error: Error) -> Bool {
        (error as? CocoaError)?.code == .userCancelled
    }
}

/// 收藏导出文件：内容就是 `exportFavoritesData()` 的 JSON。
struct FavoritesDocument: FileDocument {
    static var readableContentTypes: [UTType] { [.json] }
    var data: Data

    init(data: Data) {
        self.data = data
    }

    init(configuration: ReadConfiguration) throws {
        guard let data = configuration.file.regularFileContents else { throw CocoaError(.fileReadCorruptFile) }
        self.data = data
    }

    func fileWrapper(configuration: WriteConfiguration) throws -> FileWrapper {
        FileWrapper(regularFileWithContents: data)
    }
}

extension View {
    /// 手机 sheet 的统一外观：半屏起步可拉到全屏，深色，带拖动条。
    func phoneSheet() -> some View {
        presentationDetents([.medium, .large])
            .presentationDragIndicator(.visible)
            .preferredColorScheme(.dark)
    }
}
