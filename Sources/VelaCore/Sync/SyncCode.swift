/// 同步码的结构解析（docs/plans/sync.md 第 4.3 节）：`vela2:` + k43 + `.` + c6 + `@` + origin。
/// 校验和用的 SHA-256 由调用方注入（VelaCore 不依赖平台密码库，见 S2）。
public struct SyncCode: Equatable, Sendable {
    public static let prefix = "vela2:"
    public typealias SHA256 = ([UInt8]) -> [UInt8]

    /// 主密钥 `K`，32 字节。
    public let key: [UInt8]
    /// 规范化的 `https://host[:port]`。
    public let origin: String

    /// 严格解析：任一条不满足即返回 nil（「同步码无效」），不尝试修正。
    public static func parse(_ text: String, sha256: SHA256) -> SyncCode? {
        let b = Array(text.utf8)
        let p = Array(prefix.utf8)
        guard b.count > p.count + 43 + 1 + 6 + 1, b.starts(with: p) else { return nil }
        let kStart = p.count, dot = kStart + 43, cStart = dot + 1, at = cStart + 6
        guard b[dot] == UInt8(ascii: "."), b[at] == UInt8(ascii: "@") else { return nil }
        let k43 = String(decoding: b[kStart..<dot], as: UTF8.self)
        let c6 = String(decoding: b[cStart..<at], as: UTF8.self)
        let origin = String(decoding: b[(at + 1)...], as: UTF8.self)
        guard let key = SyncEncoding.decodeBase64url(k43), key.count == 32 else { return nil }
        guard isCanonicalOrigin(origin), c6 == checksum(k43: k43, origin: origin, sha256: sha256) else { return nil }
        return SyncCode(key: key, origin: origin)
    }

    /// 写出同步码；`key` 必须 32 字节、`origin` 必须规范。
    public static func format(key: [UInt8], origin: String, sha256: SHA256) -> String? {
        guard key.count == 32, isCanonicalOrigin(origin) else { return nil }
        let k43 = SyncEncoding.base64url(key)
        return prefix + k43 + "." + checksum(k43: k43, origin: origin, sha256: sha256) + "@" + origin
    }

    /// c6 = 小写 hex(SHA-256(ASCII(`vela2:` + k43 + `@` + origin)) 的前 3 字节)。
    static func checksum(k43: String, origin: String, sha256: SHA256) -> String {
        SyncEncoding.hex(Array(sha256(Array((prefix + k43 + "@" + origin).utf8)).prefix(3)))
    }

    /// `https://` + 小写 ASCII DNS 名 + 可选 `:port`（1–65535 规范十进制，`:443` 非规范）。
    public static func isCanonicalOrigin(_ origin: String) -> Bool {
        let scheme = Array("https://".utf8)
        let b = Array(origin.utf8)
        guard b.starts(with: scheme) else { return false }
        var hostPart = b[scheme.count...]
        if let colon = hostPart.firstIndex(of: UInt8(ascii: ":")) {
            let portText = String(decoding: hostPart[(colon + 1)...], as: UTF8.self)
            guard let port = SyncEncoding.parseDecimal(portText), (1...65535).contains(port), port != 443 else { return false }
            hostPart = hostPart[..<colon]
        }
        return isDNSName(Array(hostPart))
    }

    static func isDNSName(_ host: [UInt8]) -> Bool {
        guard !host.isEmpty, host.count <= 253 else { return false }
        let labels = host.split(separator: UInt8(ascii: "."), omittingEmptySubsequences: false)
        guard labels.count >= 2 else { return false }
        for label in labels {
            guard (1...63).contains(label.count), label.first != UInt8(ascii: "-"), label.last != UInt8(ascii: "-") else { return false }
            guard label.allSatisfy({ ($0 >= 97 && $0 <= 122) || ($0 >= 48 && $0 <= 57) || $0 == UInt8(ascii: "-") }) else { return false }
        }
        // 拒绝 IP 字面量：顶级标签全是数字的不是 DNS 名（覆盖点分 IPv4；IPv6 已因字符集被拒）。
        return !labels.last!.allSatisfy { $0 >= 48 && $0 <= 57 }
    }

    /// 主机名（不含端口），用于确认页。
    public var host: String {
        let rest = origin.dropFirst("https://".count)
        return String(rest.prefix { $0 != ":" })
    }

    /// 短指纹：`blobID` 前 8 个 hex 转大写，按 4-4 用 `-` 连接。
    public static func fingerprint(blobID: String) -> String {
        let head = Array(blobID.uppercased().prefix(8))
        return String(head.prefix(4)) + "-" + String(head.dropFirst(4))
    }
}
