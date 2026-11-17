import Foundation

/// 音频会话类别。对应系统的 ambient / playback，Core 不引用 AVFoundation。
public enum AudioSessionCategory: Equatable, Sendable {
    case ambient
    case playback
}

/// 出声时附加的会话模式。
public enum AudioSessionMode: Equatable, Sendable {
    case moviePlayback
}

/// 路由变化原因。拔耳机是 `.oldDeviceUnavailable`。
public enum AudioRouteReason: Equatable, Sendable {
    case oldDeviceUnavailable
    case other
}

/// 会话该用哪种类别、何时释放、打断与路由怎么处理。`AudioTransition` 按这里的结论排步骤。
public enum AudioSessionPolicy {
    public static let failureNotice = "暂时无法出声"

    public struct Configuration: Equatable, Sendable {
        public var category: AudioSessionCategory
        public var mode: AudioSessionMode?

        public init(category: AudioSessionCategory, mode: AudioSessionMode?) {
            self.category = category
            self.mode = mode
        }
    }

    /// 静音用 `.ambient`；出声用 `.playback` 加 `.moviePlayback`。
    public static func configuration(audible: Bool) -> Configuration {
        audible
            ? Configuration(category: .playback, mode: .moviePlayback)
            : Configuration(category: .ambient, mode: nil)
    }

    public enum DeactivationReason: Equatable, Sendable {
        case mute
        case background
        case lastLaneStopped
    }

    /// 再静音、进后台、最后一路停播都要 `setActive(false, .notifyOthersOnDeactivation)`。
    public static func deactivatesNotifyingOthers(on reason: DeactivationReason) -> Bool {
        switch reason {
        case .mute, .background, .lastLaneStopped:
            return true
        }
    }

    /// `wasSuspended == true` 的打断开始忽略，不当成用户被抢走音频。
    public static func ignoresInterruptionBegan(wasSuspended: Bool) -> Bool {
        wasSuspended
    }

    /// 打断结束后不自动恢复出声。
    public static var resumesAudibleAfterInterruption: Bool { false }

    public struct RouteDecision: Equatable, Sendable {
        public var mutes: Bool
        public var pauses: Bool

        public init(mutes: Bool, pauses: Bool) {
            self.mutes = mutes
            self.pauses = pauses
        }
    }

    /// 拔耳机转为静音，不暂停。其他路由变化不动播放。
    public static func routeDecision(_ reason: AudioRouteReason) -> RouteDecision {
        switch reason {
        case .oldDeviceUnavailable:
            return RouteDecision(mutes: true, pauses: false)
        case .other:
            return RouteDecision(mutes: false, pauses: false)
        }
    }
}

/// 音频转换的一步。顺序由 `AudioTransition.plan` 固定。
public enum AudioTransitionStep: Equatable, Sendable {
    case muteLane(PlaybackLaneRole)
    case muteAllLanes
    case setCategory(AudioSessionCategory, mode: AudioSessionMode?)
    case setActive(Bool, notifyOthersOnDeactivation: Bool)
    case unmuteLane(PlaybackLaneRole)
}

/// 要执行的音频意图。
public enum AudioIntent: Equatable, Sendable {
    case unmute(PlaybackLaneRole)
    case deactivate(AudioSessionPolicy.DeactivationReason)
    case interruptionBegan(wasSuspended: Bool)
    case interruptionEnded
    case route(AudioRouteReason)
}

/// 一次转换的步骤和结束后的运行时状态。`writesSettings == false` 时调用方不得把静音写进设置。
public struct AudioTransitionPlan: Equatable, Sendable {
    public var steps: [AudioTransitionStep]
    /// 结束后应出声的路。nil 表示全部静音；`preservesAudibleLane` 为真时忽略这个字段。
    public var audibleLane: PlaybackLaneRole?
    /// 为真表示不要改当前出声路（忽略的打断、打断结束、无关路由）。
    public var preservesAudibleLane: Bool
    /// 非焦点路：一律静音，并按 `MuteAudioPolicy` 拿掉音轨。
    public var detachAudio: [PlaybackLaneRole]
    public var writesSettings: Bool
    public var notice: String?
    public var pausesPlayback: Bool
    public var ignored: Bool
    public var resumesAudible: Bool
    /// 这次计划想变成出声。只有这时，类别或激活失败才要改写成「保持静音且不写设置」。
    public var attemptsAudible: Bool

    public init(
        steps: [AudioTransitionStep],
        audibleLane: PlaybackLaneRole?,
        preservesAudibleLane: Bool,
        detachAudio: [PlaybackLaneRole],
        writesSettings: Bool,
        notice: String?,
        pausesPlayback: Bool,
        ignored: Bool,
        resumesAudible: Bool,
        attemptsAudible: Bool
    ) {
        self.steps = steps
        self.audibleLane = audibleLane
        self.preservesAudibleLane = preservesAudibleLane
        self.detachAudio = detachAudio
        self.writesSettings = writesSettings
        self.notice = notice
        self.pausesPlayback = pausesPlayback
        self.ignored = ignored
        self.resumesAudible = resumesAudible
        self.attemptsAudible = attemptsAudible
    }
}

/// 出声 / 静音 / 打断 / 路由的步骤。失败时的最终状态用 `finished`。
public enum AudioTransition {
    public static func plan(
        intent: AudioIntent,
        focus: PlaybackLaneRole,
        currentlyAudible: PlaybackLaneRole?,
        lanes: [PlaybackLaneRole]
    ) -> AudioTransitionPlan {
        let detach = lanes.filter { $0 != focus }
        switch intent {
        case .unmute(let lane):
            return unmute(lane, focus: focus, currentlyAudible: currentlyAudible, detach: detach)
        case .deactivate(let reason):
            return silence(
                detach: detach,
                notifyOthers: AudioSessionPolicy.deactivatesNotifyingOthers(on: reason),
                writesSettings: reason == .mute,
                pausesPlayback: false
            )
        case .interruptionBegan(let wasSuspended):
            if AudioSessionPolicy.ignoresInterruptionBegan(wasSuspended: wasSuspended) {
                return hold(detach: detach, ignored: true)
            }
            return AudioTransitionPlan(
                steps: [.muteAllLanes],
                audibleLane: nil,
                preservesAudibleLane: false,
                detachAudio: detach,
                writesSettings: false,
                notice: nil,
                pausesPlayback: false,
                ignored: false,
                resumesAudible: false,
                attemptsAudible: false
            )
        case .interruptionEnded:
            return hold(
                detach: detach, ignored: false,
                resumesAudible: AudioSessionPolicy.resumesAudibleAfterInterruption
            )
        case .route(let reason):
            let decision = AudioSessionPolicy.routeDecision(reason)
            guard decision.mutes else { return hold(detach: detach, ignored: false, pausesPlayback: decision.pauses) }
            return silence(
                detach: detach,
                notifyOthers: true,
                writesSettings: true,
                pausesPlayback: decision.pauses
            )
        }
    }

    /// 类别和激活都成功则原样返回。想出声但任一步失败：去掉取消静音，全部保持静音，不写设置。
    public static func finished(
        _ plan: AudioTransitionPlan,
        categorySucceeded: Bool,
        activationSucceeded: Bool
    ) -> AudioTransitionPlan {
        guard plan.attemptsAudible, !categorySucceeded || !activationSucceeded else { return plan }
        var failed = plan
        failed.steps.removeAll { step in
            if case .unmuteLane = step { return true }
            return false
        }
        failed.audibleLane = nil
        failed.preservesAudibleLane = false
        failed.writesSettings = false
        failed.notice = AudioSessionPolicy.failureNotice
        failed.pausesPlayback = false
        failed.ignored = false
        failed.resumesAudible = false
        failed.attemptsAudible = false
        return failed
    }

    private static func unmute(
        _ lane: PlaybackLaneRole,
        focus: PlaybackLaneRole,
        currentlyAudible: PlaybackLaneRole?,
        detach: [PlaybackLaneRole]
    ) -> AudioTransitionPlan {
        guard lane == focus else {
            var steps: [AudioTransitionStep] = []
            if let currentlyAudible, currentlyAudible != focus {
                steps.append(.muteLane(currentlyAudible))
            }
            if !steps.contains(.muteLane(lane)) {
                steps.append(.muteLane(lane))
            }
            return AudioTransitionPlan(
                steps: steps,
                audibleLane: currentlyAudible == focus ? focus : nil,
                preservesAudibleLane: false,
                detachAudio: detach,
                writesSettings: false,
                notice: nil,
                pausesPlayback: false,
                ignored: false,
                resumesAudible: false,
                attemptsAudible: false
            )
        }
        var steps: [AudioTransitionStep] = []
        if let currentlyAudible {
            steps.append(.muteLane(currentlyAudible))
        }
        let audible = AudioSessionPolicy.configuration(audible: true)
        steps.append(.setCategory(audible.category, mode: audible.mode))
        steps.append(.setActive(true, notifyOthersOnDeactivation: false))
        steps.append(.unmuteLane(lane))
        return AudioTransitionPlan(
            steps: steps,
            audibleLane: lane,
            preservesAudibleLane: false,
            detachAudio: detach,
            writesSettings: true,
            notice: nil,
            pausesPlayback: false,
            ignored: false,
            resumesAudible: false,
            attemptsAudible: true
        )
    }

    private static func silence(
        detach: [PlaybackLaneRole],
        notifyOthers: Bool,
        writesSettings: Bool,
        pausesPlayback: Bool
    ) -> AudioTransitionPlan {
        let quiet = AudioSessionPolicy.configuration(audible: false)
        return AudioTransitionPlan(
            steps: [
                .muteAllLanes,
                .setCategory(quiet.category, mode: quiet.mode),
                .setActive(false, notifyOthersOnDeactivation: notifyOthers)
            ],
            audibleLane: nil,
            preservesAudibleLane: false,
            detachAudio: detach,
            writesSettings: writesSettings,
            notice: nil,
            pausesPlayback: pausesPlayback,
            ignored: false,
            resumesAudible: false,
            attemptsAudible: false
        )
    }

    private static func hold(
        detach: [PlaybackLaneRole],
        ignored: Bool,
        pausesPlayback: Bool = false,
        resumesAudible: Bool = false
    ) -> AudioTransitionPlan {
        AudioTransitionPlan(
            steps: [],
            audibleLane: nil,
            preservesAudibleLane: true,
            detachAudio: detach,
            writesSettings: false,
            notice: nil,
            pausesPlayback: pausesPlayback,
            ignored: ignored,
            resumesAudible: resumesAudible,
            attemptsAudible: false
        )
    }
}
