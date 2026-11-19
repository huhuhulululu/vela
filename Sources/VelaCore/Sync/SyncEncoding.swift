/// 第 4.2 节的编码规则：base64url（无填充、拒绝非规范）、小写 hex、无前导零的十进制。
public enum SyncEncoding {
    private static let alphabet = Array("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_".utf8)
    private static let reverse: [Int8] = {
        var table = [Int8](repeating: -1, count: 256)
        for (i, c) in alphabet.enumerated() { table[Int(c)] = Int8(i) }
        return table
    }()

    public static func base64url(_ data: [UInt8]) -> String {
        var out: [UInt8] = []
        out.reserveCapacity((data.count * 4 + 2) / 3)
        var i = 0
        while i + 3 <= data.count {
            let n = UInt32(data[i]) << 16 | UInt32(data[i + 1]) << 8 | UInt32(data[i + 2])
            out += [alphabet[Int(n >> 18 & 63)], alphabet[Int(n >> 12 & 63)], alphabet[Int(n >> 6 & 63)], alphabet[Int(n & 63)]]
            i += 3
        }
        let rest = data.count - i
        if rest == 1 {
            let n = UInt32(data[i]) << 16
            out += [alphabet[Int(n >> 18 & 63)], alphabet[Int(n >> 12 & 63)]]
        } else if rest == 2 {
            let n = UInt32(data[i]) << 16 | UInt32(data[i + 1]) << 8
            out += [alphabet[Int(n >> 18 & 63)], alphabet[Int(n >> 12 & 63)], alphabet[Int(n >> 6 & 63)]]
        }
        return String(decoding: out, as: UTF8.self)
    }

    /// 严格解码：拒绝 `=`、空白、字母表外字符与末位未用比特不为 0 的非规范编码。
    public static func decodeBase64url(_ text: String) -> [UInt8]? {
        let chars = Array(text.utf8)
        guard chars.count % 4 != 1 else { return nil }
        var out: [UInt8] = []
        out.reserveCapacity(chars.count * 3 / 4)
        var acc: UInt32 = 0
        var bits = 0
        for c in chars {
            let v = reverse[Int(c)]
            guard v >= 0 else { return nil }
            acc = acc << 6 | UInt32(v)
            bits += 6
            if bits >= 8 {
                bits -= 8
                out.append(UInt8(acc >> UInt32(bits) & 0xFF))
            }
        }
        guard acc & ((1 << UInt32(bits)) - 1) == 0 else { return nil }
        return out
    }

    public static func hex(_ data: [UInt8]) -> String {
        let digits = Array("0123456789abcdef".utf8)
        var out: [UInt8] = []
        out.reserveCapacity(data.count * 2)
        for b in data { out += [digits[Int(b >> 4)], digits[Int(b & 15)]] }
        return String(decoding: out, as: UTF8.self)
    }

    /// 只接受小写 hex，长度为偶数。
    public static func decodeHex(_ text: String) -> [UInt8]? {
        let chars = Array(text.utf8)
        guard chars.count % 2 == 0 else { return nil }
        var out: [UInt8] = []
        out.reserveCapacity(chars.count / 2)
        var i = 0
        while i < chars.count {
            guard let hi = nibble(chars[i]), let lo = nibble(chars[i + 1]) else { return nil }
            out.append(hi << 4 | lo)
            i += 2
        }
        return out
    }

    static func nibble(_ c: UInt8) -> UInt8? {
        switch c {
        case UInt8(ascii: "0")...UInt8(ascii: "9"): return c - UInt8(ascii: "0")
        case UInt8(ascii: "a")...UInt8(ascii: "f"): return c - UInt8(ascii: "a") + 10
        default: return nil
        }
    }

    public static let maxSafeInteger: UInt64 = (1 << 53) - 1

    /// 十进制：无前导零（`0` 除外）、无符号、无小数与指数，0 到 2^53−1。
    public static func parseDecimal(_ text: String) -> UInt64? {
        let chars = Array(text.utf8)
        guard !chars.isEmpty, chars.count <= 16 else { return nil }
        guard chars.count == 1 || chars[0] != UInt8(ascii: "0") else { return nil }
        var value: UInt64 = 0
        for c in chars {
            guard c >= UInt8(ascii: "0"), c <= UInt8(ascii: "9") else { return nil }
            value = value * 10 + UInt64(c - UInt8(ascii: "0"))
        }
        return value <= maxSafeInteger ? value : nil
    }
}
