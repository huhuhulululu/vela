import AVFoundation
import Foundation
import os
import SwiftUI

/// iOS 平台钩子：没有老板键、菜单栏与应用隐藏；画面不裁切（裁决 5），`allowsCrop` 恒为 false。
/// 前后台由 `scenePhaseChanged` 接入：按 `PhoneLifecycle.actions` 排出的顺序逐条执行（§3.2）。
@MainActor
final class PlatformHooks {
    let allowsCrop = false
    /// 上一次收到的场景阶段。
    private(set) var scenePhase: PhoneScenePhase = .active
    private static let log = Logger(subsystem: "com.vela.stage", category: "lifecycle")

    /// 音频转换的唯一执行者与内存警告订阅，由 `attachAudio(to:)` 在模型初始化时装上。
    private(set) var audioFocus: AudioFocusController?
    private var memoryWarning: NSObjectProtocol?

    nonisolated init() {}

    /// 模型初始化时调用：启动杂务（备份排除）、装上音频控制器与内存警告订阅。
    func attachAudio(to stage: StageModel) {
        PhoneHousekeeping.ensureBackupExclusion()
        memoryWarning = PhoneHousekeeping.observeMemoryWarnings(stage: stage)
        let controller = AudioFocusController(stage: stage)
        controller.start()
        audioFocus = controller
        stage.audioFocus = controller
    }

    var appHidden: Bool { false }

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
        let actions = PhoneLifecycle.actions(
            from: previous, to: next, wasSuspended: stage.lifecycleSuspended,
            lanes: stage.phoneSnapshots(), resumeIntent: intent
        )
        run(actions, on: stage)
        let visible = PhoneLifecycle.pointVisible(next)
        if !stage.lifecycleSuspended, stage.pointVisible != visible {
            stage.applyPower(windowVisible: visible)
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
        case .keepFailure, .stayPaused, .stayReleased:
            // 失败提示、手动暂停与隐藏路都保持原样，不自动重播。
            break
        }
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
