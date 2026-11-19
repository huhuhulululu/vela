/// 投影：同步文档 → 本机视图（docs/plans/sync.md 第 6.5 节）。只读文档，不写回。
public struct SyncProjection: Equatable, Sendable {
    public struct Entry: Equatable, Sendable {
        public let key: String
        /// 有 `n` 用它的载荷，否则用键的用户名部分。
        public let display: String
    }

    /// 收藏中，按生效寄存器 HLC 降序、同 HLC 按键字节升序（最新在前）。含本端不投影的站点。
    public let favorites: [Entry]
    public let blocked: [Entry]
    /// 每个「不想看」键当前是否在（`+`）。
    public let veto: [String: Bool]
    public let settings: [String: Bool]

    public init(_ doc: SyncDocument) {
        var favs: [(hlc: HLC, key: String)] = []
        var blocks: [(hlc: HLC, key: String)] = []
        for (key, room) in doc.rooms {
            if room.isFavorite { favs.append((room.f!.hlc, key)) } else if room.isBlocked { blocks.append((room.b!.hlc, key)) }
        }
        func entries(_ items: [(hlc: HLC, key: String)]) -> [Entry] {
            items.sorted(by: SyncMerge.newerFirst).map { item in
                Entry(key: item.key, display: doc.rooms[item.key]?.n?.payload ?? SyncKeys.username(ofRoomKey: item.key))
            }
        }
        favorites = entries(favs)
        blocked = entries(blocks)
        veto = doc.veto.mapValues(\.isOn)
        settings = doc.settings.mapValues { $0.payload == "1" }
    }

    public func isFavorite(_ key: String) -> Bool { favorites.contains { $0.key == key } }
    public func isBlocked(_ key: String) -> Bool { blocked.contains { $0.key == key } }

    /// 本机 `Curation`：取合法站点（cb、c4）的键，按 sites.md 的 ref 写回（cb 去前缀用显示名，c4 带前缀）；
    /// 不认识的站点只留在文档里（第 6.3 节）。
    public var curation: Curation {
        func local(_ list: [Entry]) -> [String] {
            list.filter { SyncKeys.isProjected($0.key) }.map { SyncKeys.localRef(roomKey: $0.key, display: $0.display) }
        }
        return Curation(favorites: local(favorites), blocked: local(blocked))
    }

    /// 本机 `Taste`：文档里的每个「不想看」键按载荷设置；本机标了「不想看」、属于同步域、但文档里不在的键撤销。
    /// 同步域外（本机专用）的键不动。只改 `vetoed`，不动学习证据。
    public func applying(to taste: Taste) -> Taste {
        var next = taste
        for (key, on) in veto { next = next.settingSyncedVeto(key, on) }
        for local in taste.vetoedKeys {
            guard let key = SyncKeys.syncVetoKey(localKey: local), veto[key] != true else { continue }
            next = next.settingSyncedVeto(local, false)
        }
        return next
    }
}
