import AVFoundation
import os
import SwiftUI
import UIKit

/// iOS 播放层：固定 `.resizeAspect`，画面保持原始比例不裁切（裁决 5）；没有 `fill` 参数。
/// 图层与视图背景透明，留边区露出下面的 `SwapBackdrop`。
/// 画面实际显示的尺寸（`videoRect`）回报给 `onDisplaySize`（写进 `lane.displaySize`），并打 `display-check` 日志（§4.5）。
/// `display-check` 以页面容器（窗格）为准：bounds 是窗格尺寸，videoRect 换算到窗格坐标里再判是否裁切。
struct PlayerLayerView: UIViewRepresentable {
    var player: AVPlayer
    /// 日志里的路标识，与 `first-frame lane=<id>` 一致。
    var laneLabel: String
    /// 所在页面容器的几何。播放层自己的 bounds 被撑大时，只拿它比是看不出裁切的。
    var pane: PaneGeometry
    /// 焦点路才传：播放层进窗口时挂上画中画控制器（修订 A2）。其余路与不支持画中画的设备为 nil。
    var pip: PictureInPictureCoordinator? = nil
    var onDisplaySize: @MainActor (CGSize) -> Void

    func makeUIView(context: Context) -> PlayerUIView {
        let view = PlayerUIView()
        view.laneLabel = laneLabel
        view.onDisplaySize = onDisplaySize
        view.pane = pane
        view.pip = pip
        view.bind(player)
        return view
    }

    func updateUIView(_ view: PlayerUIView, context: Context) {
        // 父视图刷新也会进来。播放器实例没变就不要写 layer。
        view.laneLabel = laneLabel
        view.onDisplaySize = onDisplaySize
        if view.pane !== pane { view.pane = pane }
        if view.pip !== pip { view.pip = pip }
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
    /// 页面容器的几何；尺寸变化时重算一次（只是位置变化，如拖动翻页，不重算）。
    var pane: PaneGeometry? {
        didSet {
            pane?.onResize = { [weak self] in self?.refreshDisplay() }
            refreshDisplay()
        }
    }

    /// 画中画控制器。换了（或撤了）就把本层从旧的上面拆下，在窗口里时挂到新的上面。
    var pip: PictureInPictureCoordinator? {
        didSet {
            guard pip !== oldValue else { return }
            oldValue?.detach(playerLayer)
            if window != nil { pip?.attach(playerLayer) }
        }
    }

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

    override func didMoveToWindow() {
        super.didMoveToWindow()
        if window != nil {
            pip?.attach(playerLayer)
        } else {
            pip?.detach(playerLayer)
        }
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        refreshDisplay()
    }

    /// 布局、播放项、画面尺寸、就绪状态、窗格尺寸任一变化时重算：先回报显示尺寸，画面就绪后再打比例校验日志。
    private func refreshDisplay() {
        let layerSize = playerLayer.bounds.size
        guard layerSize.width > 0, layerSize.height > 0 else { return }
        let rect = playerLayer.videoRect
        let size = DisplayCheck.displaySize(videoRect: rect, bounds: layerSize)
        if size != lastReported {
            lastReported = size
            onDisplaySize?(size)
        }
        guard playerLayer.isReadyForDisplay, rect.width > 0, rect.height > 0,
              let presentation = playerLayer.player?.currentItem?.presentationSize,
              presentation.width > 0, presentation.height > 0,
              let container = pane?.frame, container.width > 0, container.height > 0,
              window != nil else { return }
        // videoRect 在播放层坐标里；换到窗口坐标，再减去窗格在窗口里的原点，得到窗格坐标。
        let inWindow = convert(rect, to: nil)
        let inPane = inWindow.offsetBy(dx: -container.minX, dy: -container.minY)
        let line = DisplayCheck.line(
            lane: laneLabel, gravity: DisplayCheck.gravityName(playerLayer.videoGravity),
            presentation: presentation, container: container.size, layer: layerSize, videoRect: inPane
        )
        guard line != lastLogged else { return }
        lastLogged = line
        // Release 也保留：模拟器验收按这一行判比例（S13）。
        Self.log.notice("\(line, privacy: .public)")
        Self.signposter.emitEvent("display-check", "\(line, privacy: .public)")
    }
}

/// 页面容器（窗格）在窗口坐标里的位置与大小。由 `PhoneRoomPage` 的 `onGeometryChange` 写入；
/// 尺寸变化时通知播放层重算 `display-check`。
@MainActor
final class PaneGeometry {
    var frame: CGRect = .zero {
        didSet { if frame.size != oldValue.size { onResize?() } }
    }
    var onResize: (@MainActor () -> Void)?
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

    /// 播放层与窗格尺寸允许的差（点），吸收取整误差。
    static let layerTolerance: CGFloat = 1

    /// 播放层 bounds 与窗格尺寸每一边相差不超过 1 点。
    static func layerMatches(_ layer: CGSize, container: CGSize) -> Bool {
        abs(layer.width - container.width) <= layerTolerance && abs(layer.height - container.height) <= layerTolerance
    }

    /// `display-check lane=<id> gravity=<值> presentation=WxH bounds=WxH layer=WxH videoRect=WxH uncropped=<bool>`。
    /// `bounds` 是页面容器（窗格）尺寸，`layer` 是播放层 bounds，`videoRect` 已换算到窗格坐标。
    /// `uncropped` 要求两条都成立：播放层与窗格相差 ≤1 点；`DisplayFit.isUncropped` 按窗格判定画面未裁切。
    static func line(
        lane: String, gravity: String, presentation: CGSize, container: CGSize, layer: CGSize, videoRect: CGRect
    ) -> String {
        let uncropped = layerMatches(layer, container: container) && DisplayFit.isUncropped(
            videoRect: videoRect, bounds: CGRect(origin: .zero, size: container), presentation: presentation
        )
        return "display-check lane=\(lane) gravity=\(gravity) presentation=\(dims(presentation))"
            + " bounds=\(dims(container)) layer=\(dims(layer)) videoRect=\(dims(videoRect.size)) uncropped=\(uncropped)"
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
