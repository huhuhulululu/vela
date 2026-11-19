import Foundation

/// 站点短 id。`cb` 是 Chaturbate，`c4` 是 Cam4。格式只要求 `^[a-z][a-z0-9]{1,7}$`，不要求已注册。
public enum SiteID: String, CaseIterable, Codable, Sendable {
    case chaturbate = "cb"
    case cam4 = "c4"

    public static let idPattern = "^[a-z][a-z0-9]{1,7}$"

    public static func isValidID(_ raw: String) -> Bool {
        raw.range(of: idPattern, options: .regularExpression) != nil
    }

    public var displayName: String {
        switch self {
        case .chaturbate: return "Chaturbate"
        case .cam4: return "Cam4"
        }
    }

    /// `cb` 沿用 `Room.isValidUsername`（字母数字下划线，2...30，保留大小写）。
    /// `c4` 同一字符集，但用户名先折成小写再校验：`^[a-z0-9_]{2,30}$`。不放行 `-` 与 `.`。
    public func isValidName(_ name: String) -> Bool {
        switch self {
        case .chaturbate:
            return Room.isValidUsername(name)
        case .cam4:
            return name.range(of: "^[a-z0-9_]{2,30}$", options: .regularExpression) != nil
        }
    }
}

/// 房间身份。磁盘与同步用 `raw`（永远带站点前缀）；内存用 `ref`（`cb` 省略前缀）。
/// 相等与哈希按站点 + 小写用户名，所以 `cb:Alice` 与 `cb:alice` 是同一间。
public struct RoomKey: Hashable, Codable, Sendable, CustomStringConvertible {
    public let site: SiteID
    /// `cb` 去首尾空白后保留大小写（显示与请求用）。`c4` 统一小写。
    public let name: String

    public init?(site: SiteID, name: String) {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        let normalized = site == .cam4 ? trimmed.lowercased() : trimmed
        guard site.isValidName(normalized) else { return nil }
        self.site = site
        self.name = normalized
    }

    /// `"cb:Alice"`、`"c4:bob"`，或裸名（视为 cb）。站点段不合格式、未知站点、名字不合法返回 nil。
    public init?(parsing raw: String) {
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let colon = trimmed.firstIndex(of: ":") else {
            self.init(site: .chaturbate, name: trimmed)
            return
        }
        let siteRaw = String(trimmed[..<colon])
        let name = String(trimmed[trimmed.index(after: colon)...])
        guard SiteID.isValidID(siteRaw), let site = SiteID(rawValue: siteRaw) else { return nil }
        self.init(site: site, name: name)
    }

    /// 名字不合法时也要能落到房间上（`Room.key` 非可选）。不校验。
    init(unchecked site: SiteID, name: String) {
        self.site = site
        self.name = name
    }

    /// 永远带前缀：`"cb:Alice"`、`"c4:sample_one"`。
    public var raw: String { "\(site.rawValue):\(name)" }

    /// 内存简写：cb 省略前缀，其他站点带前缀。
    public var ref: String { site == .chaturbate ? name : raw }

    /// `ref` 的小写，集合与比较用。
    public var normalized: String { ref.lowercased() }

    public var description: String { raw }

    public static func == (lhs: RoomKey, rhs: RoomKey) -> Bool {
        lhs.site == rhs.site && lhs.name.lowercased() == rhs.name.lowercased()
    }

    public func hash(into hasher: inout Hasher) {
        hasher.combine(site)
        hasher.combine(name.lowercased())
    }

    public func encode(to encoder: Encoder) throws {
        var container = encoder.singleValueContainer()
        try container.encode(raw)
    }

    public init(from decoder: Decoder) throws {
        let container = try decoder.singleValueContainer()
        let raw = try container.decode(String.self)
        guard let key = RoomKey(parsing: raw) else {
            throw DecodingError.dataCorruptedError(in: container, debugDescription: "无法解析房间键")
        }
        self = key
    }

    /// 裸名、`cb:x`、`c4:x`（站点段大小写不敏感）都归一到小写 ref。
    /// 认不出的形式退回 trim + lowercased，与旧 `Curation.key` 相同。
    public static func normalizeRef(_ any: String) -> String {
        let trimmed = any.trimmingCharacters(in: .whitespacesAndNewlines)
        if let key = RoomKey(parsing: trimmed) ?? RoomKey(parsing: trimmed.lowercased()) {
            return key.normalized
        }
        return trimmed.lowercased()
    }
}

/// 磁盘 raw 与内存 ref 的转换。短名字（测试与旧文件里的一位用户名）不是合法 `RoomKey`，
/// 仍然按 cb 加前缀 / 去前缀，这样旧文件往返后内存里的键不变。
enum SiteKeys {
    static func disk(_ stored: String) -> String {
        let trimmed = stored.trimmingCharacters(in: .whitespacesAndNewlines)
        if let key = RoomKey(parsing: trimmed) ?? RoomKey(parsing: trimmed.lowercased()) {
            return key.raw
        }
        if let colon = trimmed.firstIndex(of: ":") {
            let siteRaw = String(trimmed[..<colon])
            if SiteID.isValidID(siteRaw.lowercased()) { return trimmed }
        }
        guard !trimmed.isEmpty else { return trimmed }
        return "cb:\(trimmed)"
    }

    /// cb 去掉前缀（保留名字原样），其他已知站点用 ref；认不出则原样返回。
    static func memory(_ stored: String) -> String {
        let trimmed = stored.trimmingCharacters(in: .whitespacesAndNewlines)
        if let key = RoomKey(parsing: trimmed) ?? RoomKey(parsing: trimmed.lowercased()) {
            return key.ref
        }
        guard let colon = trimmed.firstIndex(of: ":") else { return trimmed }
        let siteRaw = String(trimmed[..<colon]).lowercased()
        let name = String(trimmed[trimmed.index(after: colon)...])
        if siteRaw == SiteID.chaturbate.rawValue { return name }
        if siteRaw == SiteID.cam4.rawValue { return "c4:\(name.lowercased())" }
        return trimmed
    }
}
