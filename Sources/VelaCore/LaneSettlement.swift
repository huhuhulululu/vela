import Foundation

/// 竖屏多路的一路。单窗只用 `.primary`。与 `LaneID` 一一对应（`LaneID.role` / `PlaybackLaneRole.laneID`）。
public enum PlaybackLaneRole: Equatable, Sendable, Hashable, CaseIterable {
    case primary
    case secondary
    case tertiary

    public var laneID: LaneID {
        switch self {
        case .primary: return .primary
        case .secondary: return .secondary
        case .tertiary: return .tertiary
        }
    }

    /// 日志里的路标签。
    public var label: String {
        switch self {
        case .primary: return "primary"
        case .secondary: return "secondary"
        case .tertiary: return "tertiary"
        }
    }
}

/// 一路停留的结算。学习用加权秒，每日命中率只用焦点秒，账本记加权秒。
/// 焦点段权重 1.0，非焦点段 0.5。每路带来源，不跟另一路串。
public struct LaneSettlement: Equatable, Sendable {
    /// 一段停留里焦点在或不在这一路的时间。
    public struct Span: Equatable, Sendable {
        public var seconds: TimeInterval
        public var focused: Bool

        public init(seconds: TimeInterval, focused: Bool) {
            self.seconds = seconds
            self.focused = focused
        }
    }

    public enum EndReason: Equatable, Sendable {
        case swipe
        case autoSkip
        case block
        case background
        case dualOff
        case sourceChange
        case failed
        case notInterested
    }

    public let lane: PlaybackLaneRole
    public let username: String
    public let source: FeedSource
    public let rawSeconds: TimeInterval
    public let weightedSeconds: TimeInterval
    public let focusedSeconds: TimeInterval
    public let endReason: EndReason
    public let userInitiated: Bool

    /// 这一段要不要进口味学习。弱落空（不足 3 秒）只在划走时记；满 3 秒时加权秒若仍算落空则不记
    /// （非焦点时间折半后不该把一次观看判成落空）。
    public var learnsTaste: Bool {
        if rawSeconds < Preferences.skipBelow { return endReason == .swipe }
        return Feedback.grade(watched: weightedSeconds).miss <= 0
    }

    /// 学习用的秒数；`learnsTaste` 为假时不要使用。不足 3 秒用原始秒，其余用加权秒。
    public var learnSeconds: TimeInterval {
        rawSeconds < Preferences.skipBelow ? rawSeconds : weightedSeconds
    }

    /// 这一段要不要进每日命中率。没有焦点时间不进；`.background` 与 `.dualOff` 不记落空，够长的命中仍然计。
    public var countsDaily: Bool {
        if focusedSeconds <= 0 { return false }
        if Self.dropsMisses(endReason), Feedback.dailyOutcome(watched: focusedSeconds) == false { return false }
        return true
    }

    /// 每日统计用的秒数（只含焦点时间）；`countsDaily` 为假时不要使用。
    public var dailySeconds: TimeInterval { focusedSeconds }

    /// 这两种结束不算用户划走。回前台后另开一段停留。
    public var resumesAsNewDwell: Bool {
        endReason == .background || endReason == .dualOff
    }

    /// 热门关联只看焦点路。
    public static func tracksHotRelated(isFocusLane: Bool) -> Bool {
        isFocusLane
    }

    public static func settle(
        lane: PlaybackLaneRole,
        username: String,
        source: FeedSource,
        spans: [Span],
        endReason: EndReason,
        userInitiated: Bool
    ) -> LaneSettlement {
        let raw = spans.reduce(0.0) { $0 + finite($1.seconds) }
        let focused = min(raw, spans.reduce(0.0) { $0 + ($1.focused ? finite($1.seconds) : 0) })
        let weighted = focused + (raw - focused) * 0.5
        return LaneSettlement(
            lane: lane,
            username: username,
            source: source,
            rawSeconds: raw,
            weightedSeconds: weighted,
            focusedSeconds: focused,
            endReason: endReason,
            userInitiated: userInitiated
        )
    }

    /// 用本路自己的来源记账。学习与每日统计分开判断，不要记的一边传 nil（仍标记「已看过」）。
    public func recording(into taste: Taste, room: Room, now: Date, rarity: TagRarity? = nil) -> Taste {
        taste.recording(
            room: room,
            learnSeconds: learnsTaste ? learnSeconds : nil,
            daily: countsDaily ? dailySeconds : nil,
            source: source, now: now, rarity: rarity
        )
    }

    /// 观看账本记加权秒，不记原始秒。
    public func recording(into ledger: WatchLedger, start: TimeInterval, now: TimeInterval) -> WatchLedger {
        ledger.recording(username: username, seconds: weightedSeconds, start: start, now: now)
    }

    private static func dropsMisses(_ reason: EndReason) -> Bool {
        reason == .background || reason == .dualOff
    }

    private static func finite(_ seconds: TimeInterval) -> TimeInterval {
        seconds.isFinite ? max(0, seconds) : 0
    }
}
