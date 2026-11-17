import Foundation

/// 画中画纯函数。输入是平台、场景和开关，输出每一路能不能播、中继留哪一路、会话怎么设、要不要关掉画中画。
/// 不引用 AppKit、UIKit、AVFoundation。mac 的遮挡、最小化、老板键、锁屏都用布尔传入。
public enum PiPPolicy {
    public enum Platform: Equatable, Sendable {
        case mac
        case phone
    }

    /// 画中画控制器状态。`.starting` 视同已经进入，`.stopping` 视同正在结束。
    public enum State: Equatable, Sendable {
        case none
        case starting
        case active
        case stopping
    }

    public struct Input: Equatable, Sendable {
        public var platform: Platform
        public var scene: PhoneScenePhase
        public var pictureInPicture: State
        /// 设置里的「回桌面自动进入」。产品默认开，这里照实传入。
        public var autoEnterEnabled: Bool
        public var muted: Bool
        public var focus: PlaybackLaneRole
        public var lanes: [PlaybackLaneRole]
        /// 焦点路是不是正在播放。只有「正在播放」才会在回桌面时自动进入。
        /// 计划的输入清单没有单独这一项；7a 写了正在播放才自动进入，所以放在这里。
        public var focusPlaying: Bool
        public var bossKey: Bool
        /// 锁屏和系统休眠效果相同，合成一个输入。
        public var lockScreenOrSystemSleep: Bool
        /// 遮挡和最小化效果相同，拆开是为了两条场景能各自传入。
        public var windowMinimized: Bool
        public var windowOccluded: Bool

        public init(
            platform: Platform,
            scene: PhoneScenePhase,
            pictureInPicture: State,
            autoEnterEnabled: Bool,
            muted: Bool,
            focus: PlaybackLaneRole,
            lanes: [PlaybackLaneRole],
            focusPlaying: Bool,
            bossKey: Bool,
            lockScreenOrSystemSleep: Bool,
            windowMinimized: Bool,
            windowOccluded: Bool
        ) {
            self.platform = platform
            self.scene = scene
            self.pictureInPicture = pictureInPicture
            self.autoEnterEnabled = autoEnterEnabled
            self.muted = muted
            self.focus = focus
            self.lanes = lanes
            self.focusPlaying = focusPlaying
            self.bossKey = bossKey
            self.lockScreenOrSystemSleep = lockScreenOrSystemSleep
            self.windowMinimized = windowMinimized
            self.windowOccluded = windowOccluded
        }
    }

    public struct LaneDecision: Equatable, Sendable {
        public var role: PlaybackLaneRole
        public var playbackAllowed: Bool

        public init(role: PlaybackLaneRole, playbackAllowed: Bool) {
            self.role = role
            self.playbackAllowed = playbackAllowed
        }
    }

    /// 现在会话该活跃还是停用。停用带 `.notifyOthersOnDeactivation`。
    /// 和「这一路画面能不能播」不是一回事：前台静音、又没在画中画时，画面可以播，会话仍按再静音停用。
    public enum SessionActivation: Equatable, Sendable {
        case keepActive
        case deactivateNotifyingOthers
    }

    public struct Decision: Equatable, Sendable {
        public var lanes: [LaneDecision]
        public var keepsRelayListening: Bool
        /// 非 nil 时源表只留这一路。nil 表示本策略不要求收成一路。
        public var retainedRelaySource: PlaybackLaneRole?
        public var session: AudioSessionPolicy.Configuration
        /// 若此刻再静音，画中画（或即将自动进入）仍要播，就保持会话活跃，只切换选项。
        public var keepsSessionActiveOnMute: Bool
        public var sessionActivation: SessionActivation
        public var shouldClosePictureInPicture: Bool

        public init(
            lanes: [LaneDecision],
            keepsRelayListening: Bool,
            retainedRelaySource: PlaybackLaneRole?,
            session: AudioSessionPolicy.Configuration,
            keepsSessionActiveOnMute: Bool,
            sessionActivation: SessionActivation,
            shouldClosePictureInPicture: Bool
        ) {
            self.lanes = lanes
            self.keepsRelayListening = keepsRelayListening
            self.retainedRelaySource = retainedRelaySource
            self.session = session
            self.keepsSessionActiveOnMute = keepsSessionActiveOnMute
            self.sessionActivation = sessionActivation
            self.shouldClosePictureInPicture = shouldClosePictureInPicture
        }

        public func playbackAllowed(_ role: PlaybackLaneRole) -> Bool {
            lanes.first { $0.role == role }?.playbackAllowed ?? false
        }
    }

    public static func decide(_ input: Input) -> Decision {
        let privacyHide = input.platform == .mac && (input.bossKey || input.lockScreenOrSystemSleep)
        let engaged = input.pictureInPicture == .active || input.pictureInPicture == .starting
        // 自动进入只发生在手机回桌面、开关开着、还没进画中画、焦点路正在播。mac 的隐私隐藏优先。
        let autoEnter = input.platform == .phone
            && input.scene == .background
            && input.autoEnterEnabled
            && input.pictureInPicture == .none
            && input.focusPlaying
            && !privacyHide
        let continueFocus = !privacyHide && (engaged || autoEnter)

        var roles = input.lanes
        if !roles.contains(input.focus) {
            roles.append(input.focus)
        }
        let laneDecisions = roles.map { role in
            LaneDecision(
                role: role,
                playbackAllowed: allows(
                    role, input: input, privacyHide: privacyHide,
                    engaged: engaged, continueFocus: continueFocus
                )
            )
        }

        let keepsRelayListening: Bool
        let retainedRelaySource: PlaybackLaneRole?
        if privacyHide || (input.scene == .background && !continueFocus) {
            keepsRelayListening = false
            retainedRelaySource = nil
        } else if continueFocus || engaged {
            keepsRelayListening = true
            retainedRelaySource = input.focus
        } else {
            // 前台、也没在画中画：不要求拆监听，也不收成一路。
            keepsRelayListening = true
            retainedRelaySource = nil
        }

        let session = AudioSessionPolicy.configuration(audible: !input.muted)
        let sessionActivation: SessionActivation
        if continueFocus || (!input.muted && laneDecisions.contains(where: { $0.playbackAllowed })) {
            sessionActivation = .keepActive
        } else {
            sessionActivation = .deactivateNotifyingOthers
        }

        return Decision(
            lanes: laneDecisions,
            keepsRelayListening: keepsRelayListening,
            retainedRelaySource: retainedRelaySource,
            session: session,
            keepsSessionActiveOnMute: continueFocus,
            sessionActivation: sessionActivation,
            shouldClosePictureInPicture: input.platform == .mac
                && privacyHide
                && input.pictureInPicture != .none
        )
    }

    /// 老板键、锁屏、系统休眠只在 mac 上停播并关画中画。遮挡和最小化不参与这个判断。
    /// 进后台时只有继续的那一路能播。已经在画中画时，另一路也不播。
    private static func allows(
        _ role: PlaybackLaneRole,
        input: Input,
        privacyHide: Bool,
        engaged: Bool,
        continueFocus: Bool
    ) -> Bool {
        if privacyHide { return false }
        if input.scene == .background {
            return continueFocus && role == input.focus
        }
        if engaged {
            return role == input.focus
        }
        return true
    }
}
