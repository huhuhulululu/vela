import Foundation

/// 常看收藏提示何时算「问过」、何时可以显示。
public enum SuggestEnd: Equatable, Sendable {
    /// 点了「收藏」。
    case accepted
    /// 点了关闭。
    case closed
    /// 超时自己消失。
    case timedOut
    /// 换房或被别的横幅替换而撤下。
    case withdrawn
}

public enum SuggestRules {
    /// 只有用户点了「收藏」或明确关闭才记为已提示（`WatchLedger.markingSuggested`）；
    /// 超时消失、换房撤下都不算，这位主播以后还会被问。
    public static func countsAsAsked(_ end: SuggestEnd) -> Bool {
        end == .accepted || end == .closed
    }

    /// 能不能显示并开始计时：点击穿透期间（点击会落到后面的窗口，按钮点不到）不显示，等指针回到窗口；
    /// 本次停留里已经超时消失过的也不再重复弹。
    public static func canPresent(pointerCanClick: Bool, snoozed: Bool) -> Bool {
        pointerCanClick && !snoozed
    }
}

/// 列表为空时的说明：按原因区分，用户知道该去哪里处理。
public enum EmptyListCopy {
    public static func message(
        catalogError: String?, query: RoomQuery, catalog: [Room], source: FeedSource, favoriteCount: Int
    ) -> String {
        if let catalogError { return catalogError }
        guard !catalog.isEmpty else {
            if source == .favorites {
                return favoriteCount == 0 ? "还没有收藏，在房间里按 ⌘D 收藏" : "收藏的人现在都不在线"
            }
            return "目录里现在没有房间，可以稍后重试"
        }
        let text = query.text.trimmingCharacters(in: .whitespacesAndNewlines)
        if !text.isEmpty {
            var withoutText = query
            withoutText.text = ""
            if catalog.contains(where: withoutText.matches) { return "没有匹配「\(text)」的房间。回车搜索全站" }
        }
        let unblocked = catalog.filter { !query.blocked.contains($0.username.lowercased()) }
        if unblocked.isEmpty { return "目录里的房间都在屏蔽名单里" }
        let allowed = unblocked.filter { room in query.only.map { $0.contains(room.username.lowercased()) } ?? true }
        if allowed.isEmpty { return "收藏的人现在都不在线" }
        if !query.hidden.isEmpty, allowed.allSatisfy({ RoomQuery.isHidden($0, by: query.hidden) }) {
            return "目录里的房间都被「我的口味」里不想看的标签过滤掉了"
        }
        if query.publicOnly, allowed.allSatisfy({ $0.show != .publicShow }) {
            return "目录里没有公开的房间，可以在筛选里关掉「仅公开」"
        }
        return "目录里暂时没有符合条件的房间"
    }
}

/// 弹层尺寸随窗口高度收缩（窗口最小 360 高，固定 640 的弹层会把底部按钮挤出窗口）。
public enum SheetLayout {
    public static let challengeMaxHeight = 640.0
    public static let challengeMinHeight = 300.0
    /// 确认页高度：比窗口矮一点（上下各留约 12 点），不超过 640，也不矮于 300。
    public static func challengeHeight(windowHeight: Double) -> Double {
        min(max(windowHeight - 24, challengeMinHeight), challengeMaxHeight)
    }
}
