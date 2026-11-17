import Foundation

public struct Presence: Equatable, Sendable {
    public var conditionalOpacity: Bool
    public var clickThrough: Bool
    public var pointerInside: Bool
    public var interacting: Bool
    public var restingOpacity: Double
    public var engagedOpacity: Double

    public init(
        conditionalOpacity: Bool = true,
        clickThrough: Bool = true,
        pointerInside: Bool = false,
        interacting: Bool = false,
        restingOpacity: Double = 0.42,
        engagedOpacity: Double = 1
    ) {
        self.conditionalOpacity = conditionalOpacity
        self.clickThrough = clickThrough
        self.pointerInside = pointerInside
        self.interacting = interacting
        self.restingOpacity = min(max(restingOpacity, 0.15), 1)
        self.engagedOpacity = min(max(engagedOpacity, 0.4), 1)
    }

    public var engaged: Bool {
        if !conditionalOpacity { return true }
        return pointerInside || interacting
    }

    public var opacity: Double {
        let value = engaged ? engagedOpacity : restingOpacity
        return min(max(value, 0.15), 1)
    }

    public var ignoresMouseEvents: Bool {
        clickThrough && !engaged
    }
}

/// 「离开后淡出」的指针轮询要不要开。
/// 休眠、老板键、App 隐藏时没有指针可跟，停掉 0.4 秒定时器。
/// 手动暂停不在这里：窗口还在时指针回来要能把控制栏淡入，调用方只要窗口可见就会继续轮询。
public enum PointerPolling {
    public static func needed(
        conditionalOpacity: Bool,
        windowVisible: Bool,
        hasWindow: Bool,
        bossHidden: Bool,
        systemInactive: Bool,
        appHidden: Bool
    ) -> Bool {
        conditionalOpacity && windowVisible && hasWindow
            && !bossHidden && !systemInactive && !appHidden
    }
}
