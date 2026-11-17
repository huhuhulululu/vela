import Foundation

public enum QualityTier: Equatable, Sendable {
    case full
    case low
}

/// 「离开后降低画质」的时机判定（纯逻辑）。离开持续 `restDelay` 秒才降，指针回来恢复，
/// 两次切换至少间隔 `minInterval` 秒，避免划过或频繁进出反复重建播放项。
public struct QualityGovernor: Equatable, Sendable {
    public static let restDelay: TimeInterval = 10
    public static let minInterval: TimeInterval = 8

    public private(set) var tier: QualityTier
    private var restingSince: Date?
    private var lastSwitch: Date

    public init(tier: QualityTier = .full, now: Date) {
        self.tier = tier
        self.lastSwitch = now
    }

    /// 新房间起播：以起播时实际用的档位重新计时。
    public mutating func reset(tier: QualityTier, now: Date) {
        self.tier = tier
        lastSwitch = now
    }

    /// 开关或指针状态变化时调用；只在「开着且离开」时计离开时长。
    public mutating func observe(enabled: Bool, engaged: Bool, now: Date) {
        if enabled && !engaged {
            if restingSince == nil { restingSince = now }
        } else {
            restingSince = nil
        }
    }

    public mutating func didSwitch(to tier: QualityTier, now: Date) {
        self.tier = tier
        lastSwitch = now
    }

    /// 此刻应处的档位（不含最短间隔限制）。
    public func desired(now: Date) -> QualityTier {
        guard let restingSince else { return .full }
        return now.timeIntervalSince(restingSince) >= Self.restDelay ? .low : tier
    }

    /// 距离下一次该切档还要等多久；nil 表示现在无需切、也没有待到点的计时。
    public func delay(now: Date) -> TimeInterval? {
        let gate = max(0, lastSwitch.addingTimeInterval(Self.minInterval).timeIntervalSince(now))
        if let restingSince {
            if tier == .low { return nil }
            let remaining = max(0, restingSince.addingTimeInterval(Self.restDelay).timeIntervalSince(now))
            return max(remaining, gate)
        }
        return tier == .full ? nil : gate
    }
}
