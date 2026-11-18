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

/// 多路画质上限（ios-triple 修订 v2 H2）：最终上限 = min(尺寸上限, 热上限)。
/// - 尺寸上限：显示 1、2 路时不限（两路都用最高档，裁决 3）；3 路时按格子里画面的像素高度选不超过它的最高档。
/// - 热上限：`.serious` 降一档、`.critical` 降两档，以尺寸上限为基准逐档下降，已是最低档就保持最低档；
///   回到 `.fair` 及以下并连续 60 秒才回升到尺寸上限，期间再次发热重新计时。
public struct LaneQuality: Equatable, Sendable {
    public static let recoveryDelay: TimeInterval = 60
    /// 最低档，不比省电档更低。
    public static let minimumBitrate: Double = 896_000
    /// 档位（与 `PowerPolicy` 的分档一致），从低到高。
    public static let ladder: [Double] = [896_000, 1_696_000, 3_096_000, 5_128_000]

    /// 热状态要求降几档：0 未降，1 serious，2 critical。只升不降，回升时一次清零。
    public private(set) var stepsDown: Int
    /// 回到 fair 及以下的起点。再次升到 serious 就清掉，60 秒必须连续。
    private var fairSince: Date?

    public init() {
        stepsDown = 0
        fairSince = nil
    }

    public var isLimited: Bool { stepsDown > 0 }

    public mutating func observe(_ state: PhoneThermalState, now: Date) {
        if state >= .serious {
            stepsDown = max(stepsDown, state == .critical ? 2 : 1)
            fairSince = nil
            return
        }
        guard isLimited else { return }
        if fairSince == nil { fairSince = now }
        if let fairSince, now.timeIntervalSince(fairSince) >= Self.recoveryDelay {
            stepsDown = 0
            self.fairSince = nil
        }
    }

    /// 回升还要等多久：降档中且已回到 fair 及以下才有值（满 60 秒为 0）；还在发热或没降档为 nil。
    /// 调用方据此只排一次性的回升检查，不用空转定时器。
    public func recoveryRemaining(now: Date) -> TimeInterval? {
        guard isLimited, let fairSince else { return nil }
        return max(0, Self.recoveryDelay - now.timeIntervalSince(fairSince))
    }

    /// 尺寸上限。`displayHeight × screenScale` 是格子里画面实际显示的像素高度；3 路时还没有尺寸就落到最低档。
    /// iPhone 竖屏三格里 16:9 画面约 226 点 × 3 = 678 像素，对应 1.696M 档。
    public static func sizeCap(displayHeight: Double, screenScale: Double, shownLanes: Int) -> Double {
        guard shownLanes >= 3 else { return .infinity }
        let pixels = displayHeight * screenScale
        guard pixels.isFinite, pixels > 0 else { return minimumBitrate }
        return max(minimumBitrate, PowerPolicy.peakBitrate(pixelHeight: pixels, resting: false))
    }

    /// 热上限：以 `base`（不限视为最高档）为基准降 `stepsDown` 档；未降档为 `.infinity`。
    public func thermalCap(below base: Double) -> Double {
        guard isLimited else { return .infinity }
        let index = Self.ladder.lastIndex { $0 <= base } ?? 0
        return Self.ladder[max(0, index - stepsDown)]
    }

    /// 最终上限 = min(尺寸上限, 热上限)。
    public func cap(displayHeight: Double, screenScale: Double, shownLanes: Int) -> Double {
        let size = Self.sizeCap(displayHeight: displayHeight, screenScale: screenScale, shownLanes: shownLanes)
        return min(size, thermalCap(below: size))
    }
}
