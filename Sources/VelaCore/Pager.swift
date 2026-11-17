import Foundation

public struct WheelSample: Equatable, Sendable {
    public var deltaY: Double
    public var began: Bool
    public var ended: Bool
    public var momentum: Bool
    public var precise: Bool
    public var phaseNone: Bool

    public init(
        deltaY: Double,
        began: Bool = false,
        ended: Bool = false,
        momentum: Bool = false,
        precise: Bool = false,
        phaseNone: Bool = false
    ) {
        self.deltaY = deltaY
        self.began = began
        self.ended = ended
        self.momentum = momentum
        self.precise = precise
        self.phaseNone = phaseNone
    }
}

public struct WheelInterpreter: Equatable, Sendable {
    public var accumulated: Double = 0
    public var cooldownUntil: Date = .distantPast
    public var gestureThreshold: Double
    public var cooldown: TimeInterval

    public init(gestureThreshold: Double = 36, cooldown: TimeInterval = 0.32) {
        self.gestureThreshold = gestureThreshold
        self.cooldown = cooldown
    }

    public mutating func consume(_ sample: WheelSample, now: Date) -> Int? {
        if sample.momentum { return nil }
        if sample.precise && !sample.phaseNone {
            if sample.began { accumulated = 0 }
            accumulated += sample.deltaY
            guard sample.ended else { return nil }
            let total = accumulated
            accumulated = 0
            return commit(total, now: now, threshold: gestureThreshold)
        }
        return commit(sample.deltaY, now: now, threshold: 0.5)
    }

    private mutating func commit(_ total: Double, now: Date, threshold: Double) -> Int? {
        guard abs(total) >= threshold else { return nil }
        guard now >= cooldownUntil else { return nil }
        cooldownUntil = now.addingTimeInterval(cooldown)
        return total > 0 ? -1 : 1
    }
}
