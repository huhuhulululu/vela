import Foundation

/// 站点质询的连续次数闸门：每次撞上质询（`StreamDecodeError.challenge` / `CatalogFailure.challenge`）登记一次，
/// 判定这次还要不要弹确认页。连续次数超过上限，或距上一次质询太近（刚点过「已完成」又被拦），
/// 建议不再弹页，改为提示并跳过，避免确认页循环。成功取到结果时调用 `reset`。
/// 纯值类型，时间由参数传入。
public struct ChallengeGate: Equatable, Sendable {
    /// 连续质询最多弹几次确认页；第 `limit + 1` 次起不再弹。
    public static let limit = 2
    /// 距上一次质询不到这么久又撞上，视为通关没生效，不再弹页。
    public static let repeatWindow: TimeInterval = 20
    /// 距上一次质询超过这么久，连续计数从头算（之前的质询已是很久以前的事）。
    public static let resetAfter: TimeInterval = 600

    public enum Decision: Equatable, Sendable {
        /// 弹确认页。
        case present
        /// 不再弹页：提示并跳过（或停在失败态）。
        case suppress
    }

    public private(set) var consecutive = 0
    public private(set) var lastAt: Date?

    public init() {}

    /// 登记一次质询并给出建议。被建议不弹页的那次也计入连续次数。
    public mutating func register(now: Date) -> Decision {
        let previous = lastAt
        if let previous, now.timeIntervalSince(previous) >= Self.resetAfter { consecutive = 0 }
        consecutive += 1
        lastAt = now
        if consecutive > Self.limit { return .suppress }
        if consecutive > 1, let previous, now.timeIntervalSince(previous) < Self.repeatWindow { return .suppress }
        return .present
    }

    /// 解析或取目录成功（不再被质询）：清零。
    public mutating func reset() {
        consecutive = 0
        lastAt = nil
    }
}

/// 确认页完成后要接着做的事。
public enum ChallengeResume: Equatable, Sendable {
    case reload
    case favorites
    /// 重跑被质询打断的全站搜索（搜索词）。
    case siteSearch(String)

    /// 取消全站搜索时清掉属于它的恢复意图（其余意图保留），免得之后别处的通关又重搜旧词。
    public func droppingSiteSearch() -> ChallengeResume? {
        if case .siteSearch = self { return nil }
        return self
    }

    /// 多个发起者先后请求恢复时取「需要做得最多」的：重载会重装列表并顺带重查收藏，优先；
    /// 其次是重跑搜索（装入搜索结果）；只补查收藏最轻。同为搜索时取较新的词。
    public func merging(_ new: ChallengeResume) -> ChallengeResume {
        switch (self, new) {
        case (.reload, _), (_, .reload): return .reload
        case (_, .siteSearch): return new
        case (.siteSearch, .favorites): return self
        default: return .favorites
        }
    }
}
