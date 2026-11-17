import Foundation

/// 翻页请求的占位：按列表代次区分「同一代次已在加载」与「旧代次的残留请求」。
/// 旧来源的请求还在飞时，新来源的请求照常发出，不被全局标志挡成「到底」。
public struct LoadSlot: Equatable, Sendable {
    private var inflight: Int?

    public init() {}

    /// 同一代次已有请求在飞返回 false（暂时忙，稍后再试）；否则占位并返回 true。
    public mutating func acquire(generation: Int) -> Bool {
        if inflight == generation { return false }
        inflight = generation
        return true
    }

    /// 只释放自己占的位：旧代次的请求收尾时不会清掉新代次的占位。
    public mutating func release(generation: Int) {
        if inflight == generation { inflight = nil }
    }
}

/// 换房时手动暂停的去留：只有用户自己的划动、按键、选择才清除；
/// 自动跳过（失败后的非用户发起换房）必须保留手动暂停，否则下一个房间会被放行起播。
public enum MoveRules {
    public static func pausedAfterMove(manuallyPaused: Bool, userInitiated: Bool) -> Bool {
        userInitiated ? false : manuallyPaused
    }
}

/// 目录翻页游标。重新加载会先清零游标；失败回滚时要恢复「最后一次成功加载的列表」的游标，
/// 否则回滚后翻页从 0 开始，拉到重复页并被误判为到底。`committed` 只在成功加载时更新，
/// 与在途请求次数无关（重叠的重新加载第二次也回滚到它）。
public struct PagingState: Equatable, Sendable {
    public var offset = 0
    public private(set) var committed = 0

    public init(offset: Int = 0) {
        self.offset = offset
        committed = offset
    }

    /// 成功加载：当前游标与已加载快照一起更新。
    public mutating func commit(_ newOffset: Int) {
        offset = newOffset
        committed = newOffset
    }

    /// 重新加载开始：游标清零，返回已加载快照的状态供失败时恢复。
    public mutating func restart() -> PagingState {
        let previous = PagingState(offset: committed)
        offset = 0
        return previous
    }

    /// 重新加载失败，列表仍是旧的：恢复已加载快照的游标。
    public mutating func restore(_ previous: PagingState) { self = previous }
}

/// 「播放器因没有可播房间而空闲」的标记。当前代次的翻页完成时（无论谁发起）统一检查，
/// 一旦出现可见房间就起播，不再靠固定次数的忙等。
public struct IdleStart: Equatable, Sendable {
    public private(set) var idle = false

    public init() {}

    public mutating func markIdle() { idle = true }
    public mutating func clear() { idle = false }

    /// 翻页完成后调用：空闲且已有可播房间时返回 true 并消耗标记，调用方应起播。
    public mutating func takeStart(hasCurrent: Bool) -> Bool {
        guard idle, hasCurrent else { return false }
        idle = false
        return true
    }
}

/// 冷启动闸门。bootstrap 的副作用只做一次；目录请求在年龄已确认时只发一次。
/// 模型初始化可以先领目录请求，视图 `onAppear` 再进 bootstrap 时不会再发。
/// 未确认年龄时不领取，确认前保持不加载。
public struct StartupGate: Equatable, Sendable {
    public private(set) var bootstrapped = false
    public private(set) var catalogRequested = false

    public init() {}

    /// 第一次进入 bootstrap 返回 true，之后（例如 onAppear 再次触发）返回 false。
    public mutating func enterBootstrap() -> Bool {
        guard !bootstrapped else { return false }
        bootstrapped = true
        return true
    }

    /// 已确认年龄且还没发过目录请求时返回 true，调用方应开始加载。
    /// 未确认时不领取，之后确认了仍可以再领这一次。
    public mutating func claimCatalog(ageConfirmed: Bool) -> Bool {
        guard ageConfirmed, !catalogRequested else { return false }
        catalogRequested = true
        return true
    }
}
