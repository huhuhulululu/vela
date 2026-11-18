import AVFoundation
import os
import SwiftUI
import UIKit

/// iOS 播放层：固定 `.resizeAspect`，画面保持原始比例不裁切（裁决 5）；没有 `fill` 参数。
/// 图层与视图背景透明，留边区露出下面的 `SwapBackdrop`。
/// 画面实际显示的尺寸（`videoRect`）回报给 `onDisplaySize`（写进 `lane.displaySize`），并打 `display-check` 日志（§4.5）。
struct PlayerLayerView: UIViewRepresentable {
    var player: AVPlayer
    /// 日志里的路标识，与 `first-frame lane=<id>` 一致。
    var laneLabel: String
    var onDisplaySize: @MainActor (CGSize) -> Void

    func makeUIView(context: Context) -> PlayerUIView {
        let view = PlayerUIView()
        view.laneLabel = laneLabel
        view.onDisplaySize = onDisplaySize
        view.bind(player)
        return view
    }

    func updateUIView(_ view: PlayerUIView, context: Context) {
        // 父视图刷新也会进来。播放器实例没变就不要写 layer。
        view.laneLabel = laneLabel
        view.onDisplaySize = onDisplaySize
        if view.playerLayer.player !== player {
            view.bind(player)
        }
    }
}

final class PlayerUIView: UIView {
    override static var layerClass: AnyClass { AVPlayerLayer.self }

    private static let log = Logger(subsystem: "com.vela.stage", category: "display")
    private static let signposter = OSSignposter(subsystem: "com.vela.stage", category: "display")

    var playerLayer: AVPlayerLayer { layer as! AVPlayerLayer }
    var laneLabel = "primary"
    var onDisplaySize: (@MainActor (CGSize) -> Void)?

    private var lastReported: CGSize = .zero
    /// 上一条 `display-check`：同一播放项、尺寸没变就不重复打。换播放项时清空，新房间首次就绪会再打一条。
    private var lastLogged: String?
    private var playerObservation: NSKeyValueObservation?
    private var itemObservation: NSKeyValueObservation?
    private var readyObservation: NSKeyValueObservation?

    override init(frame: CGRect) {
        super.init(frame: frame)
        isUserInteractionEnabled = false
        backgroundColor = .clear
        playerLayer.backgroundColor = UIColor.clear.cgColor
        playerLayer.isOpaque = false
        playerLayer.videoGravity = .resizeAspect
        readyObservation = playerLayer.observe(\.isReadyForDisplay, options: [.new]) { [weak self] _, _ in
            DispatchQueue.main.async { self?.refreshDisplay() }
        }
    }

    required init?(coder: NSCoder) { fatalError() }

    func bind(_ player: AVPlayer) {
        playerLayer.player = player
        playerObservation = player.observe(\.currentItem, options: [.initial, .new]) { [weak self] _, _ in
            DispatchQueue.main.async { self?.observeCurrentItem() }
        }
    }

    private func observeCurrentItem() {
        lastLogged = nil
        itemObservation = playerLayer.player?.currentItem?.observe(\.presentationSize, options: [.new]) { [weak self] _, _ in
            DispatchQueue.main.async { self?.refreshDisplay() }
        }
        refreshDisplay()
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        refreshDisplay()
    }

    /// 布局、播放项、画面尺寸、就绪状态任一变化时重算：先回报显示尺寸，画面就绪后再打比例校验日志。
    private func refreshDisplay() {
        let bounds = playerLayer.bounds.size
        guard bounds.width > 0, bounds.height > 0 else { return }
        let rect = playerLayer.videoRect
        let size = DisplayCheck.displaySize(videoRect: rect, bounds: bounds)
        if size != lastReported {
            lastReported = size
            onDisplaySize?(size)
        }
        guard playerLayer.isReadyForDisplay, rect.width > 0, rect.height > 0,
              let presentation = playerLayer.player?.currentItem?.presentationSize,
              presentation.width > 0, presentation.height > 0 else { return }
        let line = DisplayCheck.line(
            lane: laneLabel, gravity: DisplayCheck.gravityName(playerLayer.videoGravity),
            presentation: presentation, bounds: bounds, videoRect: rect
        )
        guard line != lastLogged else { return }
        lastLogged = line
        // Release 也保留：模拟器验收按这一行判比例（S13）。
        Self.log.notice("\(line, privacy: .public)")
        Self.signposter.emitEvent("display-check", "\(line, privacy: .public)")
    }
}

/// `display-check` 日志的拼装与显示尺寸估算（纯函数，VelaPhoneTests 覆盖）。
enum DisplayCheck {
    /// 重力的短名。iOS 只会出现 `resizeAspect`；其余值照实写出，验收时一眼能看出不对。
    static func gravityName(_ gravity: AVLayerVideoGravity) -> String {
        switch gravity {
        case .resizeAspect: return "resizeAspect"
        case .resize: return "resize"
        default: return "fill"
        }
    }

    /// `display-check lane=<id> gravity=<值> presentation=WxH bounds=WxH videoRect=WxH uncropped=<bool>`。
    /// 是否裁切由 Core 的 `DisplayFit.isUncropped` 判定。
    static func line(lane: String, gravity: String, presentation: CGSize, bounds: CGSize, videoRect: CGRect) -> String {
        let uncropped = DisplayFit.isUncropped(
            videoRect: videoRect, bounds: CGRect(origin: .zero, size: bounds), presentation: presentation
        )
        return "display-check lane=\(lane) gravity=\(gravity) presentation=\(dims(presentation))"
            + " bounds=\(dims(bounds)) videoRect=\(dims(videoRect.size)) uncropped=\(uncropped)"
    }

    /// 画面实际显示的尺寸（§2）：有画面时取 `videoRect`；还没有画面时按 16:9 放进窗格估算。
    static func displaySize(videoRect: CGRect, bounds: CGSize) -> CGSize {
        if videoRect.width > 0, videoRect.height > 0 { return videoRect.size }
        return DisplayFit.fitted(
            presentation: CGSize(width: 16, height: 9), bounds: CGRect(origin: .zero, size: bounds)
        ).size
    }

    static func dims(_ size: CGSize) -> String {
        guard size.width.isFinite, size.height.isFinite else { return "0x0" }
        return "\(Int(size.width.rounded()))x\(Int(size.height.rounded()))"
    }
}
