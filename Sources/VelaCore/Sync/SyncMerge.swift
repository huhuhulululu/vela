/// 合并 `merge(A, B) = E(U(A, B))`、裁剪 `E` 与体积处理（docs/plans/sync.md 第 6.6、6.7、7 节）。
/// 全部是文档的纯函数，与时钟无关。
public enum SyncMerge {
    /// `U`：三个映射各自取键的并集，同键逐字段取字节序较大的寄存器。
    public static func union(_ a: SyncDocument, _ b: SyncDocument) -> SyncDocument {
        var out = a
        for (key, room) in b.rooms {
            guard let mine = out.rooms[key] else {
                out.rooms[key] = room
                continue
            }
            out.rooms[key] = SyncRoom(
                f: SyncRegister.larger(mine.f, room.f),
                b: SyncRegister.larger(mine.b, room.b),
                n: SyncRegister.larger(mine.n, room.n)
            )
        }
        for (key, reg) in b.veto { out.veto[key] = SyncRegister.larger(out.veto[key], reg) }
        for (key, reg) in b.settings { out.settings[key] = SyncRegister.larger(out.settings[key], reg) }
        return out
    }

    public static func merge(_ a: SyncDocument, _ b: SyncDocument) -> SyncDocument { trim(union(a, b)) }

    // MARK: E

    enum RoomClass { case favorite, blocked, tombstone }

    static func classify(_ room: SyncRoom) -> RoomClass {
        room.isFavorite ? .favorite : room.isBlocked ? .blocked : .tombstone
    }

    /// 键的 UTF-8 字节升序。
    static func keyPrecedes(_ a: String, _ b: String) -> Bool { a.utf8.lexicographicallyPrecedes(b.utf8) }

    /// 排序键「HLC 降序；键字节升序」。
    static func newerFirst(_ a: (hlc: HLC, key: String), _ b: (hlc: HLC, key: String)) -> Bool {
        a.hlc != b.hlc ? a.hlc > b.hlc : keyPrecedes(a.key, b.key)
    }

    /// `E`：每个类别按全序保留前 N 个，删除其余的整个条目。只删除，从不写入。
    public static func trim(_ doc: SyncDocument) -> SyncDocument {
        var out = doc
        var groups: [RoomClass: [(hlc: HLC, key: String)]] = [.favorite: [], .blocked: [], .tombstone: []]
        for (key, room) in doc.rooms {
            switch classify(room) {
            case .favorite: groups[.favorite]!.append((room.f!.hlc, key))
            case .blocked: groups[.blocked]!.append((room.b!.hlc, key))
            case .tombstone: groups[.tombstone]!.append((room.newestHLC, key))
            }
        }
        let caps: [RoomClass: Int] = [.favorite: SyncLimits.favorites, .blocked: SyncLimits.blocked, .tombstone: SyncLimits.roomTombstones]
        for (cls, items) in groups where items.count > caps[cls]! {
            for item in items.sorted(by: newerFirst).dropFirst(caps[cls]!) { out.rooms[item.key] = nil }
        }
        let active = doc.veto.filter { $0.value.isOn }.map { (hlc: $0.value.hlc, key: $0.key) }
        let dead = doc.veto.filter { !$0.value.isOn }.map { (hlc: $0.value.hlc, key: $0.key) }
        for (items, cap) in [(active, SyncLimits.activeVetoes), (dead, SyncLimits.vetoTombstones)] where items.count > cap {
            for item in items.sorted(by: newerFirst).dropFirst(cap) { out.veto[item.key] = nil }
        }
        return out
    }

    /// 各类别都不超过上限（接收方的合法性检查之一）。
    public static func withinCounts(_ doc: SyncDocument) -> Bool {
        var counts: [RoomClass: Int] = [:]
        for room in doc.rooms.values { counts[classify(room), default: 0] += 1 }
        let active = doc.veto.values.filter(\.isOn).count
        return counts[.favorite, default: 0] <= SyncLimits.favorites
            && counts[.blocked, default: 0] <= SyncLimits.blocked
            && counts[.tombstone, default: 0] <= SyncLimits.roomTombstones
            && active <= SyncLimits.activeVetoes
            && doc.veto.count - active <= SyncLimits.vetoTombstones
    }

    // MARK: 体积

    public struct Fitted: Equatable, Sendable {
        public let doc: SyncDocument
        /// 处理后的紧凑 JSON 字节数。
        public let bytes: Int
        /// 是否不超过上限；false 表示墓碑删完仍超（第 6.7 节第 3 步）。
        public let fits: Bool
    }

    /// 第 6.7 节：先执行 `E`，再实测；超过上限时按「最旧墓碑」全序依次删除墓碑。删除墓碑不产生写入。
    public static func fitSize(_ input: SyncDocument, limit: Int = SyncLimits.plaintextBytes) -> Fitted {
        var doc = trim(input)
        var size = doc.encodedSize
        guard size > limit else { return Fitted(doc: doc, bytes: size, fits: true) }
        struct Tomb { let hlc: HLC; let isVeto: Bool; let key: String }
        var tombs: [Tomb] = []
        for (key, room) in doc.rooms where classify(room) == .tombstone { tombs.append(Tomb(hlc: room.newestHLC, isVeto: false, key: key)) }
        for (key, reg) in doc.veto where !reg.isOn { tombs.append(Tomb(hlc: reg.hlc, isVeto: true, key: key)) }
        tombs.sort {
            if $0.hlc != $1.hlc { return $0.hlc < $1.hlc }
            if $0.isVeto != $1.isVeto { return !$0.isVeto }
            return keyPrecedes($0.key, $1.key)
        }
        for tomb in tombs {
            guard size > limit else { break }
            if tomb.isVeto {
                let comma = doc.veto.count > 1 ? 1 : 0
                size -= SyncDocument.pairBytes(tomb.key, doc.veto[tomb.key]!) + comma
                doc.veto[tomb.key] = nil
            } else {
                let comma = doc.rooms.count > 1 ? 1 : 0
                size -= SyncDocument.roomBytesFast(tomb.key, doc.rooms[tomb.key]!) + comma
                doc.rooms[tomb.key] = nil
            }
        }
        return Fitted(doc: doc, bytes: size, fits: size <= limit)
    }
}
