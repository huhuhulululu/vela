import AVFoundation
import Foundation
import os

/// `AVAudioSession` 的真实实现。选项随类别一起设；静音与出声的类别、模式相同，只有 `.mixWithOthers` 之别。
struct AVAudioSessionDriver: AudioSessionDriving {
    func setCategory(_ category: AudioSessionCategory, mode: AudioSessionMode?, options: AudioSessionOptions) throws {
        let session = AVAudioSession.sharedInstance()
        let avCategory: AVAudioSession.Category = category == .ambient ? .ambient : .playback
        let avMode: AVAudioSession.Mode = mode == .moviePlayback ? .moviePlayback : .default
        let avOptions: AVAudioSession.CategoryOptions = options.contains(.mixWithOthers) ? [.mixWithOthers] : []
        try session.setCategory(avCategory, mode: avMode, options: avOptions)
    }

    func setActive(_ active: Bool, notifyOthers: Bool) throws {
        let options: AVAudioSession.SetActiveOptions = active || !notifyOthers ? [] : [.notifyOthersOnDeactivation]
        try AVAudioSession.sharedInstance().setActive(active, options: options)
    }
}

/// iOS 音频转换的唯一执行者（§3.3，修订 A2 的 7b）。步骤由 Core 的 `AudioTransition.plan` 排定，
/// 顺序执行与失败处理在 `AudioTransitionRunner`：先静音旧路、`setCategory`、`setActive`，成功之后才取消目标路静音。
/// 播放器的 `setVolume` 在 iOS 上只从这里调用；界面和模型都走 `StageModel` 的入口，再经本控制器。
@MainActor
final class AudioFocusController: AudioFocusing, AudioLaneControlling {
    static let log = Logger(subsystem: "com.vela.stage", category: "audio")

    private weak var stage: StageModel?
    private let driver: AudioSessionDriving
    private let center: NotificationCenter
    private var observers: [NSObjectProtocol] = []

    /// 画中画是否正在播放（再静音时据此保持会话）。画中画接入时由 `PhonePlayerLayer` 一侧改写。
    var pictureInPictureActive: () -> Bool = { false }
    /// 焦点路。双窗（PR3）接入前恒为主路。
    var focus: () -> PlaybackLaneRole = { .primary }

    var focusRole: PlaybackLaneRole { focus() }
    /// 会话此刻是否按我们的配置激活着。冷启动静音起播也要先落实会话类别，否则停在系统默认类别。
    /// 成功激活时置真，成功停用（再静音、进后台、最后一路停播）时复位，下次起播按当时的静音状态重新准备。
    private(set) var sessionPrepared = false
    /// `setActive(false)` 失败后重试前的等待。测试缩短。
    var deactivationRetryDelay: TimeInterval = 0.25
    /// 停用失败且重试也失败：他人还没收到恢复通知，下次起播或停播时先补做。
    private(set) var deactivationPending = false
    private var deactivationRetry: Task<Void, Never>?

    init(
        stage: StageModel,
        driver: AudioSessionDriving = AVAudioSessionDriver(),
        center: NotificationCenter = .default
    ) {
        self.stage = stage
        self.driver = driver
        self.center = center
    }

    deinit {
        for token in observers { NotificationCenter.default.removeObserver(token) }
    }

    // MARK: 转换

    func perform(_ intent: AudioIntent) -> AudioOutcome {
        guard let stage else {
            return AudioOutcome(
                mutedAfter: nil, audibleLane: nil, writesSettings: false,
                notice: nil, pausesPlayback: false, ignored: true, failed: false
            )
        }
        let roles = stage.lanes.map { $0.id.role }
        let focused = focus()
        let plan = AudioTransition.plan(
            intent: intent,
            focus: focused,
            currentlyAudible: stage.muted ? nil : focused,
            lanes: roles,
            pictureInPictureActive: pictureInPictureActive()
        )
        let outcome = AudioTransitionRunner.run(plan, lanes: roles, driver: driver, sink: self) { line in
            Self.log.info("\(line, privacy: .public)")
        }
        noteSession(outcome)
        return outcome
    }

    /// 起播前：先补做之前没成功的停用，再按当前静音状态准备会话（已按配置激活着就不重复）。
    func prepareSession(muted: Bool) -> AudioOutcome? {
        redoPendingDeactivation()
        guard !sessionPrepared else { return nil }
        Self.log.info("audio prepare muted=\(muted)")
        return perform(.prepare(muted: muted))
    }

    /// 最后一路停播（筛选清空、播放失败、进入访问确认）：停用会话并通知他人恢复（§3.3）。
    /// 会话本来就没激活、或画中画还在播时不做。运行时静音意图不变。
    func releaseAfterLastLaneStopped() -> AudioOutcome? {
        guard sessionPrepared || deactivationPending, !pictureInPictureActive() else { return nil }
        Self.log.info("audio last lane stopped")
        return perform(.deactivate(.lastLaneStopped))
    }

    /// 记下这次转换之后会话的状态；停用失败就短延迟后重试一次，仍失败记错误并留待补做。
    private func noteSession(_ outcome: AudioOutcome) {
        if let active = outcome.sessionActiveAfter {
            sessionPrepared = active
            deactivationPending = false
            deactivationRetry?.cancel()
            deactivationRetry = nil
        }
        guard outcome.deactivationFailed else { return }
        deactivationPending = true
        deactivationRetry?.cancel()
        let delay = UInt64(max(deactivationRetryDelay, 0) * 1_000_000_000)
        deactivationRetry = Task { @MainActor [weak self] in
            try? await Task.sleep(nanoseconds: delay)
            guard !Task.isCancelled, let self else { return }
            self.deactivationRetry = nil
            if !self.redoPendingDeactivation() {
                Self.log.error("audio deactivate retry failed, will redo on next start or stop")
            }
        }
    }

    /// 补做停用（通知他人恢复）。没有待补做、或画中画还在播（会话要保持）时什么都不做。返回是否已没有待补做。
    @discardableResult
    private func redoPendingDeactivation() -> Bool {
        guard deactivationPending else { return true }
        guard !pictureInPictureActive() else { return false }
        do {
            try driver.setActive(false, notifyOthers: true)
            Self.log.info("audio deactivate redone")
            deactivationPending = false
            sessionPrepared = false
            return true
        } catch {
            Self.log.error("audio deactivate redo failed")
            return false
        }
    }

    func syncVolume(to lane: PlaybackLane) {
        guard let stage else { return }
        // 非焦点路一律静音；音轨的取舍由播放器按 `MuteAudioPolicy` 做。
        lane.player.setVolume(stage.volume, muted: stage.muted || lane.id.role != focus())
    }

    /// `AudioLaneControlling`：转换步骤里的静音与取消静音，音量沿用模型当前值。
    nonisolated func setMuted(_ muted: Bool, lane role: PlaybackLaneRole) {
        MainActor.assumeIsolated {
            guard let stage, let lane = stage.lane(for: role) else { return }
            lane.player.setVolume(stage.volume, muted: muted)
        }
    }

    // MARK: 系统通知

    /// 订阅打断与路由变化。回调切回主线程再处理；类别和模式不在这里改。
    func start() {
        let session = AVAudioSession.sharedInstance()
        observers.append(center.addObserver(
            forName: AVAudioSession.interruptionNotification, object: session, queue: .main
        ) { [weak self] note in
            let raw = note.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt
            let suspended = (note.userInfo?[AVAudioSessionInterruptionWasSuspendedKey] as? Bool) ?? false
            MainActor.assumeIsolated { self?.handleInterruption(typeRaw: raw, wasSuspended: suspended) }
        })
        observers.append(center.addObserver(
            forName: AVAudioSession.routeChangeNotification, object: session, queue: .main
        ) { [weak self] note in
            let raw = note.userInfo?[AVAudioSessionRouteChangeReasonKey] as? UInt
            MainActor.assumeIsolated { self?.handleRouteChange(reasonRaw: raw) }
        })
    }

    /// 打断开始静音（`wasSuspended == true` 忽略）；打断结束不自动出声，由用户再点取消静音。
    func handleInterruption(typeRaw: UInt?, wasSuspended: Bool) {
        guard let typeRaw, let type = AVAudioSession.InterruptionType(rawValue: typeRaw) else { return }
        let intent: AudioIntent
        switch type {
        case .began: intent = .interruptionBegan(wasSuspended: wasSuspended)
        case .ended: intent = .interruptionEnded
        @unknown default: return
        }
        Self.log.info("audio interruption \(type == .began ? "began" : "ended", privacy: .public) suspended=\(wasSuspended)")
        stage?.applyAudioOutcome(perform(intent))
    }

    /// 拔耳机（旧设备不可用）转静音，不暂停；画中画活跃时保持会话。其他原因不动。
    func handleRouteChange(reasonRaw: UInt?) {
        guard let reasonRaw, let reason = AVAudioSession.RouteChangeReason(rawValue: reasonRaw) else { return }
        let mapped: AudioRouteReason = reason == .oldDeviceUnavailable ? .oldDeviceUnavailable : .other
        guard mapped == .oldDeviceUnavailable else { return }
        Self.log.info("audio route oldDeviceUnavailable")
        stage?.applyAudioOutcome(perform(.route(mapped)))
    }
}
