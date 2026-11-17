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
    public static func actions(
        from: PhoneScenePhase,
        to: PhoneScenePhase,
        wasSuspended: Bool,
        lanes: [PhoneLaneSnapshot],
        resumeIntent: PhoneResumeIntent?
    ) -> [PhoneLifecycleAction] {
        if to == .background, from != .background {
            return [
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
        guard to == .active, wasSuspended else { return [] }
        var result: [PhoneLifecycleAction] = [.epochResume]
        if let resumeIntent {
            result.append(.redoResumeIntent(resumeIntent))
        }
        result.append(contentsOf: lanes.map(outcome))
        return result
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
