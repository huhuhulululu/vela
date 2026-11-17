import Foundation

public struct FeedStore: Equatable, Sendable {
    public private(set) var catalog: [Room] {
        didSet { refreshVisible() }
    }
    public private(set) var query: RoomQuery {
        didSet { refreshVisible() }
    }
    public private(set) var currentUsername: String? {
        // 当前房间一旦换走，搜索输入期间的「保留」就此失效。
        didSet { if retained != nil, currentUsername != retained { retained = nil } }
    }
    /// 搜索框打字期间保留的当前房间：它即使不匹配输入也仍在可见列表里，当前房间因此不会随每个字符换掉。
    /// 回车提交（`setQuery`）或当前房间换走时失效。
    public private(set) var retained: String? {
        didSet { refreshVisible() }
    }
    public var reachedEnd: Bool
    public var total: Int

    public init(
        catalog: [Room] = [],
        query: RoomQuery = RoomQuery(),
        currentUsername: String? = nil,
        reachedEnd: Bool = false,
        total: Int = 0
    ) {
        self.catalog = dedupeRooms(catalog)
        self.query = query
        self.currentUsername = currentUsername
        self.reachedEnd = reachedEnd
        self.total = total
        self.visible = self.catalog.filter(query.matches)
    }

    /// `catalog.filter(query.matches)` 的缓存：只在 `catalog` 或 `query` 被赋值/修改时（didSet）重算，
    /// 读 `rooms` 不再每次全量过滤。它完全由 catalog 与 query 决定，所以不参与相等比较。
    private var visible: [Room]

    private mutating func refreshVisible() {
        guard let retained else {
            visible = catalog.filter(query.matches)
            return
        }
        visible = catalog.filter { query.matches($0) || $0.username == retained }
    }

    public static func == (lhs: FeedStore, rhs: FeedStore) -> Bool {
        lhs.catalog == rhs.catalog
            && lhs.query == rhs.query
            && lhs.currentUsername == rhs.currentUsername
            && lhs.retained == rhs.retained
            && lhs.reachedEnd == rhs.reachedEnd
            && lhs.total == rhs.total
    }

    public var rooms: [Room] { visible }

    /// 按当前查询真实匹配的房间数：不含仅因 `retained` 保留的当前房间（全站搜索的回车判断用它）。
    public var localMatches: Int { catalog.reduce(0) { query.matches($1) ? $0 + 1 : $0 } }

    public var index: Int {
        let list = rooms
        guard !list.isEmpty else { return 0 }
        guard let currentUsername else { return 0 }
        return list.firstIndex { $0.username == currentUsername } ?? 0
    }

    public var current: Room? {
        let list = rooms
        guard !list.isEmpty else { return nil }
        return list[min(index, list.count - 1)]
    }

    public mutating func replace(with page: CatalogPage) {
        let keep = currentUsername
        catalog = dedupeRooms(page.rooms)
        total = page.total
        reachedEnd = page.rooms.isEmpty || page.nextOffset >= page.total
        if let keep, rooms.contains(where: { $0.username == keep }) {
            currentUsername = keep
        } else {
            currentUsername = rooms.first?.username
        }
    }

    /// 追加常规分页的一页。`offset` 是请求这一页时的游标（不传则不做「游标没前进」的兜底）。
    /// 是否到底只由常规分页本身决定：空页、游标走到 `total`，或游标没有前进（防止原地重复请求）。
    /// 整页都是已有房间（例如定向候选提前进了目录）不算到底：游标仍在前进，后面的页还有新房间。
    public mutating func append(_ page: CatalogPage, from offset: Int? = nil) {
        let existing = Set(catalog.map { $0.username.lowercased() })
        let fresh = page.rooms.filter { !existing.contains($0.username.lowercased()) }
        catalog.append(contentsOf: fresh)
        total = max(total, page.total)
        let stalled = offset.map { page.nextOffset <= $0 } ?? false
        if page.rooms.isEmpty || page.nextOffset >= page.total || stalled {
            reachedEnd = true
        }
        if currentUsername == nil {
            currentUsername = rooms.first?.username
        }
    }

    /// 把后到的候选并入当前位置之后：目录里已有的不重复加；当前及之前的位置不动，
    /// 当前之后（含新来的）整体按 `order` 重排。返回真正新增的房间数。不改 `reachedEnd` 与 `total`。
    @discardableResult
    public mutating func mergeAfterCurrent(_ extra: [Room], order: ([Room]) -> [Room]) -> Int {
        let folded = PageMerge.keepingCurrent(existing: catalog, incoming: extra, current: currentUsername, order: order)
        guard folded.added > 0 else { return 0 }
        catalog = folded.rooms
        if currentUsername == nil { currentUsername = rooms.first?.username }
        return folded.added
    }

    /// 后台续页：去重后并入当前项之后并重排尾部，同时按这一页更新总量和是否到底。
    /// 已经有当前房间时不改选中项，调用方不应为此再起播。
    public mutating func mergePageAfterCurrent(_ page: CatalogPage, from offset: Int, order: ([Room]) -> [Room]) {
        total = max(total, page.total)
        let stalled = page.nextOffset <= offset
        if page.rooms.isEmpty || page.nextOffset >= page.total || stalled {
            reachedEnd = true
        }
        mergeAfterCurrent(page.rooms, order: order)
    }

    @discardableResult
    public mutating func step(_ delta: Int) -> Room? {
        let list = rooms
        guard !list.isEmpty, delta != 0 else { return current }
        let next = min(max(index + delta, 0), list.count - 1)
        currentUsername = list[next].username
        return current
    }

    @discardableResult
    public mutating func focus(_ username: String) -> Bool {
        guard let match = rooms.first(where: { $0.username.lowercased() == username.lowercased() }) else {
            return false
        }
        currentUsername = match.username
        return true
    }

    public mutating func insertDirect(_ room: Room) {
        catalog.removeAll { $0.username.lowercased() == room.username.lowercased() }
        let insertIndex: Int
        if let currentUsername, let currentCatalogIndex = catalog.firstIndex(where: { $0.username == currentUsername }) {
            insertIndex = catalog.index(after: currentCatalogIndex)
        } else {
            insertIndex = 0
        }
        catalog.insert(room, at: min(insertIndex, catalog.count))
        currentUsername = room.username
    }

    /// 把指定房间挪到当前房间之后紧接着的位置（保持给定顺序）。只动当前之后的房间，当前及之前不变。
    public mutating func promoteAfterCurrent(_ usernames: [String]) {
        guard let currentUsername,
              let at = catalog.firstIndex(where: { $0.username == currentUsername }) else { return }
        let tail = catalog[(at + 1)...]
        var taken = Set<String>()
        var moved: [Room] = []
        for name in usernames.map({ $0.lowercased() }) where !taken.contains(name) {
            if let room = tail.first(where: { $0.username.lowercased() == name }) {
                taken.insert(name)
                moved.append(room)
            }
        }
        guard !moved.isEmpty else { return }
        let rest = tail.filter { !taken.contains($0.username.lowercased()) }
        catalog = Array(catalog[...at]) + moved + rest
    }

    /// 对当前房间之后的部分整体重排；当前及之前的位置不动。
    public mutating func reorderAfterCurrent(_ order: ([Room]) -> [Room]) {
        let start = currentUsername
            .flatMap { name in catalog.firstIndex { $0.username == name } }
            .map { $0 + 1 } ?? 0
        guard start < catalog.count else { return }
        catalog = Array(catalog[..<start]) + order(Array(catalog[start...]))
    }

    /// 打字预览：只改可见列表，不换当前房间（当前房间被保留在列表里，直到提交或换走）。
    public mutating func previewQuery(_ query: RoomQuery) {
        retained = currentUsername
        self.query = query
    }

    public mutating func setQuery(_ query: RoomQuery) {
        retained = nil
        self.query = query
        let list = rooms
        if let currentUsername, list.contains(where: { $0.username == currentUsername }) { return }
        self.currentUsername = list.first?.username
    }

    /// 更新屏蔽名单。若当前房间被屏蔽，立刻换到它之后的下一个（没有则取之前的一个）。
    public mutating func setBlocked(_ names: Set<String>) {
        setFilters(blocked: names, only: query.only)
    }

    /// 同时更新屏蔽名单与允许名单（收藏来源只显示当前收藏）。当前房间不再匹配时换到之后的下一个，
    /// 没有则取之前的一个。
    /// `hidden` 为 nil 表示不动「不想看」过滤。
    public mutating func setFilters(blocked: Set<String>, only: Set<String>?, hidden: Set<String>? = nil) {
        var next = query
        next.blocked = blocked
        next.only = only
        if let hidden { next.hidden = hidden }
        let list = rooms
        if let currentUsername, let at = list.firstIndex(where: { $0.username == currentUsername }), !next.matches(list[at]) {
            query = next
            self.currentUsername = (list[(at + 1)...].first(where: next.matches) ?? list[..<at].last(where: next.matches))?.username
            return
        }
        setQuery(next)
    }

    /// 直接切到指定房间并保证它就是当前房间：搜索条件会挡住它时先清掉；不在列表里就插到当前之后。
    /// 返回最终当前房间是否就是它。
    @discardableResult
    public mutating func reveal(_ room: Room) -> Bool {
        if !query.matches(room) {
            var cleared = query
            cleared.text = ""
            if cleared.matches(room) { query = cleared }
        }
        if !focus(room.username) { insertDirect(room) }
        return current?.username.lowercased() == room.username.lowercased()
    }

    /// 可见列表为空（已加载的页全被屏蔽或过滤）时翻页的上限，防止无限请求。
    public static let maxBlankPages = 10

    /// 可见列表为空但目录没到末尾时也要继续翻页，直到出现可见房间；`blankPages` 是已连续翻过的空页数。
    public func shouldLoadMore(threshold: Int = 8, blankPages: Int = 0) -> Bool {
        let list = rooms
        guard !reachedEnd else { return false }
        if list.isEmpty { return blankPages < Self.maxBlankPages }
        return index >= list.count - threshold
    }

    public func prefetchUsernames(ahead: Int = 1) -> [String] {
        let list = rooms
        guard !list.isEmpty, ahead > 0 else { return [] }
        let start = index + 1
        guard start < list.count else { return [] }
        let end = min(list.count, start + ahead)
        return list[start..<end].map(\.username)
    }
}
