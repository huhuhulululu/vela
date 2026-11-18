import SwiftUI

struct StageRoot: View {
    @ObservedObject var model: StageModel
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        ZStack {
            WindowBridge(model: model)
                .allowsHitTesting(false)
            Theme.ink
            if model.ageConfirmed {
                stage
                // 实心控制栏盖在画面上方，画面不缩小不下移；收起时向上滑出，减弱动态效果时只淡出。
                ChromeView(model: model)
                    .offset(y: chromeOn || reduceMotion ? 0 : -Theme.barHeight)
                    .opacity(chromeOn ? 1 : 0)
                    .allowsHitTesting(chromeOn)
                    .frame(maxHeight: .infinity, alignment: .top)
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
                AgeGateView(enter: model.confirmAge, quit: model.quit)
            }
        }
        .ignoresSafeArea()
        .clipShape(RoundedRectangle(cornerRadius: Theme.radius, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: Theme.radius, style: .continuous)
                .strokeBorder(.white.opacity(0.16), lineWidth: 1)
                .allowsHitTesting(false)
        )
        // 减弱动态效果：顶栏的透明度变化也不做动画（位移之外，淡入淡出同样算动态）。
        .animation(reduceMotion ? nil : .easeOut(duration: 0.22), value: chromeOn)
        .animation(reduceMotion ? nil : .easeOut(duration: 0.2), value: model.toast)
        .animation(reduceMotion ? nil : .easeOut(duration: 0.2), value: model.toastUndoName)
        .animation(reduceMotion ? nil : .easeOut(duration: 0.2), value: model.favoriteSuggest)
        .preferredColorScheme(.dark)
        .onAppear {
            model.bootstrap()
            model.syncPlaybackFace()
            #if DEBUG
            SelfTest.startIfRequested(model)
            #endif
        }
        // @Published 在 willSet 发出：回调里一律用收到的新值，不回读 model.primary.player 上的同名属性。macOS 只有主路。
        .onReceive(model.primary.player.$failed) { model.notePlaybackFailed($0, on: model.primary) }
        .onReceive(model.primary.player.$readyUsername) { model.noteReady($0, on: model.primary) }
        .onReceive(model.primary.player.$playingUsername) { model.notePlaying($0, on: model.primary) }
        .sheet(isPresented: $model.showChallenge, onDismiss: { model.webSheetDismissed() }) {
            ChallengeSheet(model: model)
        }
    }

    private var chromeOn: Bool { model.chromeVisible || model.isInteracting }

    @ViewBuilder
    private var stage: some View {
        if model.feed.rooms.isEmpty {
            StatusPane(model: model)
        } else {
            PagerView(model: model)
        }
    }

    private func banner(_ text: String) -> some View {
        let showUndo = model.toastUndoName.flatMap { model.curation.canRestoreFavorite($0) ? $0 : nil } != nil
        return topAnchored { Group {
            if showUndo {
                HStack(spacing: Theme.gap) {
                    Text(text)
                        .font(.system(size: 12, weight: .medium))
                        .foregroundStyle(Theme.textPrimary)
                        .lineLimit(2)
                    Button("撤销") { model.undoToastFavorite() }
                        .buttonStyle(ChampagneButton())
                        .font(.system(size: 12, weight: .semibold))
                }
            } else {
                Text(text)
                    .font(.system(size: 12, weight: .medium))
                    .foregroundStyle(Theme.textPrimary)
                    .multilineTextAlignment(.center)
            }
        }
        .padding(.horizontal, Theme.inset)
        .padding(.vertical, 6)
        .frame(maxWidth: 480)
        .fixedSize(horizontal: false, vertical: true)
        .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
        .allowsHitTesting(showUndo)
        }
    }

    /// 顶部横幅的定位：上方留白与下方空白都明确不拦截点击，只有横幅本体可点（不会挡住控制栏）。
    private func topAnchored<Content: View>(@ViewBuilder _ content: () -> Content) -> some View {
        VStack(spacing: 0) {
            Color.clear
                .frame(height: Theme.barHeight + Theme.gap)
                .allowsHitTesting(false)
            content()
            Spacer(minLength: 0)
                .allowsHitTesting(false)
        }
        .transition(.opacity)
    }

    private func favoriteSuggestBanner(_ prompt: StageModel.FavoriteSuggestPrompt) -> some View {
        topAnchored { HStack(spacing: Theme.gap) {
            Text("常看 \(prompt.displayName)，收藏吗？")
                .font(.system(size: 12, weight: .medium))
                .foregroundStyle(Theme.textPrimary)
                .lineLimit(2)
            Button("收藏") { model.acceptFavoriteSuggest() }
                .buttonStyle(ChampagneButton())
                .font(.system(size: 12, weight: .semibold))
            Button { model.closeFavoriteSuggest() } label: {
                Image(systemName: "xmark")
                    .font(.system(size: 10, weight: .semibold))
                    .foregroundStyle(Theme.textSecondary)
                    .frame(minWidth: Theme.tapHeight, minHeight: Theme.tapHeight)
                    .contentShape(Rectangle())
            }
            .buttonStyle(QuietButtonStyle())
            .help("不再提示这位主播")
            .accessibilityLabel("关闭收藏提示")
        }
        .padding(.horizontal, Theme.inset)
        .padding(.vertical, 8)
        .frame(maxWidth: 440)
        .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
        .accessibilityElement(children: .combine)
        }
    }

    /// 首次提示：三件隐藏操作各一行，可关闭（关闭或第一次切房后不再出现，沿用 `hintSeen`）。
    private var hint: some View {
        HStack(alignment: .top, spacing: Theme.gap) {
            VStack(alignment: .leading, spacing: 4) {
                Text("拖动画面移动窗口")
                Text("滚轮或 ↑↓、J / K 换房间")
                Text("右键心形打开收藏夹")
            }
            .font(.system(size: 12, weight: .medium))
            .foregroundStyle(Theme.textPrimary)
            .lineLimit(1)
            Button { model.dismissHint() } label: {
                Image(systemName: "xmark")
                    .font(.system(size: 10, weight: .semibold))
                    .foregroundStyle(Theme.textSecondary)
                    .frame(minWidth: Theme.tapHeight, minHeight: Theme.tapHeight)
                    .contentShape(Rectangle())
            }
            .buttonStyle(QuietButtonStyle())
            .help("关闭提示")
            .accessibilityLabel("关闭操作提示")
        }
        .padding(.leading, Theme.inset + 4)
        .padding(.trailing, 4)
        .padding(.vertical, 8)
        .background(Theme.hintFill, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
        .padding(.bottom, 96)
        .frame(maxHeight: .infinity, alignment: .bottom)
    }
}

struct StatusPane: View {
    @ObservedObject var model: StageModel

    var body: some View {
        VStack(spacing: 16) {
            Text("VELA")
                .font(.system(size: 12, weight: .semibold))
                .tracking(6)
                .foregroundStyle(Theme.champagne)
            if model.loading {
                LoadingLine()
                Text("正在整理在线房间")
                    .font(.system(size: 13))
                    .foregroundStyle(Theme.textSecondary)
            } else {
                Text(model.emptyMessage)
                    .font(.system(size: 14, weight: .medium))
                    .foregroundStyle(Theme.textPrimary)
                    .multilineTextAlignment(.center)
                    .frame(maxWidth: 420)
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
