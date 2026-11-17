import Foundation

/// 上游子列表地址（带 `session=`）闲置一段时间后会 403。中继遇到这种失败时，不直接让 AVPlayer 看到 503，
/// 而是请上层重新解析当前房间、拿到新地址后原地续上。这里只放判定：哪些失败算地址过期、续期的限次与间隔。
public enum RenewalPolicy {
    /// 视为「地址已过期、重新解析可能解决」的上游状态码。
    public static func isExpiry(_ failure: RelayFailure?) -> Bool {
        guard case .http(let status) = failure else { return false }
        return status == 403 || status == 410
    }

    /// 一次续期（重新解析 + 取主列表选档）的最长等待。回答 AVPlayer 的请求遇到续期时，
    /// 在常规有界等待之外最多再等这么久；超过就让 AVPlayer 看到失败，走播放器自己的恢复。
    public static let waitBound: TimeInterval = 8

    /// 请求被取消（暂停 / 换源）而不是网络故障：不能当作需要走侧车的失败。
    public static func isCancellation(_ error: Error) -> Bool {
        if #available(macOS 10.15, *), error is CancellationError { return true }
        if (error as? URLError)?.code == .cancelled { return true }
        let ns = error as NSError
        return ns.domain == NSURLErrorDomain && ns.code == NSURLErrorCancelled
    }
}

/// 一个播放源的续期预算：滑动窗口内最多 `limit` 次、相邻两次至少隔 `minGap`。
/// 地址换了仍立刻 403（房间其实已关播等）时不会打转，耗尽后交给失败与重试路径。
public struct RenewalBudget: Equatable, Sendable {
    public let limit: Int
    public let window: TimeInterval
    public let minGap: TimeInterval
    private var attempts: [Date] = []

    public init(limit: Int = 3, window: TimeInterval = 60, minGap: TimeInterval = 3) {
        self.limit = limit
        self.window = window
        self.minGap = minGap
    }

    public func canRenew(now: Date) -> Bool {
        let recent = attempts.filter { now.timeIntervalSince($0) < window }
        guard recent.count < limit else { return false }
        if let last = recent.last, now.timeIntervalSince(last) < minGap { return false }
        return true
    }

    /// 退回 `at` 那次占用（续期因取消而没有完成时用）：它不再占次数，也不再占最短间隔。
    public mutating func refund(at date: Date) {
        if let index = attempts.lastIndex(of: date) { attempts.remove(at: index) }
    }

    /// 允许则记一次并返回 true；不允许不记。
    public mutating func take(now: Date) -> Bool {
        guard canRenew(now: now) else { return false }
        attempts = attempts.filter { now.timeIntervalSince($0) < window }
        attempts.append(now)
        return true
    }
}

/// 续期结果是否仍归当前播放源：起播代次没变、房间没变、任务没被取消。
/// 慢续期可能在换档、重新解析、换房、停播之后才返回，过期的结果不能再写进缓存与可播地址。
public enum RenewalFreshness {
    public static func isCurrent(
        startedGeneration: Int, currentGeneration: Int,
        startedRoom: String, currentRoom: String?, cancelled: Bool
    ) -> Bool {
        !cancelled && startedGeneration == currentGeneration && currentRoom == startedRoom
    }
}

/// 一个播放源的续期协调：视频与音轨可能同时 403，共用一次续期；预算用尽不再发起。
/// 新地址由共享任务内部写入（`apply`），任何一路等到续期完成后重取，读到的一定是新地址。
/// `recovering` 把「续期后的那次重取」也算进续期中，直到重取结束。`cancel()` 随源生命周期调用：
/// 在途续期作废，结果不再写入；取消后直到 `resume()`（源重新开始刷新）不再接受新的续期注册。
/// 取消代次 `epoch` 同时保护注册与提交：提交方记下注册时的代次，写入前用 `isCurrent` 核对。
/// 预算不随取消清零，但因取消而没有完成的那一次会退回额度。
@available(macOS 10.15, *)
public final class RenewalCoordinator: @unchecked Sendable {
    private let lock = NSLock()
    private var budget: RenewalBudget
    private var task: Task<Bool, Never>?
    private var generation = 0
    private var accepting = true
    private var recoveries = 0

    public init(budget: RenewalBudget = RenewalBudget()) {
        self.budget = budget
    }

    /// 有续期在途，或有等续期的重取还没结束。
    public var isActive: Bool {
        lock.withLock { task != nil || recoveries > 0 }
    }

    /// 当前取消代次。提交方在注册前读取，写入时用 `isCurrent` 核对。
    public var epoch: Int { lock.withLock { generation } }

    /// 代次未变、且仍接受续期。取消（`cancel()`）发生在持有源锁时，提交方在源锁内调用即无窗口。
    public func isCurrent(_ epoch: Int) -> Bool {
        lock.withLock { generation == epoch && accepting }
    }

    /// 在途就等它，否则（预算允许时）发起。返回时 `apply` 已执行完：成功即新地址已写入。
    /// `fetch` 拿新地址；`apply` 把它写进源，返回是否写入成功。预算不允许返回 false。
    public func renew(
        now: Date = Date(),
        fetch: @escaping @Sendable () async -> StreamRendition?,
        apply: @escaping @Sendable (StreamRendition) -> Bool
    ) async -> Bool {
        let shared: Task<Bool, Never>? = lock.withLock {
            guard accepting else { return nil }
            if let task { return task }
            guard budget.take(now: now) else { return nil }
            let mine = generation
            let created = Task { [weak self] in
                let fresh = await fetch()
                var applied = false
                if !Task.isCancelled, let fresh { applied = apply(fresh) }
                // 因取消而没有完成的续期不占预算。
                if !applied, Task.isCancelled { self?.refund(now) }
                self?.finish(mine)
                return applied
            }
            task = created
            return created
        }
        guard let shared else { return false }
        return await shared.value
    }

    /// 包住「续期 + 续期后重取」，期间 `isActive` 为真。
    public func recovering<T>(_ body: () async throws -> T) async rethrows -> T {
        lock.withLock { recoveries += 1 }
        defer { lock.withLock { recoveries -= 1 } }
        return try await body()
    }

    public func cancel() {
        lock.withLock {
            generation += 1
            accepting = false
            task?.cancel()
            task = nil
        }
    }

    /// 源重新开始刷新：恢复接受续期注册。
    public func resume() {
        lock.withLock { accepting = true }
    }

    private func refund(_ date: Date) {
        lock.withLock { budget.refund(at: date) }
    }

    private func finish(_ mine: Int) {
        lock.withLock { if generation == mine { task = nil } }
    }
}
