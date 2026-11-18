import SwiftUI

/// iOS 单窗主界面：持有 `StageModel`（由 App 注入），画面全屏，单击显示控制栏、2.4 秒后收起（§2）。
/// 上下滑切房走 `LanePager`（主路）；年龄未确认时显示手机版年龄确认页。
struct PhoneStageView: View {
    @ObservedObject var model: StageModel
    @Environment(\.displayScale) private var displayScale
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        ZStack {
            Theme.ink.ignoresSafeArea()
            if model.ageConfirmed {
                stage
                    .ignoresSafeArea()
                PhoneChrome(model: model, playback: model.focusPlaybackLane.playback, visible: chromeOn)
                    .opacity(chromeOn ? 1 : 0)
                    .allowsHitTesting(chromeOn)
                if model.hintVisible {
                    hint
                }
                if let toast = model.toast {
                    banner(toast)
                }
                if let suggest = model.favoriteSuggest {
                    favoriteSuggestBanner(suggest)
                }
            } else {
                AgeGateView(enter: model.confirmAge)
            }
        }
        .background(SceneWindowReader { model.sidecar.hold(window: $0) })
        // 每一路各订阅自己的播放事件；双窗的副路随 `dualWindow` 出现与消失。
        .background { ForEach(model.lanes, id: \.id) { LaneEvents(model: model, lane: $0) } }
        // 横屏只显示焦点路，非焦点路由模型释放（§4.2）。
        .onGeometryChange(for: Bool.self) { $0.size.width > $0.size.height } action: { model.setLandscape($0) }
        // 减弱动态效果：控制栏与横幅的淡入淡出也不做动画。
        .animation(reduceMotion ? nil : .easeOut(duration: 0.22), value: chromeOn)
        .animation(reduceMotion ? nil : .easeOut(duration: 0.2), value: model.toast)
        .animation(reduceMotion ? nil : .easeOut(duration: 0.2), value: model.toastUndoName)
        .animation(reduceMotion ? nil : .easeOut(duration: 0.2), value: model.favoriteSuggest)
        .preferredColorScheme(.dark)
        .onAppear {
            model.screenScale = Double(displayScale)
            model.bootstrap()
            model.syncPlaybackFace()
        }
        .onChange(of: displayScale) { _, scale in model.screenScale = Double(scale) }
        .sheet(isPresented: $model.showChallenge, onDismiss: { model.webSheetDismissed() }) {
            ChallengeSheet(model: model)
        }
    }

    private var chromeOn: Bool { model.chromeVisible || model.isInteracting }

    @ViewBuilder
    private var stage: some View {
        if model.feed.rooms.isEmpty {
            // 空列表时也要能单击唤出控制栏，否则收起后就切不了来源。
            PhoneStatusPane(model: model)
                .contentShape(Rectangle())
                .onTapGesture { model.noteActivity() }
        } else if model.dualWindow && !model.landscape {
            PhoneDualStage(model: model, chromeOn: chromeOn)
        } else {
            singleStage(model.focusPlaybackLane)
        }
    }

    /// 单窗（横屏的双窗也是它：只显示焦点路）。
    private func singleStage(_ lane: PlaybackLane) -> some View {
        GeometryReader { geo in
            LanePager(
                rooms: model.feed.rooms, index: model.index(of: lane),
                step: { delta in
                    let before = model.name(of: lane)
                    model.step(delta, on: lane)
                    return model.name(of: lane) != before
                },
                tap: { model.noteActivity() }
            ) { room, active in
                PhoneRoomPage(
                    room: room, active: active, model: model,
                    lane: lane, playback: lane.playback
                )
            }
            .onAppear { model.notePointHeight(geo.size.height) }
            .onChange(of: geo.size.height) { _, height in model.notePointHeight(height) }
        }
    }

    // MARK: 横幅与提示

    private func banner(_ text: String) -> some View {
        let showUndo = model.toastUndoName.flatMap { model.curation.canRestoreFavorite($0) ? $0 : nil } != nil
        return topAnchored {
            HStack(spacing: Theme.gap) {
                Text(text)
                    .font(.system(size: 13, weight: .medium))
                    .foregroundStyle(Theme.textPrimary)
                    .multilineTextAlignment(showUndo ? .leading : .center)
                    .lineLimit(2)
                if showUndo {
                    Button("撤销") { model.undoToastFavorite() }
                        .buttonStyle(ChampagneButton())
                }
            }
            .padding(.horizontal, Theme.inset + 2)
            .padding(.vertical, 8)
            .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
            .allowsHitTesting(showUndo)
        }
    }

    private func favoriteSuggestBanner(_ prompt: StageModel.FavoriteSuggestPrompt) -> some View {
        topAnchored {
            HStack(spacing: Theme.gap) {
                Text("常看 \(prompt.displayName)，收藏吗？")
                    .font(.system(size: 13, weight: .medium))
                    .foregroundStyle(Theme.textPrimary)
                    .lineLimit(2)
                Button("收藏") { model.acceptFavoriteSuggest() }
                    .buttonStyle(ChampagneButton())
                Button { model.closeFavoriteSuggest() } label: {
                    Image(systemName: "xmark")
                        .font(.system(size: 12, weight: .semibold))
                        .foregroundStyle(Theme.textSecondary)
                        .frame(width: 44, height: 44)
                        .contentShape(Rectangle())
                }
                .buttonStyle(QuietButtonStyle())
                .accessibilityLabel("关闭收藏提示")
            }
            .padding(.leading, Theme.inset + 2)
            .padding(.vertical, 4)
            .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
            .accessibilityElement(children: .combine)
        }
    }

    /// 横幅放在顶栏下方：上方留白与下方空白不拦截点击，只有横幅本体可点。
    private func topAnchored<Content: View>(@ViewBuilder _ content: () -> Content) -> some View {
        VStack(spacing: 0) {
            Color.clear
                .frame(height: 112)
                .allowsHitTesting(false)
            content()
                .padding(.horizontal, 16)
            Spacer(minLength: 0)
                .allowsHitTesting(false)
        }
        .transition(.opacity)
    }

    /// 首次提示：三件手势操作各一行，可关闭（关闭或第一次切房后不再出现，沿用 `hintSeen`）。
    private var hint: some View {
        HStack(alignment: .top, spacing: Theme.gap) {
            VStack(alignment: .leading, spacing: 4) {
                Text("上下滑动换房间")
                Text("单击画面显示控制栏")
                Text("长按心形打开收藏夹")
            }
            .font(.system(size: 13, weight: .medium))
            .foregroundStyle(Theme.textPrimary)
            Button { model.dismissHint() } label: {
                Image(systemName: "xmark")
                    .font(.system(size: 12, weight: .semibold))
                    .foregroundStyle(Theme.textSecondary)
                    .frame(width: 44, height: 44)
                    .contentShape(Rectangle())
            }
            .buttonStyle(QuietButtonStyle())
            .accessibilityLabel("关闭操作提示")
        }
        .padding(.leading, Theme.inset + 4)
        .padding(.vertical, 6)
        .background(Theme.hintFill, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
        .padding(.bottom, 120)
        .frame(maxHeight: .infinity, alignment: .bottom)
    }
}

/// 列表为空：加载中显示加载线，否则显示原因与重试、打开收藏夹。
struct PhoneStatusPane: View {
    @ObservedObject var model: StageModel

    var body: some View {
        VStack(spacing: 16) {
            Text("VELA")
                .font(.system(size: 12, weight: .semibold))
                .tracking(6)
                .foregroundStyle(Theme.champagne)
            if model.loading {
                PhoneLoadingLine()
                Text("正在整理在线房间")
                    .font(.system(size: 14))
                    .foregroundStyle(Theme.textSecondary)
            } else {
                Text(model.emptyMessage)
                    .font(.system(size: 15, weight: .medium))
                    .foregroundStyle(Theme.textPrimary)
                    .multilineTextAlignment(.center)
                    .fixedSize(horizontal: false, vertical: true)
                HStack(spacing: 12) {
                    if model.source == .favorites {
                        Button("打开收藏夹") { model.showCuration = true }
                            .buttonStyle(ChampagneButton())
                    }
                    if model.source != .favorites || !model.curation.favorites.isEmpty {
                        Button("重试") { Task { await model.retry() } }
                            .buttonStyle(ChampagneButton())
                    }
                }
            }
        }
        .padding(32)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}
