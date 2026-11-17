import Foundation

/// 手机场景阶段。不用 UIKit 的 `ScenePhase`，Core 里用这个值。
public enum PhoneScenePhase: Equatable, Sendable {
    case active
    case inactive
    case background
}

/// 进后台前正在做、回前台要重做的共享工作。
public enum PhoneResumeIntent: Equatable, Sendable {
    case reload
    case search
}

/// 某一路回前台时原先在做的事。满足 `shouldPlay` 时原样带回，方便调用方区分首次解析、切房和刷新。
public enum PhoneLaneActivity: Equatable, Sendable {
    case idle
    case firstResolve
    case roomSwitch
    case refreshing
    case playing
}

/// 回前台时拿来判断该不该重新起播的一路快照。
public struct PhoneLaneSnapshot: Equatable, Sendable {
    public var role: PlaybackLaneRole
    public var username: String?
    public var manuallyPaused: Bool
    public var visible: Bool
    public var failed: Bool
    public var activity: PhoneLaneActivity

    public init(
        role: PlaybackLaneRole,
        username: String?,
        manuallyPaused: Bool,
        visible: Bool,
        failed: Bool,
        activity: PhoneLaneActivity
    ) {
        self.role = role
        self.username = username
        self.manuallyPaused = manuallyPaused
        self.visible = visible
        self.failed = failed
        self.activity = activity
    }
}

/// `PhoneLifecycle.actions` 给出的有序动作。进后台与回前台各是一条固定序列。
public enum PhoneLifecycleAction: Equatable, Sendable {
    case gateOff
    case epochSuspend
    case settleDwell(LaneSettlement.EndReason)
    case flushSavers
    case suspendLanes
    case audioDeactivate
    case stopListener
    case noteResumeIntent(PhoneResumeIntent?)
    case epochResume
    case redoResumeIntent(PhoneResumeIntent)
    case requestPlay(PlaybackLaneRole, PhoneLaneActivity)
    case keepFailure(PlaybackLaneRole)
    case stayPaused(PlaybackLaneRole)
    case stayReleased(PlaybackLaneRole)
    /// 挂起目录、搜索、收藏查询、侧车、缩略图。不挂起画中画那一路。
    case suspendSharedTasks
    /// `LifecycleEpoch` 放行这一路的请求。只跟 `suspendSharedTasks` 一起出现。
    case allowLaneRequests(PlaybackLaneRole)
    /// 释放这一路：播放项、中继源，不允许再播。
    case releaseLane(PlaybackLaneRole)
    /// 这一路继续播放（画中画中，或即将自动进入）。
    case continueLane(PlaybackLaneRole)
    /// 中继保持监听，源表只留这一路。
    case retainRelaySource(PlaybackLaneRole)
}

/// 前后台切换的纯函数。进入 `.background` 与从挂起回到 `.active` 的动作顺序都在这里，不碰系统 API。
public enum PhoneLifecycle {
    /// `.active` 与 `.inactive` 时画面仍在，`.background` 时不在。
    public static func pointVisible(_ phase: PhoneScenePhase) -> Bool {
        phase != .background
    }

    /// 只有 `.active` 才算在看。`.inactive` 时画面照常播，停留不计时。
    public static func attentive(_ phase: PhoneScenePhase) -> Bool {
        phase == .active
    }

    /// 该路是否该重新解析地址。不读 `lastPlay`。
    public static func shouldPlay(_ lane: PhoneLaneSnapshot) -> Bool {
        guard let username = lane.username, !username.isEmpty else { return false }
        return !lane.manuallyPaused && lane.visible && !lane.failed
    }

    /// `to == .background`（且原先不在后台）给出进后台序列。
    /// 回到 `.active` 只看 `wasSuspended`：中间经过 `.inactive` 也一样。没挂起过则什么都不做。
    /// `pip == nil` 时与修订 A2 之前相同：进后台全停。
    /// 有画中画输入时，焦点路已在画中画或将自动进入，则该路继续、其余路释放、中继只留该路；
    /// 共享任务照常挂起。已经在后台且状态变为 `.stopping` 时，补做原来的全停（画中画结束）。
    /// 快照里能找到焦点路时，用 `shouldPlay` 覆盖 `pip.focusPlaying`，暂停、失败、隐藏的路不会自动进入。
    public static func actions(
        from: PhoneScenePhase,
        to: PhoneScenePhase,
        wasSuspended: Bool,
        lanes: [PhoneLaneSnapshot],
        resumeIntent: PhoneResumeIntent?,
        pip: PiPPolicy.Input? = nil
    ) -> [PhoneLifecycleAction] {
        if to == .background {
            if from != .background {
                if let pip, let focus = continuedLane(evaluate(pip, lanes: lanes, scene: to)) {
                    return pictureInPictureBackground(lanes: lanes, focus: focus, resumeIntent: resumeIntent)
                }
                return backgroundStop(resumeIntent)
            }
            if let pip, pip.pictureInPicture == .stopping {
                return backgroundStop(resumeIntent)
            }
            return []
        }
        guard to == .active, wasSuspended else { return [] }
        var result: [PhoneLifecycleAction] = [.epochResume]
        if let resumeIntent {
            result.append(.redoResumeIntent(resumeIntent))
        }
        result.append(contentsOf: lanes.map(outcome))
        return result
    }

    /// 没有画中画时的进后台序列。顺序保持修订前的合同。
    private static func backgroundStop(_ resumeIntent: PhoneResumeIntent?) -> [PhoneLifecycleAction] {
        [
            .gateOff,
            .epochSuspend,
            .settleDwell(.background),
            .flushSavers,
            .suspendLanes,
            .audioDeactivate,
            .stopListener,
            .noteResumeIntent(resumeIntent)
        ]
    }

    /// 画中画继续时不发全局 `gateOff`、不 `audioDeactivate`、不 `stopListener`。
    /// 非焦点路逐路释放。共享任务挂起，但画中画路的请求放行。
    private static func pictureInPictureBackground(
        lanes: [PhoneLaneSnapshot],
        focus: PlaybackLaneRole,
        resumeIntent: PhoneResumeIntent?
    ) -> [PhoneLifecycleAction] {
        var result: [PhoneLifecycleAction] = [
            .suspendSharedTasks,
            .allowLaneRequests(focus),
            .settleDwell(.background),
            .flushSavers
        ]
        for lane in lanes where lane.role != focus {
            result.append(.releaseLane(lane.role))
        }
        result.append(.continueLane(focus))
        result.append(.retainRelaySource(focus))
        result.append(.noteResumeIntent(resumeIntent))
        return result
    }

    private static func evaluate(
        _ pip: PiPPolicy.Input,
        lanes: [PhoneLaneSnapshot],
        scene: PhoneScenePhase
    ) -> PiPPolicy.Decision {
        var input = pip
        input.scene = scene
        if !lanes.isEmpty {
            input.lanes = lanes.map(\.role)
        }
        if let focusLane = lanes.first(where: { $0.role == input.focus }) {
            input.focusPlaying = shouldPlay(focusLane)
        }
        return PiPPolicy.decide(input)
    }

    /// 中继留着、而且留下的那一路允许播放，才算画中画继续。
    private static func continuedLane(_ decision: PiPPolicy.Decision) -> PlaybackLaneRole? {
        guard decision.keepsRelayListening, let source = decision.retainedRelaySource else { return nil }
        guard decision.playbackAllowed(source) else { return nil }
        return source
    }

    /// 隐藏优先（保持已释放），其次手动暂停，其次失败态，其余满足 `shouldPlay` 才重新起播。
    private static func outcome(_ lane: PhoneLaneSnapshot) -> PhoneLifecycleAction {
        if !lane.visible { return .stayReleased(lane.role) }
        if lane.manuallyPaused { return .stayPaused(lane.role) }
        if lane.failed { return .keepFailure(lane.role) }
        if shouldPlay(lane) { return .requestPlay(lane.role, lane.activity) }
        return .stayReleased(lane.role)
    }
}
