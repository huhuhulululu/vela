import AVFoundation
import AppKit
import QuartzCore
import SwiftUI

struct RoomCanvas: View {
    var room: Room
    var active: Bool
    @ObservedObject var model: StageModel
    /// 不观察 `PlayerController`：它在播放中会反复发布相同的就绪 / 失败状态，整页画面会跟着失效。
    @ObservedObject var playback: PlaybackFace
    @ObservedObject private var info = RoomInfoPrefs.shared
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        ZStack(alignment: .bottomLeading) {
            SwapBackdrop(room: room)
            if active {
                PlayerLayerView(player: model.player.player, fill: model.effectiveFill)
                    .opacity(videoReady ? 1 : 0)
                    .animation(reduceMotion ? nil : .easeOut(duration: Theme.videoFade), value: videoReady)
            }
            Group {
                LinearGradient(
                    colors: [.clear, .black.opacity(0.18), .black.opacity(0.78)],
                    startPoint: .top,
                    endPoint: .bottom
                )
                .frame(height: 280)
                .frame(maxHeight: .infinity, alignment: .bottom)
                RoomCaption(room: room, pulse: pulseLive, reason: model.reason(for: room))
            }
            .opacity(infoVisible ? 1 : 0)
            .animation(reduceMotion ? nil : .easeOut(duration: Theme.infoFade), value: infoVisible)
            .allowsHitTesting(false)
            if active && model.manuallyPaused {
                pauseMark
            } else if active, let note = model.playbackNote, !videoReady || playback.failed {
                noteMark(note)
            }
            if active && !videoReady && model.playbackNote == nil && !model.manuallyPaused {
                LoadingLine()
                    .frame(maxHeight: .infinity, alignment: .top)
                    .padding(.top, 8)
            }
        }
        .clipped()
    }

    private var chromeOn: Bool { model.chromeVisible || model.isInteracting }

    /// 当前页的房间信息跟控制栏一起显隐（「始终显示」除外）；邻页照旧带信息。
    private var infoVisible: Bool {
        InfoVisibility.visible(isCurrent: active, alwaysShow: info.alwaysShow, chromeOn: chromeOn)
    }

    private var videoReady: Bool { active && playback.readyUsername == room.username }

    /// 只有当前页、这一间正在播、信息块看得见时才做脉冲。停播、信息被隐藏、App 隐藏后停播都要停。
    /// `.id` 用这个条件：条件变假时拆掉旧视图，避免动画值没变、repeatForever 还在跑。
    private var pulseLive: Bool {
        active && playback.playingUsername == room.username && infoVisible
    }

    private var pauseMark: some View {
        Image(systemName: "pause.fill")
            .font(.system(size: 22, weight: .semibold))
            .foregroundStyle(.white)
            .frame(width: 64, height: 64)
            .background(.ultraThinMaterial, in: Circle())
            .frame(maxWidth: .infinity, maxHeight: .infinity)
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
                .frame(maxWidth: 440)
                .fixedSize(horizontal: false, vertical: true)
                // 固定深色底垫在材质下面：亮画面上材质会发灰，白字对比度不能只靠它。
                .background {
                    ZStack {
                        Rectangle().fill(.ultraThinMaterial)
                        Rectangle().fill(Theme.hintFill)
                    }
                    .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
                }
            Button("重试") { model.retryPlayback(model.target()) }
                .buttonStyle(ChampagneButton())
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

struct LoadingLine: View {
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

struct PlayerLayerView: NSViewRepresentable {
    var player: AVPlayer
    var fill: Bool

    func makeNSView(context: Context) -> PlayerNSView {
        let view = PlayerNSView()
        view.playerLayer.player = player
        view.playerLayer.videoGravity = fill ? .resizeAspectFill : .resizeAspect
        return view
    }

    func updateNSView(_ view: PlayerNSView, context: Context) {
        // 父视图刷新也会进来。播放器实例和重力没变就不要写 layer，同值写入会把 AVPlayerLayer 标脏。
        if view.playerLayer.player !== player {
            view.playerLayer.player = player
        }
        let gravity: AVLayerVideoGravity = fill ? .resizeAspectFill : .resizeAspect
        if view.playerLayer.videoGravity != gravity {
            view.playerLayer.videoGravity = gravity
        }
    }
}

final class PlayerNSView: NSView {
    let playerLayer = AVPlayerLayer()

    override init(frame frameRect: NSRect) {
        super.init(frame: frameRect)
        wantsLayer = true
        layer?.backgroundColor = NSColor.clear.cgColor
        playerLayer.backgroundColor = NSColor.clear.cgColor
        playerLayer.isOpaque = false
        playerLayer.drawsAsynchronously = true
        playerLayer.videoGravity = .resizeAspect
        layer?.addSublayer(playerLayer)
    }

    required init?(coder: NSCoder) { fatalError() }

    override func layout() {
        super.layout()
        // 宿主视图在播放中仍可能反复 layout。frame 没变就不要再写，避免隐式动画把图层再标脏。
        guard playerLayer.frame != bounds else { return }
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        playerLayer.frame = bounds
        CATransaction.commit()
    }
}