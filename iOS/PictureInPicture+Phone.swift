import AVKit
import os

/// 画中画控制器回报给生命周期的事件。系统委托回调一一对应；`restoreRequested` 是用户点了「回到 App」。
enum PiPEvent: Equatable {
    case willStart
    case didStart
    case failed(String)
    case willStop
    case didStop
    case restoreRequested
}

/// 生命周期对画中画控制器的最小需求。测试用桩替换系统控制器。
@MainActor
protocol PictureInPictureControlling: AnyObject {
    /// 自动进入开着，且系统此刻允许对焦点路的播放层进入画中画。
    var canAutoEnter: Bool { get }
    /// 设置里的「回桌面自动进入画中画」，改动立即落到系统控制器上。
    var autoEnter: Bool { get set }
    func stop()
    /// 启动中止：回退全停时调用。系统控制器还没真正进入时 `stop()` 是空操作、也不会有回调，
    /// 这里要把协调器的 active、旧控制器与播放层绑定一并清掉，之后能重新绑到新的焦点路。
    func abort()
}

extension PictureInPictureControlling {
    func abort() { stop() }
}

/// 回桌面后的回退判定（修订 A3）：按「将自动进入」放行焦点路之后，到时仍不在后台画中画里就全停释放。
/// 只有 `.active` 算进入：一直卡在启动中也按未进入处理，免得留下后台播放或黑小窗。
enum PiPFallback {
    static let timeout: TimeInterval = 3

    static func shouldStop(scene: PhoneScenePhase, state: PiPPolicy.State) -> Bool {
        scene == .background && state != .active
    }
}

/// iOS 画中画控制器（单窗只挂焦点路）。播放层出现在窗口里时 `attach`，移出时 `detach`；
/// 画中画进行中不换层、不拆控制器。委托回调转成 `PiPEvent` 交给 `onEvent`（由 `PlatformHooks` 接上）。
/// 不支持画中画的设备（iPhone 模拟器）不创建本对象，按钮隐藏，进后台照旧全停。
@MainActor
final class PictureInPictureCoordinator: NSObject, ObservableObject, PictureInPictureControlling {
    static let log = Logger(subsystem: "com.vela.stage", category: "pip")

    @Published private(set) var active = false
    @Published private(set) var possible = false
    @Published var autoEnter: Bool {
        didSet { controller?.canStartPictureInPictureAutomaticallyFromInline = autoEnter }
    }
    var onEvent: ((PiPEvent) -> Void)?

    var canAutoEnter: Bool { autoEnter && possible && controller != nil }

    private var controller: AVPictureInPictureController?
    private weak var boundLayer: AVPlayerLayer?
    private var possibleObservation: NSKeyValueObservation?

    /// 系统不支持画中画时返回 nil。
    static func makeIfSupported(autoEnter: Bool) -> PictureInPictureCoordinator? {
        guard AVPictureInPictureController.isPictureInPictureSupported() else {
            log.notice("pip unsupported")
            return nil
        }
        return PictureInPictureCoordinator(autoEnter: autoEnter)
    }

    init(autoEnter: Bool) {
        self.autoEnter = autoEnter
        super.init()
    }

    /// 焦点路的播放层进了窗口。同一层不重复创建；画中画进行中保持原控制器。
    func attach(_ layer: AVPlayerLayer) {
        guard layer !== boundLayer, !active else { return }
        guard let next = AVPictureInPictureController(playerLayer: layer) else {
            Self.log.error("pip controller unavailable")
            return
        }
        next.delegate = self
        next.canStartPictureInPictureAutomaticallyFromInline = autoEnter
        controller = next
        Self.log.notice("pip attach")
        boundLayer = layer
        possibleObservation = next.observe(\.isPictureInPicturePossible, options: [.initial, .new]) { [weak self] pip, _ in
            let value = pip.isPictureInPicturePossible
            DispatchQueue.main.async { self?.setPossible(value) }
        }
    }

    /// 播放层离开窗口。只拆自己挂着的那一层，画中画进行中不拆。
    func detach(_ layer: AVPlayerLayer) {
        guard layer === boundLayer, !active else { return }
        possibleObservation = nil
        controller = nil
        boundLayer = nil
        setPossible(false)
    }

    /// 控制栏按钮：在画中画里就退出，否则进入。
    func toggle() {
        guard let controller else { return }
        if controller.isPictureInPictureActive {
            controller.stopPictureInPicture()
        } else if controller.isPictureInPicturePossible {
            controller.startPictureInPicture()
        }
    }

    func stop() {
        controller?.stopPictureInPicture()
    }

    /// 启动中止（3 秒回退）：系统控制器没真正进入时 `stopPictureInPicture()` 是空操作且没有回调，
    /// `active` 会一直是 true，`attach` / `detach` 的守卫都被挡住。这里不等回调：摘掉委托（旧控制器迟到的事件不再进来）、
    /// 清掉控制器与绑定、`active` 与 `possible` 归位；播放层还在窗口里就重新绑一个新控制器，焦点换了则由新焦点路的视图去绑。
    func abort() {
        let layer = boundLayer
        controller?.delegate = nil
        controller?.stopPictureInPicture()
        possibleObservation = nil
        controller = nil
        boundLayer = nil
        setPossible(false)
        if active { active = false }
        if let layer, layer.superlayer != nil { attach(layer) }
    }

    private func setPossible(_ value: Bool) {
        guard possible != value else { return }
        possible = value
        Self.log.notice("pip possible=\(value)")
    }

    func deliver(_ event: PiPEvent) {
        switch event {
        case .willStart, .didStart: if !active { active = true }
        case .failed, .didStop: if active { active = false }
        case .willStop, .restoreRequested: break
        }
        onEvent?(event)
    }
}

extension PictureInPictureCoordinator: AVPictureInPictureControllerDelegate {
    nonisolated func pictureInPictureControllerWillStartPictureInPicture(_ controller: AVPictureInPictureController) {
        MainActor.assumeIsolated { deliver(.willStart) }
    }

    nonisolated func pictureInPictureControllerDidStartPictureInPicture(_ controller: AVPictureInPictureController) {
        MainActor.assumeIsolated { deliver(.didStart) }
    }

    nonisolated func pictureInPictureController(
        _ controller: AVPictureInPictureController,
        failedToStartPictureInPictureWithError error: Error
    ) {
        let ns = error as NSError
        let text = "\(ns.domain) \(ns.code)"
        MainActor.assumeIsolated { deliver(.failed(text)) }
    }

    nonisolated func pictureInPictureControllerWillStopPictureInPicture(_ controller: AVPictureInPictureController) {
        MainActor.assumeIsolated { deliver(.willStop) }
    }

    nonisolated func pictureInPictureControllerDidStopPictureInPicture(_ controller: AVPictureInPictureController) {
        MainActor.assumeIsolated { deliver(.didStop) }
    }

    nonisolated func pictureInPictureController(
        _ controller: AVPictureInPictureController,
        restoreUserInterfaceForPictureInPictureStopWithCompletionHandler completionHandler: @escaping (Bool) -> Void
    ) {
        MainActor.assumeIsolated { deliver(.restoreRequested) }
        completionHandler(true)
    }
}
