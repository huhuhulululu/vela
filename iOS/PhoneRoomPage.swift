import SwiftUI

/// 分页里的一页：模糊缩略图垫底，当前页叠上播放层（就绪后淡入），以及暂停、失败、加载三种提示。
/// 邻页只有垫底，不挂播放层。垫底放在 `.background` 里不参与布局，播放层严格等于窗格（裁决 5）。
struct PhoneRoomPage: View {
    var room: Room
    var active: Bool
    @ObservedObject var model: StageModel
    @ObservedObject var lane: PlaybackLane
    /// 不观察 `PlayerController`：它在播放中会反复发布相同的就绪 / 失败状态，整页画面会跟着失效。
    @ObservedObject var playback: PlaybackFace
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    /// 本页（窗格）在窗口里的位置与大小，`display-check` 拿它作比较基准。引用类型：拖动翻页只改值，不让整页重算。
    @State private var pane = PaneGeometry()

    var body: some View {
        ZStack {
            if active {
                PlayerLayerView(
                    player: lane.player.player, laneLabel: lane.player.laneLabel, pane: pane, pip: pipController
                ) { size in
                    lane.displaySize = size
                }
                .opacity(videoReady ? 1 : 0)
                .animation(reduceMotion ? nil : .easeOut(duration: Theme.videoFade), value: videoReady)
            }
            if active && lane.manuallyPaused {
                pauseMark
            } else if active, let note = lane.playbackNote, !videoReady || playback.failed {
                noteMark(note)
            }
            // 只在允许播放时显示：后台或闸门关闭时播放项已释放，加载线的无限动画只会空转耗电。
            if active && !videoReady && lane.playbackNote == nil && !lane.manuallyPaused && model.playbackGateOpen {
                PhoneLoadingLine()
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background { SwapBackdrop(room: room) }
        .clipped()
        .onGeometryChange(for: CGRect.self) { $0.frame(in: .global) } action: { pane.frame = $0 }
    }

    /// 只有焦点路挂画中画（单窗恒为主路）。
    private var pipController: PictureInPictureCoordinator? {
        lane.id == model.focusLane ? model.platform.pipCoordinator : nil
    }

    private var videoReady: Bool { active && playback.readyUsername == room.username }

    private var pauseMark: some View {
        Image(systemName: "pause.fill")
            .font(.system(size: 22, weight: .semibold))
            .foregroundStyle(.white)
            .frame(width: 64, height: 64)
            .background(.ultraThinMaterial, in: Circle())
            .allowsHitTesting(false)
    }

    private func noteMark(_ note: String) -> some View {
        VStack(spacing: 14) {
            Text(note)
                .font(.system(size: 16, weight: .semibold))
                .foregroundStyle(.white)
                .multilineTextAlignment(.center)
                .padding(.horizontal, 16)
                .padding(.vertical, 10)
                .fixedSize(horizontal: false, vertical: true)
                // 固定深色底垫在材质下面：亮画面上材质会发灰，白字对比度不能只靠它。
                .background {
                    ZStack {
                        Rectangle().fill(.ultraThinMaterial)
                        Rectangle().fill(Theme.hintFill)
                    }
                    .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
                }
            Button("重试") { model.retryPlayback(model.target(lane.id)) }
                .buttonStyle(ChampagneButton())
        }
        .padding(.horizontal, 24)
    }
}

/// 加载线：香槟色短线来回伸缩；减弱动态效果时静止。
struct PhoneLoadingLine: View {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var wide = false

    var body: some View {
        Capsule()
            .fill(Theme.champagne)
            .frame(width: wide ? 92 : 28, height: 2)
            .animation(reduceMotion ? nil : .easeInOut(duration: 0.85).repeatForever(autoreverses: true), value: wide)
            .onAppear { wide = true }
            .allowsHitTesting(false)
    }
}
