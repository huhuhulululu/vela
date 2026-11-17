import Foundation

/// 房间暂时离开（`away`）时的纪律：不立刻自动跳过，在当前房间停留时按保守间隔只重试一次解析；
/// 重试后仍离开就保持提示，由用户决定重试或换房。换到别的房间、或成功起播后清零。
/// 纯值类型；延迟任务由调用方用一次性任务实现，切房 / 暂停时取消。
public struct AwayRetry: Equatable, Sendable {
    /// 第一次撞上离开后，隔这么久再解析一次。
    public static let delay: TimeInterval = 60
    /// 等待自动重试期间的文案。
    public static let waitingNote = "暂时离开，稍后自动重试"
    /// 已自动重试过一次仍离开：不再自动重试。
    public static let heldNote = "暂时离开，可稍后手动重试或换个房间"

    public enum Decision: Equatable, Sendable {
        /// 隔 `after` 秒再解析一次。
        case retryLater(after: TimeInterval)
        /// 已重试过：保持提示，不再自动重试。
        case hold

        public var note: String {
            switch self {
            case .retryLater: return AwayRetry.waitingNote
            case .hold: return AwayRetry.heldNote
            }
        }
    }

    public private(set) var room: String?
    public private(set) var retried = false

    public init() {}

    /// 起播一个房间时调用：与记录的房间不同（换房了）就清零。
    public mutating func enter(_ username: String) {
        if let room, Curation.key(room) == Curation.key(username) { return }
        clear()
    }

    /// 解析结果为离开：同一房间还没自动重试过就给出延迟重试，已重试过则保持提示。
    /// 只给建议、不记次数：重试排程被取消（暂停等）的那次不消耗，真正开始重试时由 `beginRetry` 记。
    public mutating func onAway(_ username: String) -> Decision {
        if room.map(Curation.key) != Curation.key(username) {
            room = username
            retried = false
        }
        return retried ? .hold : .retryLater(after: Self.delay)
    }

    /// 延迟到点、真正开始自动重试解析时调用：记一次，之后再离开就保持提示。
    public mutating func beginRetry() { retried = true }

    /// 播放门控关闭（暂停等）时不建自动重试：把延迟重试降为保持提示，由用户恢复后手动重试。
    public static func gated(_ decision: Decision, playbackAllowed: Bool) -> Decision {
        if case .retryLater = decision, !playbackAllowed { return .hold }
        return decision
    }

    /// 等待提示在自动重试被取消（暂停等）后换成手动重试文案；其他提示原样。
    public static func noteAfterCancel(_ note: String?) -> String? {
        switch note {
        case waitingNote: return heldNote
        case EndpointBackoff.waitingNote: return EndpointBackoff.heldNote
        default: return note
        }
    }

    /// 成功起播：清零，之后再离开可以再自动重试一次。
    public mutating func clear() {
        room = nil
        retried = false
    }

    /// 延迟到点时是否还该重试：仍是同一房间、代次没变、允许播放、任务没被取消。
    public static func shouldFire(
        scheduledRoom: String, currentRoom: String?,
        scheduledGeneration: Int, currentGeneration: Int,
        playbackAllowed: Bool, cancelled: Bool
    ) -> Bool {
        guard !cancelled, playbackAllowed, scheduledGeneration == currentGeneration, let currentRoom else { return false }
        return Curation.key(currentRoom) == Curation.key(scheduledRoom)
    }
}

/// 等待中的自动重试排程：播放门控变为不允许（暂停、老板键、窗口不可见等）时作废，
/// 之后即使在到点前恢复也不再触发（恢复后由用户手动重试）。纯值类型；延迟任务本身由调用方持有并随之取消。
public struct AwayRetryTimer: Equatable, Sendable {
    public private(set) var pending = false

    public init() {}

    public mutating func schedule() { pending = true }

    public mutating func clear() { pending = false }

    /// 播放门控变化：不允许且有排程时作废，返回 true 表示刚作废（调用方据此取消任务并更新提示）。
    public mutating func gateChanged(allowed: Bool) -> Bool {
        guard !allowed, pending else { return false }
        pending = false
        return true
    }
}
