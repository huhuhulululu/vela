import Foundation

/// 「已请求外面重新解析地址、新项到来前不接着播旧项」的待刷新状态。
/// 所有让刷新结束的路径（新项起播、播放器停止、刷新失败、确认页、任务放弃）都必须清掉它，
/// 否则旧项被永远挡住，暂停与恢复全部失效。
public struct RefreshTracker: Equatable, Sendable {
    public private(set) var pending = false

    public init() {}

    public mutating func begin() { pending = true }

    /// 新播放项起播或播放器停止：刷新自然结束。
    public mutating func clear() { pending = false }

    /// 刷新没有换来新项就结束了：仍在待刷新时清掉并返回要展示的原因；已结束则返回 nil（不重复报）。
    public mutating func fail(reason: String?) -> String? {
        guard pending else { return nil }
        pending = false
        return reason ?? "画面没能刷新"
    }

    /// 刷新任务收尾时，这次结果是否归自己：期间被更新的起播取代（代次多走了不止一步）就不归自己，
    /// 由取代者负责收尾。
    public static func ownsOutcome(taskGeneration: Int, currentGeneration: Int) -> Bool {
        currentGeneration <= taskGeneration + 1
    }
}

/// 播放失败文案的生命周期：失败文案由播放器的失败状态带来，恢复后要一并清掉；
/// 其他来源的提示（连续跳过、解析失败等）不动。
public enum PlaybackNoteRule {
    public static func clearOnRecovery(note: String?, playerNote: String?) -> Bool {
        guard let note, let playerNote else { return false }
        return note == playerNote
    }
}

/// 翻页完成后是否要继续补拉：列表仍无可见房间，且这次列表代次没有变。
public enum LoadFollowUp {
    public static func needsFill(tokenUnchanged: Bool, visibleRooms: Int) -> Bool {
        tokenUnchanged && visibleRooms == 0
    }
}
