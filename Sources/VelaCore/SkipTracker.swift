import Foundation

/// 自动跳过的纪律：沿用户最后一次手动划动的方向，连续自动跳过到上限就停下。
public struct SkipTracker: Equatable, Sendable {
    public static let limit = 3
    /// 失败原因在屏幕上至少停留这么久，才会自动跳走。
    public static let noticeSeconds = 1.6

    public private(set) var direction = 1
    public private(set) var count = 0

    public init() {}

    /// 用户手动划动：记住方向，连续计数清零。
    public mutating func noteManual(_ delta: Int) {
        if delta != 0 { direction = delta > 0 ? 1 : -1 }
        count = 0
    }

    /// 登记一次自动跳过；已达上限返回 false（调用方应停下并提示重试）。
    public mutating func registerAuto() -> Bool {
        guard count < Self.limit else { return false }
        count += 1
        return true
    }

    /// 播放成功或用户主动重试：连续计数清零，方向保留。
    public mutating func reset() { count = 0 }

    public enum AutoOutcome: Equatable, Sendable {
        /// 跳到了下一间。
        case moved
        /// 已到列表尽头（`move` 失败）：不计入连续次数，保留原失败原因。
        case endOfList
        /// 确实已连续自动跳过 `limit` 间，停下。
        case limitReached

        /// 停下时的文案：尽头保留原因并说明已到尽头；只有真到上限才说「连续几个房间」。跳走了返回 nil。
        public func notice(keeping reason: String?) -> String? {
            switch self {
            case .moved:
                return nil
            case .endOfList:
                let kept = (reason ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
                return kept.isEmpty ? "已到列表尽头" : "\(kept)，已到列表尽头"
            case .limitReached:
                return "连续几个房间都无法播放"
            }
        }
    }

    /// 尝试一次自动跳过：已达上限不调用 `move`；`move` 返回 false（无处可跳）时不占用计数。
    public mutating func attemptAuto(move: () -> Bool) -> AutoOutcome {
        guard count < Self.limit else { return .limitReached }
        guard move() else { return .endOfList }
        count += 1
        return .moved
    }
}
