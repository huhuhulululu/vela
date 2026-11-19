import Foundation

/// 收藏夹弹窗里的一行：一个收藏用户名此刻的状态与一行说明。
public struct FavoriteEntry: Equatable, Sendable, Identifiable {
    public enum State: Equatable, Sendable { case online, offline, restricted, missing, unknown }

    public var id: String { username }
    public let username: String
    public let state: State
    /// 仅 online 时有值。
    public let room: Room?
    /// 仅 online：优先 `room.thumbnailURL`，没有则按小写用户名拼接。
    public let thumbnailURL: URL?
    public let detail: String

    public init(username: String, state: State, room: Room?, thumbnailURL: URL?, detail: String) {
        self.username = username
        self.state = state
        self.room = room
        self.thumbnailURL = thumbnailURL
        self.detail = detail
    }
}

/// 收藏夹弹窗的数据：在线的在前（人数多的在前），其余按离线、地区受限、未查询、不存在分段；
/// 离线的按上次开播由近到远，不知道时间的排在离线末尾。纯值类型。
public struct FavoritesBoard: Equatable, Sendable {
    public let online: [FavoriteEntry]
    public let others: [FavoriteEntry]

    public init(online: [FavoriteEntry], others: [FavoriteEntry]) {
        self.online = online
        self.others = others
    }

    public var isEmpty: Bool { online.isEmpty && others.isEmpty }
    public var onlineCount: Int { online.count }

    /// 菜单栏列表顶部的一行摘要。
    public func menuSummary(checking: Bool) -> String {
        if isEmpty { return "还没有收藏" }
        if !online.isEmpty { return "\(onlineCount) 位在线" }
        if checking { return "正在查询…" }
        if others.allSatisfy({ $0.state == .unknown }) { return "还没有查询状态" }
        return "收藏的人现在都不在线"
    }

    /// `statuses` 的键按用户名不区分大小写匹配；没有状态时，`catalog` 里可播放的房间算在线。
    /// `lastBroadcast` 的键为小写用户名，离线的收藏有时间时显示「上次开播 3 小时前」。
    public static func make(
        favorites: [String], statuses: [String: FavoriteStatus], catalog: [Room],
        lastBroadcast: [String: Date] = [:], now: Date = Date()
    ) -> FavoritesBoard {
        var byKey: [String: FavoriteStatus] = [:]
        // 键按排序后的顺序写入，同一用户名有多种大小写时结果稳定（与字典遍历顺序无关）。
        for key in statuses.keys.sorted() {
            let normalized = Curation.key(key)
            if byKey[normalized] == nil || key == normalized { byKey[normalized] = statuses[key] }
        }
        var playable: [String: Room] = [:]
        for room in catalog where room.show.isPlayable {
            let key = Curation.key(room.ref)
            if playable[key] == nil { playable[key] = room }
        }

        var seen = Set<String>()
        var onlineEntries: [FavoriteEntry] = []
        var offline: [FavoriteEntry] = []
        var restricted: [FavoriteEntry] = []
        var unknown: [FavoriteEntry] = []
        var missing: [FavoriteEntry] = []
        for raw in favorites {
            let name = raw.trimmingCharacters(in: .whitespacesAndNewlines)
            let key = Curation.key(name)
            guard !key.isEmpty, seen.insert(key).inserted else { continue }
            let status: FavoriteStatus? = byKey[key] ?? playable[key].map { .online($0) }
            switch status {
            case .online(let room)?:
                onlineEntries.append(FavoriteEntry(
                    username: name,
                    state: .online,
                    room: room,
                    thumbnailURL: room.thumbnailURL ?? Self.fallbackThumb(key),
                    detail: onlineDetail(room)
                ))
            case .offline?:
                let detail = lastBroadcast[key].map { BroadcastAgo.string(since: $0, now: now) } ?? "离线"
                offline.append(FavoriteEntry(username: name, state: .offline, room: nil, thumbnailURL: nil, detail: detail))
            case .restricted?:
                restricted.append(FavoriteEntry(username: name, state: .restricted, room: nil, thumbnailURL: nil, detail: "当前地区不可观看"))
            case .notFound?:
                missing.append(FavoriteEntry(username: name, state: .missing, room: nil, thumbnailURL: nil, detail: "账号不存在"))
            case nil:
                unknown.append(FavoriteEntry(username: name, state: .unknown, room: nil, thumbnailURL: nil, detail: "未查询"))
            }
        }

        let sortedOnline = onlineEntries.enumerated()
            .sorted { lhs, rhs in
                let l = lhs.element.room?.viewers ?? 0
                let r = rhs.element.room?.viewers ?? 0
                return l != r ? l > r : lhs.offset < rhs.offset
            }
            .map(\.element)
        let sortedOffline = recentFirst(offline, lastBroadcast: lastBroadcast)
        return FavoritesBoard(online: sortedOnline, others: sortedOffline + restricted + unknown + missing)
    }

    /// 离线分组：上次开播近的在前；没有时间的排在后面，同组保持收藏先后。
    static func recentFirst(_ entries: [FavoriteEntry], lastBroadcast: [String: Date]) -> [FavoriteEntry] {
        entries.enumerated().sorted { lhs, rhs in
            switch (lastBroadcast[Curation.key(lhs.element.username)], lastBroadcast[Curation.key(rhs.element.username)]) {
            case let (a?, b?): return a != b ? a > b : lhs.offset < rhs.offset
            case (_?, nil): return true
            case (nil, _?): return false
            case (nil, nil): return lhs.offset < rhs.offset
            }
        }.map(\.element)
    }

    /// cb 没有目录缩略图时按小写用户名拼接。带站点前缀的 ref 不拼这条旧地址。
    private static func fallbackThumb(_ key: String) -> URL? {
        guard !key.contains(":") else { return nil }
        return URL(string: "https://thumb.live.mmcdn.com/riw/\(key).jpg")
    }

    private static func onlineDetail(_ room: Room) -> String {
        let viewers = "\(ViewerCount.string(room.viewers))人在看"
        guard let clock = BroadcastClock.string(seconds: room.secondsOnline) else { return viewers }
        return "\(viewers) · \(clock)"
    }
}
