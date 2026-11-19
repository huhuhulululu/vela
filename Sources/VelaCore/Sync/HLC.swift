/// 混合逻辑时钟时间戳（第 5.1 节）：`l` 48 位毫秒、`c` 16 位计数、`dev` 32 位设备号。
/// 线格式为 24 个小写 hex，字符串字节序等于元组 `(l, c, dev)` 的字典序。
public struct HLC: Hashable, Comparable, Sendable {
    public static let maxL: UInt64 = 0xFFFF_0000_0000
    public static let zero = HLC(l: 0, c: 0, dev: 0)

    public let l: UInt64
    public let c: UInt16
    public let dev: UInt32

    public init(l: UInt64, c: UInt16, dev: UInt32) {
        self.l = l
        self.c = c
        self.dev = dev
    }

    /// 只接受 24 个小写 hex，`l` 不超过 48 位。
    public init?(hex text: Substring) {
        let chars = Array(text.utf8)
        guard chars.count == 24 else { return nil }
        var value: [UInt64] = [0, 0, 0]
        let widths = [12, 4, 8]
        var i = 0
        for (part, width) in widths.enumerated() {
            for _ in 0..<width {
                guard let n = SyncEncoding.nibble(chars[i]) else { return nil }
                value[part] = value[part] << 4 | UInt64(n)
                i += 1
            }
        }
        self.init(l: value[0], c: UInt16(value[1]), dev: UInt32(value[2]))
    }

    public init?(hex text: String) { self.init(hex: Substring(text)) }

    public var hex: String {
        func pad(_ v: UInt64, _ width: Int) -> String {
            let s = String(v, radix: 16)
            return String(repeating: "0", count: width - s.count) + s
        }
        return pad(l, 12) + pad(UInt64(c), 4) + pad(UInt64(dev), 8)
    }

    /// 与接收方当前时间无关的静态界限：`1 ≤ l ≤ 0xFFFF00000000`，`dev ≠ 0`。
    public var isStaticallyValid: Bool { l >= 1 && l <= Self.maxL && dev != 0 }

    public static func < (a: HLC, b: HLC) -> Bool {
        (a.l, a.c, a.dev) < (b.l, b.c, b.dev)
    }

    /// 只比较 `(l, c)`。
    func physicalPrecedes(_ other: HLC) -> Bool { (l, c) < (other.l, other.c) }
}

/// 本设备在某个空间里的时钟状态 `(l, c, dev)`，随同步状态持久化。
public struct SyncClock: Codable, Equatable, Sendable {
    public private(set) var l: UInt64
    public private(set) var c: UInt16
    public let dev: UInt32

    public init(l: UInt64 = 0, c: UInt16 = 0, dev: UInt32) {
        self.l = l
        self.c = c
        self.dev = dev
    }

    public var current: HLC { HLC(l: l, c: c, dev: dev) }

    /// 本地事件：一次用户操作取一个 tick（第 5.1 节）。
    public mutating func tick(pt: UInt64) -> HLC {
        if pt > l {
            (l, c) = (pt, 0)
        } else if c < 0xFFFF {
            c += 1
        } else {
            (l, c) = (l + 1, 0)
        }
        return current
    }

    /// 收到已通过校验的远端文档：`(l', c')` 更大时取之，不 tick。
    public mutating func receive(_ remote: HLC?) {
        guard let remote, (remote.l, remote.c) > (l, c) else { return }
        (l, c) = (remote.l, remote.c)
    }

    /// 重新盖章前把时钟重置到给定值（第 5.2 节）。
    mutating func reset(to value: HLC) {
        (l, c) = (value.l, value.c)
    }
}
