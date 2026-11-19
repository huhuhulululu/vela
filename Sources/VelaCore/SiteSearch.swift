import Foundation

/// 全站搜索：筛选面板的搜索框本地没有匹配时，回车用站点列表接口的 `keywords` 查一页，
/// 结果作为临时列表浏览；清空搜索回到原来源。打字过程中不发请求，只在回车时查。
public enum SiteSearch {
    /// 一次只取一页（站点单页上限）。
    public static let pageSize = CatalogQuery.maxPageSize
    /// 搜索词最长字符数，超出截断。
    public static let maxLength = 64

    /// 回车时做什么。
    public enum Submit: Equatable, Sendable {
        /// 沿用本地处理（直达用户名或本地过滤）。
        case local
        /// 用这个词查全站。
        case remote(String)
        /// 什么都不做（空白，或与正在显示的全站搜索词相同）。
        case ignore
    }

    /// 文字变化时做什么。
    public enum Edit: Equatable, Sendable {
        /// 不在全站搜索里：照常本地过滤。
        case filter
        /// 在全站搜索里继续打字：不发请求，也不过滤临时列表，等回车。
        case keep
        /// 在全站搜索里清空了搜索框：回到原来源。
        case exit
    }

    /// 全站搜索落空或失败后的善后。
    public enum MissRecovery: Equatable, Sendable {
        /// 已有上一次全站搜索：先结束它、换回原来源列表，再决定直达或提示（不改写上一次的临时列表）。
        case restoreSource
        case none
    }

    public static func missRecovery(active: String?) -> MissRecovery { active == nil ? .none : .restoreSource }

    /// 落空后的最终动作。
    public enum MissOutcome: Equatable, Sendable {
        /// 恢复原来源期间已被新的操作取代：什么都不做。
        case abandon
        case direct(Room)
        case notify(String)
    }

    /// 落空后直达还是提示；`stillCurrent` 是恢复原来源之后重新校验操作代次的结果。
    public static func missOutcome(
        keyword: String, fallback: Room?, fallbackBlocked: Bool, failed: Bool, stillCurrent: Bool
    ) -> MissOutcome {
        guard stillCurrent else { return .abandon }
        if let fallback, !fallbackBlocked { return .direct(fallback) }
        return .notify(failed ? "全站搜索失败，稍后再试" : "全站没有找到「\(keyword)」")
    }

    /// 去掉首尾空白、合并中间空白、截断到上限；空白返回 nil。
    public static func normalized(_ raw: String?) -> String? {
        guard let raw else { return nil }
        let words = raw.split(whereSeparator: { $0.isWhitespace }).joined(separator: " ")
        let clipped = String(words.prefix(maxLength))
        return clipped.isEmpty ? nil : clipped
    }

    /// 回车规则：正在显示全站搜索时，换了词就再查一次；否则本地有匹配沿用本地，没有才查全站。
    /// `localMatches` 是按当前搜索词过滤后的可见房间数。
    public static func onSubmit(text: String, localMatches: Int, active: String?) -> Submit {
        guard let keyword = normalized(text) else { return .ignore }
        if let active {
            return keyword.lowercased() == active.lowercased() ? .ignore : .remote(keyword)
        }
        return localMatches > 0 ? .local : .remote(keyword)
    }

    /// 打字规则：只有回车才发请求。
    public static func onEdit(text: String, active: String?) -> Edit {
        guard active != nil else { return .filter }
        return normalized(text) == nil ? .exit : .keep
    }

    /// 搜索框被清空（空白）时，在途的全站搜索要取消并作废代次（首次搜索在途时 `active` 仍是 nil，
    /// `onEdit` 走 `.filter`，也不能放过）。
    public static func cancelsInflight(text: String) -> Bool { normalized(text) == nil }

    /// 全站搜索的请求：只带搜索词与分页，不叠加性别、地区、标签。
    public static func query(_ keyword: String) -> CatalogQuery {
        CatalogQuery(limit: pageSize, offset: 0, keywords: keyword)
    }

    /// 停留归属：全站搜索的临时列表不是画像推荐出来的，「猜你喜欢」下按热门记
    /// （证据减半、不进每日命中率），其他来源照旧。
    public static func dwellSource(loaded: FeedSource, searching: Bool) -> FeedSource {
        searching && loaded == .guess ? .popular : loaded
    }

    /// 搜索结果整理成一次给全的临时列表（去重、去掉屏蔽的；没有后续页）。
    public static func page(_ rooms: [Room], blocked: Set<String>) -> CatalogPage {
        let kept = dedupeRooms(rooms).filter { !blocked.contains($0.identity) }
        return CatalogPage(rooms: kept, total: kept.count, nextOffset: kept.count)
    }
}

/// 全站搜索的操作代次：每次发起新搜索领一个新号，本地提交、收藏选择、退出搜索时整体作废；
/// 慢请求返回后先核对号，不是当前的就丢弃，不覆盖用户刚选的房间。纯值类型。
public struct SearchEpoch: Equatable, Sendable {
    public private(set) var value = 0
    /// 认领「加载中」状态的那次搜索；只有它（且仍是当前）能把加载中复位。
    public private(set) var loadingOwner: Int?

    public init() {}

    /// 发起新搜索：作废之前所有搜索，返回新号。
    public mutating func begin() -> Int {
        value += 1
        return value
    }

    /// 作废所有在途搜索。
    public mutating func invalidate() { value += 1 }

    public func isCurrent(_ token: Int) -> Bool { token == value }

    /// 任务真正开始前的核对：仍是当前搜索、且任务没被取消，才可以改 `loadGeneration` / `loading` 并发请求。
    public func canStart(_ token: Int, cancelled: Bool) -> Bool { !cancelled && isCurrent(token) }

    /// 这次搜索把界面置为加载中。
    public mutating func claimLoading(_ token: Int) { loadingOwner = token }

    /// 复位加载中：只在有搜索认领时返回 true（调用方据此把 `loading` 置 false）。
    public mutating func releaseLoading() -> Bool {
        defer { loadingOwner = nil }
        return loadingOwner != nil
    }

    /// 同上，但只让仍是当前的认领者复位；过期的旧搜索返回 false，不动新搜索或重载的加载中。
    public mutating func releaseLoading(token: Int) -> Bool {
        guard loadingOwner == token, isCurrent(token) else { return false }
        loadingOwner = nil
        return true
    }
}
