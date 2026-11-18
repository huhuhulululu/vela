import Foundation

/// 设备热状态。对应系统的 nominal / fair / serious / critical，Core 不引用 ProcessInfo。
public enum PhoneThermalState: Int, Comparable, Sendable {
    case nominal
    case fair
    case serious
    case critical

    public static func < (lhs: PhoneThermalState, rhs: PhoneThermalState) -> Bool {
        lhs.rawValue < rhs.rawValue
    }
}

/// 双窗画质上限。平时不封顶（`.infinity`，两路都用最高档）。
/// 热状态到 `.serious` 及以上时，按画面实际显示高度降档；回到 `.fair` 及以下并持续 60 秒后回升。
public struct LaneQuality: Equatable, Sendable {
    public static let recoveryDelay: TimeInterval = 60
    /// 降档后的下限，不比省电档更低。
    public static let minimumBitrate: Double = 896_000

    public private(set) var isLimited: Bool
    /// 回到 fair 及以下的起点。再次升到 serious 就清掉，60 秒必须连续。
    private var fairSince: Date?

    public init() {
        isLimited = false
        fairSince = nil
    }

    public mutating func observe(_ state: PhoneThermalState, now: Date) {
        if state >= .serious {
            isLimited = true
            fairSince = nil
            return
        }
        guard isLimited else { return }
        if fairSince == nil { fairSince = now }
        if let fairSince, now.timeIntervalSince(fairSince) >= Self.recoveryDelay {
            isLimited = false
            self.fairSince = nil
        }
    }

    /// 回升还要等多久：降档中且已回到 fair 及以下才有值（满 60 秒为 0）；还在发热或没降档为 nil。
    /// 调用方据此只排一次性的回升检查，不用空转定时器。
    public func recoveryRemaining(now: Date) -> TimeInterval? {
        guard isLimited, let fairSince else { return nil }
        return max(0, Self.recoveryDelay - now.timeIntervalSince(fairSince))
    }

    /// 未降档时返回 `.infinity`。降档时用 `displayHeight × screenScale`（videoRect 高度乘显示倍率）
    /// 查 `PowerPolicy`，再保证不低于 `minimumBitrate`。
    /// 竖屏双窗里约 221pt × 3 的 16:9 画面是 663px，对应 1.696M 档。
    public func peakBitrate(displayHeight: Double, screenScale: Double) -> Double {
        guard isLimited else { return .infinity }
        let pixels = displayHeight * screenScale
        guard pixels.isFinite, pixels > 0 else { return Self.minimumBitrate }
        let estimated = PowerPolicy.peakBitrate(pixelHeight: pixels, resting: false)
        guard estimated.isFinite else { return Self.minimumBitrate }
        return max(Self.minimumBitrate, estimated)
    }
}
