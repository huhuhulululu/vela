/// 寄存器 = 24 位 HLC + 载荷（第 6.2 节）。比较一律按 UTF-8 字节，HLC 在前，所以先比时间再比载荷。
public struct SyncRegister: Hashable, Comparable, Sendable {
    public let raw: String
    public let hlc: HLC

    public init(hlc: HLC, payload: String) {
        self.raw = hlc.hex + payload
        self.hlc = hlc
    }

    /// 解析线格式；HLC 不合静态界限时返回 nil。载荷的合法性由调用方按字段检查。
    public init?(raw: String) {
        let b = Array(raw.utf8)
        guard b.count > 24, let hlc = HLC(hex: String(decoding: b[..<24], as: UTF8.self)), hlc.isStaticallyValid else {
            return nil
        }
        self.raw = raw
        self.hlc = hlc
    }

    public var payload: String { String(decoding: Array(raw.utf8)[24...], as: UTF8.self) }
    /// 载荷为 `+`（收藏、屏蔽、「不想看」在）。
    public var isOn: Bool { raw.utf8.last == UInt8(ascii: "+") && raw.utf8.count == 25 }

    public static func < (a: SyncRegister, b: SyncRegister) -> Bool { a.raw.utf8.lexicographicallyPrecedes(b.raw.utf8) }

    public static func == (a: SyncRegister, b: SyncRegister) -> Bool { a.raw.utf8.elementsEqual(b.raw.utf8) }

    public func hash(into hasher: inout Hasher) { hasher.combine(Array(raw.utf8)) }

    /// 取字节序较大者；缺失的寄存器小于任何真实寄存器。
    static func larger(_ a: SyncRegister?, _ b: SyncRegister?) -> SyncRegister? {
        guard let a else { return b }
        guard let b else { return a }
        return a < b ? b : a
    }
}

/// 一个房间条目：收藏 `f`、屏蔽 `b`、显示名 `n`，至少有一个。
public struct SyncRoom: Equatable, Sendable {
    public var f: SyncRegister?
    public var b: SyncRegister?
    public var n: SyncRegister?

    public init(f: SyncRegister? = nil, b: SyncRegister? = nil, n: SyncRegister? = nil) {
        self.f = f
        self.b = b
        self.n = n
    }

    /// 第 6.5 节「收藏中」：`f` 为 `+`，且 `b` 不为 `+` 或 HLC(f) > HLC(b)。
    public var isFavorite: Bool {
        guard let f, f.isOn else { return false }
        guard let b, b.isOn else { return true }
        return f.hlc > b.hlc
    }

    /// 「已屏蔽」：`b` 为 `+`，且 `f` 不为 `+` 或 HLC(b) ≥ HLC(f)。平局屏蔽胜。
    public var isBlocked: Bool {
        guard let b, b.isOn else { return false }
        guard let f, f.isOn else { return true }
        return b.hlc >= f.hlc
    }

    /// 条目内三个寄存器 HLC 的最大值（墓碑排序用）。
    var newestHLC: HLC { [f, b, n].compactMap { $0?.hlc }.max() ?? .zero }

    var registers: [SyncRegister] { [f, b, n].compactMap { $0 } }
}

/// 同步文档（加密前的明文，第 6.1 节）。所有键都是 ASCII 或 NFC，所以 `String` 身份等于字节身份。
public struct SyncDocument: Equatable, Sendable {
    public var rooms: [String: SyncRoom]
    public var veto: [String: SyncRegister]
    public var settings: [String: SyncRegister]

    public init(rooms: [String: SyncRoom] = [:], veto: [String: SyncRegister] = [:], settings: [String: SyncRegister] = [:]) {
        self.rooms = rooms
        self.veto = veto
        self.settings = settings
    }

    public static let empty = SyncDocument()

    public var allRegisters: [SyncRegister] {
        rooms.values.flatMap(\.registers) + Array(veto.values) + Array(settings.values)
    }

    /// 所有寄存器 HLC 的最大值；空文档为 nil。
    public var maxHLC: HLC? { allRegisters.map(\.hlc).max() }

    // MARK: 写出

    /// 紧凑 JSON，键按 UTF-8 字节升序；字符串原样写出（合法文档不含需转义的字符）。
    public func encoded() -> [UInt8] {
        var out: [UInt8] = []
        out += Array("{\"rooms\":{".utf8)
        for (i, key) in Self.sortedKeys(rooms.keys).enumerated() {
            if i > 0 { out.append(UInt8(ascii: ",")) }
            Self.appendRoom(key, rooms[key]!, into: &out)
        }
        out += Array("},\"schema\":2,\"settings\":".utf8)
        Self.appendRegisters(settings, into: &out)
        out += Array(",\"veto\":".utf8)
        Self.appendRegisters(veto, into: &out)
        out.append(UInt8(ascii: "}"))
        return out
    }

    static func sortedKeys<S: Sequence>(_ keys: S) -> [String] where S.Element == String {
        keys.sorted { $0.utf8.lexicographicallyPrecedes($1.utf8) }
    }

    static func appendRoom(_ key: String, _ room: SyncRoom, into out: inout [UInt8]) {
        SyncJSON.writeString(key, into: &out)
        out += Array(":{".utf8)
        var first = true
        for (name, reg) in [("b", room.b), ("f", room.f), ("n", room.n)] {
            guard let reg else { continue }
            if !first { out.append(UInt8(ascii: ",")) }
            first = false
            SyncJSON.writeString(name, into: &out)
            out.append(UInt8(ascii: ":"))
            SyncJSON.writeString(reg.raw, into: &out)
        }
        out.append(UInt8(ascii: "}"))
    }

    static func appendRegisters(_ map: [String: SyncRegister], into out: inout [UInt8]) {
        out.append(UInt8(ascii: "{"))
        for (i, key) in sortedKeys(map.keys).enumerated() {
            if i > 0 { out.append(UInt8(ascii: ",")) }
            appendPair(key, map[key]!, into: &out)
        }
        out.append(UInt8(ascii: "}"))
    }

    static func appendPair(_ key: String, _ reg: SyncRegister, into out: inout [UInt8]) {
        SyncJSON.writeString(key, into: &out)
        out.append(UInt8(ascii: ":"))
        SyncJSON.writeString(reg.raw, into: &out)
    }

    static func pairBytes(_ key: String, _ reg: SyncRegister) -> Int { key.utf8.count + reg.raw.utf8.count + 5 }

    private static let emptyBytes = SyncDocument().encoded().count

    /// 紧凑写法的字节数，按结构直接累加，不生成字节（与 `encoded().count` 相等，由测试保证）。
    public var encodedSize: Int {
        func separators(_ n: Int) -> Int { max(0, n - 1) }
        var total = Self.emptyBytes + separators(rooms.count) + separators(veto.count) + separators(settings.count)
        for (key, room) in rooms { total += Self.roomBytesFast(key, room) }
        for (key, reg) in veto { total += Self.pairBytes(key, reg) }
        for (key, reg) in settings { total += Self.pairBytes(key, reg) }
        return total
    }

    /// `"key":{"b":"…","f":"…","n":"…"}`
    static func roomBytesFast(_ key: String, _ room: SyncRoom) -> Int {
        var fields = 0, total = key.utf8.count + 5
        for reg in [room.b, room.f, room.n] {
            guard let reg else { continue }
            total += reg.raw.utf8.count + 6
            fields += 1
        }
        return total + max(0, fields - 1)
    }
}

/// 解析接收到的明文（第 7.3 节）。
public enum SyncDecodeResult: Equatable, Sendable {
    case valid(SyncDocument)
    /// `schema` 大于 2：只读，提示升级，不算非法。
    case newerSchema
    case invalid(String)
}

extension SyncDocument {
    public static func decode(_ bytes: [UInt8]) -> SyncDecodeResult {
        let value: SyncJSON.Value
        do {
            value = try SyncJSON.parse(bytes)
        } catch {
            return .invalid("json:\(error)")
        }
        guard case .object(let top) = value else { return .invalid("top-not-object") }
        guard let schemaValue = top.first(where: { $0.key == "schema" })?.value else { return .invalid("missing-schema") }
        guard case .number(let text) = schemaValue, let schema = SyncEncoding.parseDecimal(text) else {
            return .invalid("schema-not-integer")
        }
        if schema > UInt64(SyncLimits.schema) { return .newerSchema }
        guard schema == UInt64(SyncLimits.schema) else { return .invalid("schema-old") }
        guard top.count == 4, Set(top.map(\.key)) == ["schema", "rooms", "veto", "settings"] else {
            return .invalid("top-keys")
        }
        var doc = SyncDocument()
        for member in top {
            switch member.key {
            case "rooms":
                guard case .object(let rooms) = member.value else { return .invalid("rooms-not-object") }
                for entry in rooms {
                    guard SyncKeys.isRoomKey(entry.key) else { return .invalid("room-key") }
                    guard let room = decodeRoom(entry.key, entry.value) else { return .invalid("room:\(entry.key)") }
                    doc.rooms[entry.key] = room
                }
            case "veto":
                guard case .object(let veto) = member.value else { return .invalid("veto-not-object") }
                for entry in veto {
                    guard SyncKeys.isVetoKey(entry.key) else { return .invalid("veto-key") }
                    guard let reg = register(entry.value), ["+", "-"].contains(reg.payload) else { return .invalid("veto-register") }
                    doc.veto[entry.key] = reg
                }
            case "settings":
                guard case .object(let settings) = member.value else { return .invalid("settings-not-object") }
                for entry in settings {
                    guard SyncKeys.settingKeys.contains(entry.key) else { return .invalid("settings-key") }
                    guard let reg = register(entry.value), ["1", "0"].contains(reg.payload) else { return .invalid("settings-register") }
                    doc.settings[entry.key] = reg
                }
            default:
                break
            }
        }
        guard SyncMerge.withinCounts(doc) else { return .invalid("over-limit") }
        return .valid(doc)
    }

    private static func register(_ value: SyncJSON.Value) -> SyncRegister? {
        guard case .string(let raw) = value else { return nil }
        return SyncRegister(raw: raw)
    }

    private static func decodeRoom(_ key: String, _ value: SyncJSON.Value) -> SyncRoom? {
        guard case .object(let fields) = value, !fields.isEmpty else { return nil }
        var room = SyncRoom()
        for field in fields {
            guard let reg = register(field.value) else { return nil }
            switch field.key {
            case "f":
                guard ["+", "-"].contains(reg.payload) else { return nil }
                room.f = reg
            case "b":
                guard ["+", "-"].contains(reg.payload) else { return nil }
                room.b = reg
            case "n":
                guard SyncKeys.isDisplayName(reg.payload, forRoomKey: key) else { return nil }
                room.n = reg
            default:
                return nil
            }
        }
        return room
    }
}
