import Foundation

/// 音频会话类别。对应系统的 ambient / playback，Core 不引用 AVFoundation。
/// 修订 A2 之后静音不再用 `.ambient`。枚举留着，避免把已经放弃的类别写没；新代码不要再选它。
public enum AudioSessionCategory: Equatable, Sendable {
    case ambient
    case playback
}

/// 会话选项。对应系统的 category options，Core 不引用 AVFoundation。
public struct AudioSessionOptions: OptionSet, Equatable, Sendable {
    public let rawValue: UInt

    public init(rawValue: UInt) {
        self.rawValue = rawValue
    }

    /// 与其他 App 混音，不打断对方。静音（含静音画中画）使用。
    public static let mixWithOthers = AudioSessionOptions(rawValue: 1 << 0)
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
        public var options: AudioSessionOptions

        public init(
            category: AudioSessionCategory,
            mode: AudioSessionMode?,
            options: AudioSessionOptions = []
        ) {
            self.category = category
            self.mode = mode
            self.options = options
        }
    }

    /// 出声：`.playback` 加 `.moviePlayback`，不混音。
    /// 静音：同样是 `.playback` 加 `.moviePlayback`（画中画要求播放类别），选项 `.mixWithOthers`。
    /// 修订 A2 用这组替换原来的「静音用 `.ambient`」。
    public static func configuration(audible: Bool) -> Configuration {
        audible
            ? Configuration(category: .playback, mode: .moviePlayback, options: [])
            : Configuration(category: .playback, mode: .moviePlayback, options: .mixWithOthers)
    }

    /// 再静音时画中画仍在播：保持会话活跃，只把选项换成混音。否则要停用并通知对方恢复。
    public static func keepsSessionActiveWhenMuting(pictureInPicturePlaying: Bool) -> Bool {
        pictureInPicturePlaying
    }

    public enum DeactivationReason: Equatable, Sendable {
        case mute
        case background
        case lastLaneStopped
    }

    /// 再静音、进后台、最后一路停播都要 `setActive(false, .notifyOthersOnDeactivation)`。
    /// 画中画仍在播时的再静音与拔耳机转静音不走这里，见 `keepsSessionActiveWhenMuting`。
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
    /// 非空的会话选项。静音是 `.mixWithOthers`。出声不混音，不发这一步。
    case setSessionOptions(AudioSessionOptions)
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
    /// 首次起播前按当前静音状态落实会话（§3.3、7b）：静音时播放类别 + 混音并激活，出声时同取消静音。不写设置。
    case prepare(muted: Bool)
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
    /// 为真表示这次不要 `setActive(false)`。再静音且画中画仍在播时，只切换选项。
    public var keepsSessionActive: Bool

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
        attemptsAudible: Bool,
        keepsSessionActive: Bool = false
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
        self.keepsSessionActive = keepsSessionActive
    }
}

/// 出声 / 静音 / 打断 / 路由的步骤。失败时的最终状态用 `finished`。
public enum AudioTransition {
    public static func plan(
        intent: AudioIntent,
        focus: PlaybackLaneRole,
        currentlyAudible: PlaybackLaneRole?,
        lanes: [PlaybackLaneRole],
        pictureInPictureActive: Bool = false
    ) -> AudioTransitionPlan {
        let detach = lanes.filter { $0 != focus }
        switch intent {
        case .unmute(let lane):
            return unmute(lane, focus: focus, currentlyAudible: currentlyAudible, detach: detach)
        case .deactivate(let reason):
            let keepSession = reason == .mute && AudioSessionPolicy.keepsSessionActiveWhenMuting(
                pictureInPicturePlaying: pictureInPictureActive
            )
            return silence(
                detach: detach,
                notifyOthers: AudioSessionPolicy.deactivatesNotifyingOthers(on: reason),
                writesSettings: reason == .mute,
                pausesPlayback: false,
                keepSessionActive: keepSession
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
        case .prepare(let muted):
            return prepare(muted: muted, focus: focus, detach: detach)
        case .route(let reason):
            let decision = AudioSessionPolicy.routeDecision(reason)
            guard decision.mutes else { return hold(detach: detach, ignored: false, pausesPlayback: decision.pauses) }
            // 拔耳机转静音与再静音同一规则：画中画仍在播时保持会话激活，只切混音选项。
            return silence(
                detach: detach,
                notifyOthers: true,
                writesSettings: true,
                pausesPlayback: decision.pauses,
                keepSessionActive: AudioSessionPolicy.keepsSessionActiveWhenMuting(
                    pictureInPicturePlaying: pictureInPictureActive
                )
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

    private static func prepare(muted: Bool, focus: PlaybackLaneRole, detach: [PlaybackLaneRole]) -> AudioTransitionPlan {
        guard muted else {
            // 出声：与取消静音同样的步骤；设置里本来就是出声，不再写一遍。
            var plan = unmute(focus, focus: focus, currentlyAudible: nil, detach: detach)
            plan.writesSettings = false
            return plan
        }
        let quiet = AudioSessionPolicy.configuration(audible: false)
        var steps: [AudioTransitionStep] = [.muteAllLanes, .setCategory(quiet.category, mode: quiet.mode)]
        if !quiet.options.isEmpty {
            steps.append(.setSessionOptions(quiet.options))
        }
        steps.append(.setActive(true, notifyOthersOnDeactivation: false))
        return AudioTransitionPlan(
            steps: steps,
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
    }

    private static func silence(
        detach: [PlaybackLaneRole],
        notifyOthers: Bool,
        writesSettings: Bool,
        pausesPlayback: Bool,
        keepSessionActive: Bool = false
    ) -> AudioTransitionPlan {
        let quiet = AudioSessionPolicy.configuration(audible: false)
        var steps: [AudioTransitionStep] = [
            .muteAllLanes,
            .setCategory(quiet.category, mode: quiet.mode)
        ]
        if !quiet.options.isEmpty {
            steps.append(.setSessionOptions(quiet.options))
        }
        if !keepSessionActive {
            steps.append(.setActive(false, notifyOthersOnDeactivation: notifyOthers))
        }
        return AudioTransitionPlan(
            steps: steps,
            audibleLane: nil,
            preservesAudibleLane: false,
            detachAudio: detach,
            writesSettings: writesSettings,
            notice: nil,
            pausesPlayback: pausesPlayback,
            ignored: false,
            resumesAudible: false,
            attemptsAudible: false,
            keepsSessionActive: keepSessionActive
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
