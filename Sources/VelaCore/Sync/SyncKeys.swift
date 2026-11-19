import Foundation

/// 同步文档里的键规则（docs/plans/sync.md 第 6.3 节）。
public enum SyncKeys {
    /// 本端认识、会投影到本机的站点前缀。其他合规前缀照常参与合并与裁剪，但不投影。
    public static let projectedSite = "cb"
    public static let settingKeys: Set<String> = ["publicOnly", "infoAlwaysOn"]

    // MARK: 房间键

    /// `^[a-z][a-z0-9]{1,7}:[a-z0-9_]{2,30}$`
    public static func isRoomKey(_ key: String) -> Bool {
        let b = Array(key.utf8)
        guard let colon = b.firstIndex(of: UInt8(ascii: ":")) else { return false }
        let site = b[..<colon], user = b[(colon + 1)...]
        guard (2...8).contains(site.count), isLower(site.first!), site.dropFirst().allSatisfy({ isLower($0) || isDigit($0) }) else {
            return false
        }
        return isUsername(user)
    }

    static func isUsername<C: Collection>(_ b: C) -> Bool where C.Element == UInt8 {
        (2...30).contains(b.count) && b.allSatisfy { isLower($0) || isDigit($0) || $0 == UInt8(ascii: "_") }
    }

    private static func isLower(_ c: UInt8) -> Bool { c >= UInt8(ascii: "a") && c <= UInt8(ascii: "z") }
    private static func isDigit(_ c: UInt8) -> Bool { c >= UInt8(ascii: "0") && c <= UInt8(ascii: "9") }

    /// 本机无前缀的名字映射为 `cb:` + `Curation.key`；不合规返回 nil（不进同步）。
    public static func roomKey(localName: String) -> String? {
        let key = projectedSite + ":" + Curation.key(localName)
        return isRoomKey(key) ? key : nil
    }

    public static func site(ofRoomKey key: String) -> Substring { key.prefix { $0 != ":" } }

    public static func username(ofRoomKey key: String) -> String {
        guard let colon = key.firstIndex(of: ":") else { return key }
        return String(key[key.index(after: colon)...])
    }

    public static func isProjected(_ roomKey: String) -> Bool { site(ofRoomKey: roomKey) == projectedSite }

    /// `n` 的载荷：`^[A-Za-z0-9_]{2,30}$`，按 ASCII 转小写后等于键的用户名部分。
    public static func isDisplayName(_ name: String, forRoomKey key: String) -> Bool {
        let b = Array(name.utf8)
        guard (2...30).contains(b.count), b.allSatisfy({ isAlpha($0) || isDigit($0) || $0 == UInt8(ascii: "_") }) else {
            return false
        }
        return Array(asciiLowercased(name).utf8) == Array(username(ofRoomKey: key).utf8)
    }

    private static func isAlpha(_ c: UInt8) -> Bool {
        (c >= UInt8(ascii: "a") && c <= UInt8(ascii: "z")) || (c >= UInt8(ascii: "A") && c <= UInt8(ascii: "Z"))
    }

    static func asciiLowercased(_ s: String) -> String {
        String(decoding: s.utf8.map { ($0 >= 65 && $0 <= 90) ? $0 + 32 : $0 }, as: UTF8.self)
    }

    /// 写入时用的显示名：原文（去首尾空白）合 `n` 的格式时用原文，否则用键的用户名部分。
    static func displayName(original: String, roomKey: String) -> String {
        let trimmed = original.trimmingCharacters(in: .whitespacesAndNewlines)
        return isDisplayName(trimmed, forRoomKey: roomKey) ? trimmed : username(ofRoomKey: roomKey)
    }

    // MARK: 口味键

    public static func isGenderKey(_ key: String) -> Bool {
        ["g:f", "g:m", "g:t", "g:c"].contains { Array($0.utf8) == Array(key.utf8) }
    }

    /// 同步标签域的码点条件（第 6.3 节第 1 步）。
    static func isSyncTagScalar(_ s: Unicode.Scalar) -> Bool {
        guard s.value <= 0xFFFF, !(0xE000...0xF8FF).contains(s.value) else { return false }
        let p = s.properties
        guard !p.isNoncharacterCodePoint, let age = p.age, (age.major, age.minor) <= (13, 0) else { return false }
        guard !p.isWhitespace, p.generalCategory != .control, s != "\"", s != "\\" else { return false }
        return true
    }

    /// 逐码点完整小写映射，不考虑上下文（第 3 步）。
    static func lowercasedPerScalar<S: Sequence>(_ scalars: S) -> String where S.Element == Unicode.Scalar {
        var out = ""
        for s in scalars { out += s.properties.lowercaseMapping }
        return out
    }

    static func nfc(_ s: String) -> String { s.precomposedStringWithCanonicalMapping }

    /// 原始标签 → 同步键（第 1 到 5 步）；域外返回 nil（只留在本机）。
    public static func syncTagKey(raw: String) -> String? {
        let scalars = Array(raw.unicodeScalars)
        guard (1...64).contains(scalars.count), scalars.allSatisfy(isSyncTagScalar) else { return nil }
        let lower = lowercasedPerScalar(scalars)
        guard lower.unicodeScalars.allSatisfy(isSyncTagScalar) else { return nil }
        let key = nfc("t:" + lower)
        guard key.utf8.count - 2 <= 128 else { return nil }
        return key
    }

    /// 接收方检查：码点条件、NFC、不超过 128 字节，且再做一遍第 3、4 步结果不变。
    public static func isSyncTagKey(_ key: String) -> Bool {
        let b = Array(key.utf8)
        guard b.count > 2, b[0] == UInt8(ascii: "t"), b[1] == UInt8(ascii: ":"), b.count - 2 <= 128 else { return false }
        let part = String(decoding: b[2...], as: UTF8.self)
        guard part.unicodeScalars.allSatisfy(isSyncTagScalar) else { return false }
        guard Array(nfc(key).utf8) == b else { return false }
        return Array(nfc("t:" + lowercasedPerScalar(part.unicodeScalars)).utf8) == b
    }

    public static func isVetoKey(_ key: String) -> Bool { isGenderKey(key) || isSyncTagKey(key) }

    /// 本机口味键（`Taste.tagKey` 或 `Taste.genderKey` 的结果）→ 同步键；域外返回 nil。
    /// 本机键已经小写，对它的标签部分再走一遍第 1 到 5 步（小写幂等）。
    public static func syncVetoKey(localKey: String) -> String? {
        if localKey.utf8.starts(with: "g:".utf8) { return isGenderKey(localKey) ? localKey : nil }
        let b = Array(localKey.utf8)
        guard b.count > 2, b[0] == UInt8(ascii: "t"), b[1] == UInt8(ascii: ":") else { return nil }
        return syncTagKey(raw: String(decoding: b[2...], as: UTF8.self))
    }
}
