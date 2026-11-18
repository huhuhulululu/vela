import AVFoundation
import Foundation
import os
import SwiftUI

/// iOS 平台钩子：没有老板键、菜单栏与应用隐藏；画面不裁切（裁决 5），`allowsCrop` 恒为 false。
/// 前后台由 `scenePhaseChanged` 接入：按 `PhoneLifecycle.actions` 排出的顺序逐条执行（§3.2）。
@MainActor
final class PlatformHooks {
    let allowsCrop = false
    /// iOS 竖屏有双窗。
    let supportsDual = true
    /// 画中画小窗开着（或在启动）时焦点路不能换：小窗里放的就是它（T18b）。
    var focusLocked: Bool { pictureInPicturePlaying }
    /// 上一次收到的场景阶段。
    private(set) var scenePhase: PhoneScenePhase = .active
    private static let log = Logger(subsystem: "com.vela.stage", category: "lifecycle")

    /// 音频转换的唯一执行者与内存警告订阅，由 `attachAudio(to:)` 在模型初始化时装上。
    private(set) var audioFocus: AudioFocusController?
    private var memoryWarning: NSObjectProtocol?

    // MARK: 画中画状态（修订 A2、A3）

    /// 画中画控制器。不支持画中画的设备为 nil：按钮隐藏，进后台照旧全停。
    var pip: PictureInPictureControlling?
    /// 系统控制器本身，控制栏按钮观察它。测试里的桩不是它，此处为 nil。
    var pipCoordinator: PictureInPictureCoordinator? { pip as? PictureInPictureCoordinator }
    /// 由委托事件维护的画中画状态。生命周期判定与音频钩子都读它。
    private(set) var pictureInPictureState: PiPPolicy.State = .none
    /// 画中画正在显示或正在启动（音频钩子：再静音时据此保持会话）。
    var pictureInPicturePlaying: Bool { pictureInPictureState == .active || pictureInPictureState == .starting }
    /// 焦点路的播放器是不是真在走（`timeControlStatus == .playing`）。测试替换。
    var playerAdvancing: (PlaybackLane) -> Bool = { $0.player.player.timeControlStatus == .playing }
    /// 回桌面后等画中画进入的时限。测试缩短。
    var pipFallbackDelay: TimeInterval = PiPFallback.timeout
    /// 本次后台按画中画分支放行了焦点路，尚未全停。
    private var pipContinuing = false
    /// 用户点了画中画里的「回到 App」：随后的结束不当成关闭，等回前台按 §3.2 恢复。
    private var pipRestoreRequested = false
    private var pipFallbackTask: Task<Void, Never>?
    private static let pipLog = Logger(subsystem: "com.vela.stage", category: "pip")

    nonisolated init() {}

    /// 模型初始化时调用：启动杂务（备份排除）、装上音频控制器与内存警告订阅。
    func attachAudio(to stage: StageModel) {
        PhoneHousekeeping.ensureBackupExclusion()
        memoryWarning = PhoneHousekeeping.observeMemoryWarnings(stage: stage)
        let controller = AudioFocusController(stage: stage)
        controller.start()
        audioFocus = controller
        stage.audioFocus = controller
        controller.pictureInPictureActive = { [weak self] in self?.pictureInPicturePlaying ?? false }
        if let coordinator = PictureInPictureCoordinator.makeIfSupported(autoEnter: stage.settings.pipAutoEnter) {
            coordinator.onEvent = { [weak self, weak stage] event in
                guard let self, let stage else { return }
                self.pictureInPictureEvent(event, stage: stage)
            }
            pip = coordinator
        }
    }

    var appHidden: Bool { false }

    /// iOS 没有指针悬停与点击穿透（§2）：固定 `conditionalOpacity = false`、`clickThrough = false`，
    /// 因此 `engaged` 恒为真（控制栏 2.4 秒后收起、播放时阻止自动锁屏）。只改内存里的值，不写设置。
    func adjustedPresence(_ presence: Presence) -> Presence {
        var fixed = presence
        fixed.conditionalOpacity = false
        fixed.clickThrough = false
        return fixed
    }

    /// 只有 `.active` 算在看（§2 `attentive`）：`.inactive` 时画面照常播，停留不计时。
    var attentive: Bool { PhoneLifecycle.attentive(scenePhase) }

    func observeApp(
        unhidden: @escaping @MainActor () -> Void,
        hidden: @escaping @MainActor () -> Void,
        terminating: @escaping @MainActor () -> Void
    ) {}

    /// iOS 没有全局热键与锁屏通知，视为注册成功，不打「boss key unavailable」。
    func startSystemHooks(bossKey action: @escaping () -> Void, inactiveChanged: @escaping (Bool) -> Void) -> Bool {
        true
    }

    func terminate() {}

    func hide() {}

    func unhide() {}

    func revealMain() {}

    // MARK: 前后台（§3.2）

    /// 场景阶段变化。进后台给出挂起序列；回 `.active` 只看是否挂起过（中间经过 `.inactive` 也一样）。
    /// 动作执行完、且已不在挂起时，再按新阶段重算播放闸门（`.active` 与 `.inactive` 画面可见）。返回执行的动作，测试用。
    @discardableResult
    func scenePhaseChanged(_ next: PhoneScenePhase, stage: StageModel) -> [PhoneLifecycleAction] {
        let previous = scenePhase
        scenePhase = next
        let intent = next == .background ? stage.currentResumeIntent() : stage.wiring.resumeIntent
        if next == .active, stage.lifecycleSuspended {
            returnFromBackgroundPictureInPicture()
        }
        let pipInput = next == .background && previous != .background ? pictureInPictureInput(stage: stage) : nil
        let actions = PhoneLifecycle.actions(
            from: previous, to: next, wasSuspended: stage.lifecycleSuspended,
            lanes: stage.phoneSnapshots(), resumeIntent: intent, pip: pipInput
        )
        run(actions, on: stage)
        if let focus = Self.continuedLane(actions) {
            pipContinuing = true
            Self.pipLog.notice("pip continue lane=\(StageModel.label(focus), privacy: .public) state=\(String(describing: self.pictureInPictureState), privacy: .public)")
            if pictureInPictureState != .active { armPictureInPictureFallback(stage: stage, reason: "timeout") }
        }
        // 回前台时小窗已经收掉（或根本没起来）：另一路不再被画中画扣着。小窗还在收的话由 didStop 放开。
        if next == .active, !pictureInPicturePlaying { stage.setPipSolo(false) }
        let visible = PhoneLifecycle.pointVisible(next)
        if !stage.lifecycleSuspended, stage.pointVisible != visible {
            stage.applyPower(windowVisible: visible)
        } else if !stage.lifecycleSuspended, PhoneLifecycle.attentive(previous) != PhoneLifecycle.attentive(next) {
            // `.active` 与 `.inactive` 之间画面都在、闸门不变，只是停留要停表或续表。
            for lane in stage.lanes { stage.updateDwellActive(on: lane) }
        }
        return actions
    }

    /// 按顺序执行生命周期动作。画中画分支（`suspendSharedTasks` 与 `allowLaneRequests` 成对出现）
    /// 用 `LifecycleEpoch.suspend(allowingLane:)`：共享任务挂起，只放行那一路。
    func run(_ actions: [PhoneLifecycleAction], on stage: StageModel) {
        let pipLane = actions.lazy.compactMap { action -> PlaybackLaneRole? in
            if case .allowLaneRequests(let role) = action { return role }
            return nil
        }.first
        for action in actions {
            perform(action, pipLane: pipLane, on: stage)
        }
    }

    private func perform(_ action: PhoneLifecycleAction, pipLane: PlaybackLaneRole?, on stage: StageModel) {
        switch action {
        case .gateOff:
            stage.applyPower(windowVisible: false)
        case .epochSuspend:
            stage.suspendWork()
        case .suspendSharedTasks:
            stage.suspendWork(allowingLane: pipLane)
        case .allowLaneRequests:
            break // 已随 `suspendSharedTasks` 一起生效
        case .settleDwell(let reason):
            stage.settleDwell(reason)
        case .flushSavers:
            stage.flushSavers()
        case .suspendLanes:
            stage.suspendLanes()
        case .releaseLane(let role):
            if let lane = stage.lane(for: role) { stage.release(lane) }
        case .continueLane, .retainRelaySource:
            // 画中画那一路照常播放；其余路已在 `releaseLane` 作废自己的中继源，源表里只剩这一路。
            break
        case .audioDeactivate:
            deactivateAudio(on: stage)
        case .stopListener:
            PlaylistRelay.shared.stopListener()
            Self.log.info("relay listener stopped")
        case .noteResumeIntent(let intent):
            stage.wiring.resumeIntent = intent
        case .epochResume:
            stage.resumeWork()
        case .redoResumeIntent(let intent):
            stage.redo(intent)
        case .requestPlay(let role, let activity):
            if let lane = stage.lane(for: role) { stage.resumePlay(lane, activity: activity) }
        case .keepFailure(let role), .stayPaused(let role), .stayReleased(let role):
            // 失败提示、手动暂停与隐藏路都保持原样，不自动重播。播放器自己记下的恢复（`suspendNow` 补写的
            // 暂停起点 + `lastPlay`）一并清掉：否则闸门重开时它会自己重新解析起播。恢复只由 Stage 层决定（§3.2）。
            stage.lane(for: role)?.player.forgetSuspendedResume()
        }
    }

    // MARK: 画中画（修订 A2 的 T15b、修订 A3 的 3 秒回退）

    /// 进后台时交给 `PiPPolicy` 的输入。没有画中画控制器（不支持的设备）时为 nil，照旧全停。
    /// 「焦点路正在播放」要求画面已在播且播放器真在走；暂停、失败、隐藏由 `PhoneLifecycle` 再收紧。
    private func pictureInPictureInput(stage: StageModel) -> PiPPolicy.Input? {
        guard let pip else { return nil }
        let focus = audioFocus?.focusRole ?? .primary
        let playing = stage.lane(for: focus).map { stage.activity(of: $0) == .playing && playerAdvancing($0) } ?? false
        return PiPPolicy.Input(
            platform: .phone, scene: .background, pictureInPicture: pictureInPictureState,
            autoEnterEnabled: pip.canAutoEnter, muted: stage.muted, focus: focus,
            lanes: stage.lanes.map { $0.id.role }, focusPlaying: playing,
            bossKey: false, lockScreenOrSystemSleep: false, windowMinimized: false, windowOccluded: false
        )
    }

    private static func continuedLane(_ actions: [PhoneLifecycleAction]) -> PlaybackLaneRole? {
        for action in actions {
            if case .continueLane(let role) = action { return role }
        }
        return nil
    }

    /// 委托事件。开始与结束都打日志；后台里画中画没起来、起不来或被关掉，一律按无画中画全停。
    func pictureInPictureEvent(_ event: PiPEvent, stage: StageModel) {
        switch event {
        case .willStart:
            pictureInPictureState = .starting
            if !PerfEntry.noPipSolo { stage.setPipSolo(true) }
            keepMutedSessionForPictureInPicture(stage: stage)
        case .didStart:
            pictureInPictureState = .active
            Self.pipLog.notice("pip started")
            if pipContinuing {
                pipFallbackTask?.cancel()
                pipFallbackTask = nil
            } else if scenePhase == .background {
                // 回退之后才起来的画中画：各路已经释放，留着只会是黑小窗。
                Self.pipLog.notice("pip late-start closed")
                pip?.stop()
            }
        case .failed(let error):
            pictureInPictureState = .none
            stage.setPipSolo(false)
            Self.pipLog.notice("pip failed error=\(error, privacy: .public)")
            if pipContinuing, scenePhase == .background {
                fallbackStop(stage: stage, reason: "failed")
            } else {
                finishLatePictureInPicture(stage: stage)
            }
        case .willStop:
            pictureInPictureState = .stopping
        case .didStop:
            pictureInPictureState = .none
            stage.setPipSolo(false)
            Self.pipLog.notice("pip stopped restore=\(self.pipRestoreRequested)")
            guard pipContinuing, scenePhase == .background else {
                finishLatePictureInPicture(stage: stage)
                return
            }
            if pipRestoreRequested {
                // 正在回到 App：等回前台按 §3.2 恢复；万一没回来，到时照样全停。
                armPictureInPictureFallback(stage: stage, reason: "restore-timeout")
            } else {
                stopAllAfterPictureInPicture(stage: stage)
            }
        case .restoreRequested:
            pipRestoreRequested = true
        }
    }

    /// 没有续播的画中画（全停之后迟到的启动）结束或失败：补做之前没成功的停用，让他人的音乐恢复。
    private func finishLatePictureInPicture(stage: StageModel) {
        guard scenePhase == .background, let audioFocus = stage.audioFocus as? AudioFocusController else { return }
        if !audioFocus.redoPendingDeactivation() {
            Self.pipLog.error("pip late-end deactivate still failing")
        }
    }

    /// 改设置里的「回桌面自动进入画中画」：写进设置并落到控制器上。
    func setPictureInPictureAutoEnter(_ enabled: Bool, stage: StageModel) {
        stage.settings.pipAutoEnter = enabled
        pip?.autoEnter = enabled
        Self.pipLog.notice("pip auto-enter \(enabled ? "on" : "off", privacy: .public)")
    }

    /// 从后台回前台（按 §3.2 恢复之前）：撤回退计时，画中画还开着就退出，回到 App 内播放。
    private func returnFromBackgroundPictureInPicture() {
        pipFallbackTask?.cancel()
        pipFallbackTask = nil
        pipContinuing = false
        pipRestoreRequested = false
        if pictureInPicturePlaying { pip?.stop() }
    }

    /// 静音时画中画也要播放类别：保持 `.playback + .mixWithOthers` 会话激活（7b），不打断他人音乐。
    /// 只在画中画真会继续播时做：前台（按钮或回桌面自动进入的那一刻，场景还是 active / inactive），
    /// 或后台里本次按画中画分支放行了焦点路。已经全停之后迟到的 willStart 不碰会话（全停时已停用），
    /// 否则会话被重新激活后再没人释放；随后的 didStart 会把这个小窗关掉。
    private func keepMutedSessionForPictureInPicture(stage: StageModel) {
        guard pipContinuing || scenePhase != .background else {
            Self.pipLog.notice("pip late-start session untouched")
            return
        }
        guard stage.muted, let audioFocus = stage.audioFocus else { return }
        stage.applyAudioOutcome(audioFocus.perform(.prepare(muted: true)))
    }

    private func armPictureInPictureFallback(stage: StageModel, reason: String) {
        pipFallbackTask?.cancel()
        let delay = UInt64(max(pipFallbackDelay, 0) * 1_000_000_000)
        pipFallbackTask = Task { @MainActor [weak self, weak stage] in
            try? await Task.sleep(nanoseconds: delay)
            guard !Task.isCancelled, let self, let stage, self.pipContinuing else { return }
            guard PiPFallback.shouldStop(scene: self.scenePhase, state: self.pictureInPictureState) else { return }
            self.fallbackStop(stage: stage, reason: reason)
        }
    }

    /// 没能进入画中画：关掉可能半开的小窗，再全停释放。
    private func fallbackStop(stage: StageModel, reason: String) {
        Self.pipLog.notice("pip fallback-stop reason=\(reason, privacy: .public)")
        if pictureInPictureState != .none { pip?.abort() }
        // 还没真正进入时 stop() 是空操作，不会有委托回调：不等它，立刻放开焦点锁与另一路的扣留。
        pictureInPictureState = .none
        stage.setPipSolo(false)
        stopAllAfterPictureInPicture(stage: stage)
    }

    /// 画中画结束或回退：补做 §3.2 的进后台全停（共享任务已挂起，这里不再放行任何路）。
    private func stopAllAfterPictureInPicture(stage: StageModel) {
        pipFallbackTask?.cancel()
        pipFallbackTask = nil
        pipContinuing = false
        pipRestoreRequested = false
        guard scenePhase == .background, let pip = pictureInPictureInput(stage: stage) else { return }
        var ended = pip
        ended.pictureInPicture = .stopping
        let actions = PhoneLifecycle.actions(
            from: .background, to: .background, wasSuspended: stage.lifecycleSuspended,
            lanes: stage.phoneSnapshots(), resumeIntent: stage.wiring.resumeIntent, pip: ended
        )
        run(actions, on: stage)
        Self.pipLog.notice("pip background stop-all")
    }

    /// 后台释放音频会话，让被打断的其他 App 恢复（§3.3）。交给 `AudioFocusController` 按计划执行。
    private func deactivateAudio(on stage: StageModel) {
        guard let audioFocus = stage.audioFocus else { return }
        let outcome = audioFocus.perform(.deactivate(.background))
        stage.applyAudioOutcome(outcome)
        Self.log.info("audio deactivated")
    }
}

extension PhoneScenePhase {
    init(_ phase: ScenePhase) {
        switch phase {
        case .active: self = .active
        case .inactive: self = .inactive
        case .background: self = .background
        @unknown default: self = .inactive
        }
    }
}
