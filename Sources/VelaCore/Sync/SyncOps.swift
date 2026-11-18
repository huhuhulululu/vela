import Foundation

/// 本设备在一个空间里的同步数据：文档与时钟。
public struct SyncReplica: Equatable, Sendable {
    public var doc: SyncDocument
    public var clock: SyncClock

    public init(doc: SyncDocument = .empty, clock: SyncClock) {
        self.doc = doc
        self.clock = clock
    }
}

/// 第 6.4 节表格里会写寄存器的本地操作。名字为本机显示原文，口味键为本机键（`Taste.tagKey` / `genderKey`）。
public enum SyncLocalOp: Equatable, Sendable {
    case favorite(String)
    case restoreFavorite(String)
    case unfavorite(String)
    case block(String)
    case unblock(String)
    /// 同步开启时的导入（固定窗口，第 6.4 节）。
    case importing([String])
    case veto(String)
    /// `Taste.restoring`、`boosting`、`removing`。
    case unveto(String)
    /// `Taste.resettingProfile`、`erased`。
    case clearVetoes
    case setting(String, Bool)
}

public enum SyncOpOutcome: Equatable, Sendable {
    /// 写入了寄存器，已执行 `E` 与体积处理。
    case applied(SyncReplica)
    /// 前置条件不满足：不写任何寄存器（本机函数照常执行或原样返回）。
    case unchanged
    /// 「不想看」在同步域外：只在本机生效。
    case localOnly
    /// 同步的「不想看」已有 450 个。
    case vetoQuotaFull
    /// 本机专用的「不想看」已有 50 个。
    case localOnlyQuotaFull
    /// 墓碑删完仍超过 `P`：拒绝这次操作，文档与时钟都不变（第 6.7 节第 3 步）。
    case full
}

/// 本地操作到寄存器的映射（docs/plans/sync.md 第 6.4 节）。前置条件按操作发生时的投影判断。
public enum SyncOps {
    public static func apply(
        _ op: SyncLocalOp, to replica: SyncReplica, pt: UInt64, localOnlyVetoCount: Int = 0
    ) -> SyncOpOutcome {
        var r = replica
        let projection = Facts(doc: r.doc)
        switch op {
        case .favorite(let name):
            guard let key = SyncKeys.roomKey(localName: name) else { return .unchanged }
            writeFavorite(key, original: name, into: &r, h: r.clock.tick(pt: pt))
        case .restoreFavorite(let name):
            guard let key = SyncKeys.roomKey(localName: name), !projection.isBlocked(key), !projection.isFavorite(key) else {
                return .unchanged
            }
            writeFavorite(key, original: name, into: &r, h: r.clock.tick(pt: pt))
        case .unfavorite(let name):
            guard let key = SyncKeys.roomKey(localName: name), projection.isFavorite(key) else { return .unchanged }
            r.doc.rooms[key, default: SyncRoom()].f = SyncRegister(hlc: r.clock.tick(pt: pt), payload: "-")
        case .block(let name):
            guard let key = SyncKeys.roomKey(localName: name) else { return .unchanged }
            let h = r.clock.tick(pt: pt)
            var room = r.doc.rooms[key] ?? SyncRoom()
            room.b = SyncRegister(hlc: h, payload: "+")
            room.f = SyncRegister(hlc: h, payload: "-")
            writeDisplay(key, original: name, room: &room, h: h)
            r.doc.rooms[key] = room
        case .unblock(let name):
            guard let key = SyncKeys.roomKey(localName: name), projection.isBlocked(key) else { return .unchanged }
            r.doc.rooms[key, default: SyncRoom()].b = SyncRegister(hlc: r.clock.tick(pt: pt), payload: "-")
        case .importing(let names):
            let fresh = importWindow(names).filter { !projection.isFavorite($0.key) && !projection.isBlocked($0.key) }
            guard !fresh.isEmpty else { return .unchanged }
            for item in fresh.reversed() { writeFavorite(item.key, original: item.name, into: &r, h: r.clock.tick(pt: pt)) }
        case .veto(let local):
            guard let key = SyncKeys.syncVetoKey(localKey: local) else {
                return localOnlyVetoCount < SyncLimits.localOnlyVetoes ? .localOnly : .localOnlyQuotaFull
            }
            let active = r.doc.veto.values.filter(\.isOn).count
            guard projection.veto[key] == true || active < SyncLimits.activeVetoes else { return .vetoQuotaFull }
            r.doc.veto[key] = SyncRegister(hlc: r.clock.tick(pt: pt), payload: "+")
        case .unveto(let local):
            guard let key = SyncKeys.syncVetoKey(localKey: local) else { return .localOnly }
            guard projection.veto[key] == true else { return .unchanged }
            r.doc.veto[key] = SyncRegister(hlc: r.clock.tick(pt: pt), payload: "-")
        case .clearVetoes:
            let on = r.doc.veto.filter(\.value.isOn).map(\.key)
            guard !on.isEmpty else { return .unchanged }
            let h = r.clock.tick(pt: pt)
            for key in on { r.doc.veto[key] = SyncRegister(hlc: h, payload: "-") }
        case .setting(let key, let value):
            guard SyncKeys.settingKeys.contains(key) else { return .unchanged }
            r.doc.settings[key] = SyncRegister(hlc: r.clock.tick(pt: pt), payload: value ? "1" : "0")
        }
        let fitted = SyncMerge.fitSize(r.doc)
        guard fitted.fits else { return .full }
        r.doc = fitted.doc
        return .applied(r)
    }

    /// 前置条件用到的投影事实（与 `SyncProjection` 同一判定，不排序）。
    struct Facts {
        let doc: SyncDocument
        func isFavorite(_ key: String) -> Bool { doc.rooms[key]?.isFavorite ?? false }
        func isBlocked(_ key: String) -> Bool { doc.rooms[key]?.isBlocked ?? false }
        var veto: VetoFacts { VetoFacts(doc: doc) }
    }

    struct VetoFacts {
        let doc: SyncDocument
        subscript(key: String) -> Bool? { doc.veto[key].map(\.isOn) }
    }

    /// 导入的固定窗口：按文件顺序去掉不合规名字、按键去重，取前 300 个。只由文件内容决定。
    public static func importWindow(_ names: [String]) -> [(name: String, key: String)] {
        var seen = Set<String>()
        var out: [(name: String, key: String)] = []
        for name in names {
            guard let key = SyncKeys.roomKey(localName: name), seen.insert(key).inserted else { continue }
            out.append((name, key))
            if out.count == SyncLimits.importWindow { break }
        }
        return out
    }

    /// `favoriting`：`f = h+`，`b = h-`，`n` 按规则。
    static func writeFavorite(_ key: String, original: String, into r: inout SyncReplica, h: HLC) {
        var room = r.doc.rooms[key] ?? SyncRoom()
        room.f = SyncRegister(hlc: h, payload: "+")
        room.b = SyncRegister(hlc: h, payload: "-")
        writeDisplay(key, original: original, room: &room, h: h)
        r.doc.rooms[key] = room
    }

    /// 显示原文与键不同，或该条目已有 `n` 时，写 `n = h` + 原文（原文不合格式时用键）。
    static func writeDisplay(_ key: String, original: String, room: inout SyncRoom, h: HLC) {
        let trimmed = original.trimmingCharacters(in: .whitespacesAndNewlines)
        let differs = Array(trimmed.utf8) != Array(SyncKeys.username(ofRoomKey: key).utf8)
        guard differs || room.n != nil else { return }
        room.n = SyncRegister(hlc: h, payload: SyncKeys.displayName(original: original, roomKey: key))
    }
}
