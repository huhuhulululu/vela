import Foundation

/// 停留时长记账：只累计「实际在播放且可见」的时间，换房间时结算上一个房间。
/// 播放失败被自动跳过的房间用户没看到画面，结算时不产生学习信号。
public struct DwellTracker: Equatable, Sendable {
    public struct Settled: Equatable, Sendable {
        public let room: Room
        public let dwell: TimeInterval
        /// 计入观看账本的秒数：`dwell` 去掉「暂停学习」期间的那一段（`setLedgerExcluded`）。
        public let ledgerSeconds: TimeInterval
        /// `dwell` 里焦点在这一路的秒数（§4.3）。单窗恒等于 `dwell`。
        public let focusedDwell: TimeInterval
        /// 本次观看真正开始的时刻（首次开始实际播放），没播放过为 nil。
        public let start: Date?
    }

    public private(set) var room: Room?
    private var accumulated: TimeInterval = 0
    private var activeSince: Date?
    private var failed = false
    private var discarded = false
    private var relatedDone = false
    /// 本次观看首次开始实际播放的时刻；暂停、缓冲后恢复不改它。实时提示判定与持久化账本共用这一口径。
    public private(set) var startedAt: Date?
    /// 已被排除出账本的累计秒数，与进行中的排除段起点（那时的累计值）。
    private var excluded: TimeInterval = 0
    private var exclusionMark: TimeInterval?
    /// 焦点是否在这一路。默认在（单窗恒如此）；双窗里由 Stage 层按焦点路写入，换房间时保持。
    private var focused = true
    private var focusedAccumulated: TimeInterval = 0
    private var focusedSince: Date?

    public init() {}

    /// 列表改动后是否要结算停留：当前房间换了，或来源换了（新来源的第一个房间恰好与旧房间同名时，
    /// 用户名没变，但旧来源下的停留必须按旧来源结算，新来源重新开始）。
    public static func shouldSettle(before: String?, after: String?, sourceChanged: Bool) -> Bool {
        before != after || (sourceChanged && after != nil)
    }

    /// 换到新房间（可为 nil）：返回上一个房间的结算；新房间从「不活跃」开始，等播放起来再 setActive。
    public mutating func switchTo(_ next: Room?, now: Date) -> Settled? {
        let done = settled(now: now)
        room = next
        accumulated = 0
        activeSince = nil
        failed = false
        discarded = false
        relatedDone = false
        startedAt = nil
        excluded = 0
        exclusionMark = nil
        focusedAccumulated = 0
        focusedSince = nil
        return done
    }

    /// 结束记账（退出应用）：结算当前房间并清空。
    public mutating func finish(now: Date) -> Settled? {
        switchTo(nil, now: now)
    }

    public var isActive: Bool { activeSince != nil }

    /// 当前是否在播放且可见。重复设置同一状态无副作用。
    public mutating func setActive(_ active: Bool, now: Date) {
        guard room != nil else { return }
        if active {
            if activeSince == nil {
                activeSince = now
                if focused { focusedSince = now }
            }
            if startedAt == nil { startedAt = now }
        } else if let since = activeSince {
            accumulated += max(0, now.timeIntervalSince(since))
            activeSince = nil
            closeFocusedSpan(now: now)
        }
    }

    /// 焦点进入或离开这一路。正在计时时把已走的秒数按旧口径切开；没在计时只改标志。重复设置同一值无副作用。
    public mutating func setFocused(_ next: Bool, now: Date) {
        guard next != focused else { return }
        if activeSince != nil {
            if next { focusedSince = now } else { closeFocusedSpan(now: now) }
        }
        focused = next
    }

    /// 焦点在这一路的实时秒数（含当前在播段）。
    public func focusedDwell(now: Date) -> TimeInterval {
        focusedAccumulated + (focusedSince.map { max(0, now.timeIntervalSince($0)) } ?? 0)
    }

    private mutating func closeFocusedSpan(now: Date) {
        if let since = focusedSince {
            focusedAccumulated += max(0, now.timeIntervalSince(since))
            focusedSince = nil
        }
    }

    /// 当前房间播放失败（将被自动跳过）：结算时不记信号。
    public mutating func markFailed() { failed = true }

    /// 丢弃当前房间的停留（例如用户屏蔽了它）：结算时不记任何信号，之后也不会被恢复。
    public mutating func discard() { discarded = true }

    public var isDiscarded: Bool { discarded }

    /// 排除段开关（暂停学习期间为开）：开着时这段观看不计入账本，关上后继续计。重复设置同一状态无副作用。
    public mutating func setLedgerExcluded(_ on: Bool, now: Date) {
        guard room != nil else { return }
        if on {
            if exclusionMark == nil { exclusionMark = dwell(now: now) }
        } else if let mark = exclusionMark {
            excluded += max(0, dwell(now: now) - mark)
            exclusionMark = nil
        }
    }

    /// 可计入账本的实时秒数（含当前在播段）；排除段进行中时冻结在进入排除段那一刻。
    public func ledgerDwell(now: Date) -> TimeInterval {
        let total = exclusionMark ?? dwell(now: now)
        return max(0, total - excluded)
    }

    /// 当前房间后来播放成功（例如用户重试）：恢复记信号。
    public mutating func clearFailure() { failed = false }

    public func dwell(now: Date) -> TimeInterval {
        accumulated + (activeSince.map { max(0, now.timeIntervalSince($0)) } ?? 0)
    }

    public enum RelatedPlan: Equatable, Sendable {
        /// 已不在该房间，或已触发过。
        case none
        /// 当前没在计时（暂停、缓冲、隐藏）：不必唤醒，等恢复计时后再排。
        case idle
        case wait(TimeInterval)
        case due
    }

    /// 热门关联的下一步。每个房间只触发一次（`markRelatedDone`）。
    public func relatedPlan(for username: String, now: Date) -> RelatedPlan {
        guard let room, !relatedDone, room.username.lowercased() == username.lowercased() else { return .none }
        let remaining = max(0, Preferences.relatedAfter - dwell(now: now))
        if remaining <= 0 { return .due }
        return isActive ? .wait(remaining) : .idle
    }

    public mutating func markRelatedDone() { relatedDone = true }

    private func settled(now: Date) -> Settled? {
        guard let room, !failed, !discarded else { return nil }
        return Settled(
            room: room, dwell: dwell(now: now), ledgerSeconds: ledgerDwell(now: now),
            focusedDwell: min(dwell(now: now), focusedDwell(now: now)), start: startedAt
        )
    }
}

/// 停留记账的来源归属：以「当前列表实际属于哪个来源」为准，而不是用户刚选中的来源。
public enum ListOrigin {
    /// 换房间时新房间所在列表的来源：列表正被整体替换（`installing` 非 nil）时是新来源，否则仍是已加载的来源。
    public static func dwell(loaded: FeedSource, installing: FeedSource?) -> FeedSource {
        installing ?? loaded
    }
}
